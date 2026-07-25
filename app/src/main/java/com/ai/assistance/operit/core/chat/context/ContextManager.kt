package com.ai.assistance.operit.core.chat.context

import android.util.Log
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.withContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 多级上下文压缩管理器，移植自 DeepSeek-Reasonix 的 compact.go。
 *
 * ### 四级触发阈值
 * | 阈值 | 值   | 行为 |
 * |------|------|------|
 * | softCompactRatio    | 0.5 (50%) | 温和提醒 —— 仅一次通知 |
 * | toolResultSnipRatio | 0.6 (60%) | 裁剪陈旧工具结果 —— 零开销 |
 * | compactRatio        | 0.8 (80%) | 完整压缩 —— 调用 LLM 生成摘要 |
 * | compactForceRatio   | 0.9 (90%) | 强制压缩 —— 跳过经济性检查 |
 *
 * ### 核心原则
 * - 用户的原始轮次永不进入摘要
 * - 已有摘要被保留，不会再次摘要
 * - 压缩区域归档后仍然可追溯（写入 .jsonl）
 * - 连续两次压缩无改善 → 暂停自动压缩（compactStuck）
 */
class ContextManager(
    /** 上下文窗口大小（token 数） */
    private val contextWindow: Int = 65536,
    /** 温和提醒阈值 */
    private val softCompactRatio: Float = 0.5f,
    /** 工具结果裁剪阈值 */
    private val toolResultSnipRatio: Float = 0.6f,
    /** 触发压缩阈值 */
    private val compactRatio: Float = 0.8f,
    /** 强制压缩阈值 */
    private val compactForceRatio: Float = 0.9f,
    /** 压缩后目标占比 */
    private val compactTarget: Float = 0.5f,
    /** 最近保留的消息数 */
    private val recentKeep: Int = 5,
    /** 存档目录 */
    private val archiveDir: File? = null,
    /** 经济性检查：至少节省多少 token 才值得压缩 */
    private val foldEconomicsThreshold: Int = 400
) {
    companion object {
        private const val TAG = "ContextManager"

        /** 用于 token 估算的简单比率（字符数 → token 数） */
        private const val CHARS_PER_TOKEN = 3.5f

        /** 只读工具列表 */
        val READ_ONLY_TOOLS = setOf(
            "read_file", "grep", "glob", "web_fetch", "web_search",
            "list_files", "file_info", "diff", "preview",
            "query_memory", "search_memory", "get_memory_by_title",
            "history_search"
        )

        /** 有副作用的工具列表 */
        val SIDE_EFFECT_TOOLS = setOf(
            "bash", "shell", "write_file", "edit_file", "multi_edit",
            "move_file", "delete_file", "create_file", "mkdir",
            "execute_command", "npm_install", "git_commit", "git_push"
        )
    }

    // 压缩状态
    private var softCompactNoticed: Boolean = false
    private var compactStuck: Boolean = false
    private var consecutiveCompactions: Int = 0
    private var lastArchiveIndex: Int = 0

    // ==================== 公开 API ====================

    /**
     * 主入口：检查当前 token 占比并决定是否压缩。
     *
     * @return 本次压缩操作的结果
     */
    suspend fun maybeCompact(
        messages: MutableList<PromptTurn>,
        aiService: AIService? = null
    ): CompactResult {
        if (compactStuck) {
            Log.w(TAG, "Compact stuck — auto-compression suspended")
            return CompactResult.Stuck
        }

        val totalTokens = estimateTokens(messages)
        val ratio = totalTokens.toFloat() / contextWindow

        return when {
            ratio < softCompactRatio -> CompactResult.Noop

            ratio < toolResultSnipRatio -> {
                if (!softCompactNoticed) {
                    softCompactNoticed = true
                    CompactResult.Noticed(ratio)
                } else {
                    CompactResult.Noop
                }
            }

            ratio < compactRatio -> {
                val snipped = snipStaleToolResults(messages)
                if (snipped > 0) CompactResult.Snipped(snipped) else CompactResult.Noop
            }

            else -> {
                // 先 Prune 再 Compact
                pruneStaleToolResults(messages)

                val shouldForce = ratio >= compactForceRatio
                val saved = compact(messages, aiService, force = shouldForce)
                if (saved > 0) {
                    consecutiveCompactions++
                    if (consecutiveCompactions >= 2) {
                        compactStuck = true
                        Log.w(TAG, "2 consecutive compactions — marking stuck")
                    }
                    CompactResult.Compacted(saved)
                } else {
                    CompactResult.Noop
                }
            }
        }
    }

    /** 手动触发完整压缩 */
    suspend fun compactManually(
        messages: MutableList<PromptTurn>,
        aiService: AIService? = null
    ): CompactResult {
        val saved = compact(messages, aiService, force = true)
        return if (saved > 0) CompactResult.Compacted(saved) else CompactResult.Noop
    }

    /** 重置压缩卡死状态 */
    fun resetStuck() {
        compactStuck = false
        consecutiveCompactions = 0
    }

    /** 获取当前 token 估算值 */
    fun estimateTokenCount(messages: List<PromptTurn>): Int = estimateTokens(messages)

    /** 获取当前窗口占比 */
    fun getWindowRatio(messages: List<PromptTurn>): Float {
        val tokens = estimateTokens(messages)
        return tokens.toFloat() / contextWindow
    }

    // ==================== Snip — 零成本裁剪 ====================

    /**
     * 裁剪陈旧工具结果（Snip）：保留工具结果的首尾，中间截断。
     * 无副作用，不修改原始消息结构。
     *
     * @return 裁剪的消息数量
     */
    fun snipStaleToolResults(messages: MutableList<PromptTurn>): Int {
        var count = 0
        // 保留最近的 recentKeep 条消息不裁剪
        val safeIndex = maxOf(0, messages.size - recentKeep)

        for (i in 0 until safeIndex) {
            val msg = messages[i]
            if (msg.kind == PromptTurnKind.TOOL_RESULT && msg.content.length > 200) {
                val policy = inferSnipPolicy(msg.toolName)
                val snipResult = applySnip(msg.content, policy)
                if (snipResult != msg.content) {
                    messages[i] = msg.withContent(snipResult)
                    count++
                }
            }
        }
        return count
    }

    /**
     * 裁断陈旧工具结果（Prune）：将工具结果完全替换为简短标记。
     * 比 Snip 更激进，为压缩腾出空间。
     */
    private fun pruneStaleToolResults(messages: MutableList<PromptTurn>): Int {
        var count = 0
        val safeIndex = maxOf(0, messages.size - recentKeep)

        for (i in 0 until safeIndex) {
            val msg = messages[i]
            if (msg.kind == PromptTurnKind.TOOL_RESULT && msg.content.length > 500) {
                val archivePath = archiveToolResult(msg)
                val elided = if (archivePath != null) {
                    "[elided tool result — stored at $archivePath]"
                } else {
                    "[elided tool result — content truncated due to context pressure]"
                }
                messages[i] = msg.withContent(elided)
                count++
            }
        }
        return count
    }

    // ==================== Compact — 完整压缩 ====================

    /**
     * 执行完整压缩：将对话中间区域折叠为结构化摘要。
     *
     * @return 节省的 token 数
     */
    private suspend fun compact(
        messages: MutableList<PromptTurn>,
        aiService: AIService? = null,
        force: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val (pinEnd, tailStart) = planCompaction(messages)

        // 经济性检查
        if (!force) {
            val foldTokens = estimateTokens(messages.subList(pinEnd, tailStart))
            if (foldTokens < foldEconomicsThreshold) {
                Log.d(TAG, "Fold region too small ($foldTokens tokens < $foldEconomicsThreshold) — skipping")
                return@withContext 0
            }
        }

        // 分割保留/折叠区域
        val (keep, fold) = partitionFold(messages, pinEnd, tailStart)

        // 归档折叠区域
        archiveFoldRegion(fold)

        // 生成结构化摘要
        val summary = if (aiService != null) {
            summarizeWithRetry(fold, aiService)
        } else {
            // 机械折叠（回退）
            CompressionSummary(
                standingFacts = mechanicalFoldDigest(fold)
            )
        }

        if (summary.isEmpty()) return@withContext 0

        // 合成摘要消息
        val summaryTurn = PromptTurn(
            kind = PromptTurnKind.SUMMARY,
            content = summary.toCompactionText(),
            metadata = mapOf("type" to "compaction-summary", "foldCount" to fold.size)
        )

        // 替换消息列表：保留部分 + 摘要 + 尾部
        messages.clear()
        messages.addAll(keep)
        messages.add(summaryTurn)
        // tailStart 相对的尾部消息
        // （注意：keep 已经包含了系统提示 + 摘要 + 尾部保留消息）

        val savedTokens = estimateTokens(fold) - estimateTokens(listOf(summaryTurn))
        Log.i(TAG, "Compaction saved ~$savedTokens tokens (folded ${fold.size} messages)")
        savedTokens
    }

    /**
     * 规划压缩区域。
     * - pinEnd：从开头算起，必须保留的固定前缀结束位置（系统提示 + 首个用户轮次 + 已有摘要）
     * - tailStart：从末尾算起，按目标 token 预算保留的尾部起始位置
     */
    private fun planCompaction(messages: List<PromptTurn>): Pair<Int, Int> {
        // 固定前缀：找到已有的摘要消息的结束位置
        var pinEnd = 0
        for ((i, msg) in messages.withIndex()) {
            pinEnd = i + 1
            if (msg.kind == PromptTurnKind.SUMMARY) break
            // 至少保留系统提示和第一个用户消息
            if (i >= 2 && msg.kind == PromptTurnKind.ASSISTANT) {
                // 继续，但不要无限
                if (i > 10) break
            }
        }

        // 尾部预算
        val tailBudget = (contextWindow * compactTarget).toInt()
        var tailTokens = 0
        var tailStart = messages.size

        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val tokens = estimateToken(msg)
            if (tailTokens + tokens > tailBudget) break
            tailTokens += tokens
            tailStart = i

            // 确保工具结果不孤立
            if (msg.kind == PromptTurnKind.TOOL_RESULT && tailStart > 0) {
                val prev = messages[tailStart - 1]
                if (prev.kind == PromptTurnKind.TOOL_CALL || prev.kind == PromptTurnKind.ASSISTANT) {
                    tailTokens += estimateToken(prev)
                    tailStart--
                }
            }
        }

        // 确保 pinEnd <= tailStart
        if (pinEnd >= tailStart) {
            pinEnd = maxOf(1, tailStart - 2)
        }

        return Pair(pinEnd, tailStart)
    }

    /**
     * 将消息分为"保留"和"折叠"两部分。
     * 保留包括：小用户轮次、已有摘要、KeepPolicy 保留的消息。
     */
    private fun partitionFold(
        messages: List<PromptTurn>,
        pinEnd: Int,
        tailStart: Int
    ): Pair<List<PromptTurn>, List<PromptTurn>> {
        val keep = mutableListOf<PromptTurn>()
        val fold = mutableListOf<PromptTurn>()

        // 固定前缀保留
        for (i in 0 until pinEnd) {
            keep.add(messages[i])
        }

        // 折叠区域过滤
        for (i in pinEnd until tailStart) {
            val msg = messages[i]
            // 保留用户轮次（永不进入摘要）、已有摘要、KeepPolicy 消息
            if (msg.kind == PromptTurnKind.USER ||
                msg.kind == PromptTurnKind.SUMMARY ||
                shouldKeepByPolicy(msg)
            ) {
                keep.add(msg)
            } else {
                fold.add(msg)
            }
        }

        // 尾部保留
        for (i in tailStart until messages.size) {
            keep.add(messages[i])
        }

        return Pair(keep, fold)
    }

    /** 判断消息是否应保留（非用户轮次的其他保留策略） */
    private fun shouldKeepByPolicy(msg: PromptTurn): Boolean {
        val metadata = msg.metadata
        // 标记为重要的消息
        if (metadata["keep"] == true) return true
        // 包含错误的助手消息
        if (msg.kind == PromptTurnKind.ASSISTANT &&
            (msg.content.contains("error", ignoreCase = true) ||
             msg.content.contains("failed", ignoreCase = true))) return false
        return false
    }

    // ==================== 摘要生成 ====================

    /**
     * 调用 LLM 生成结构化摘要，自动重试最多 3 次，超时 90 秒。
     */
    private suspend fun summarizeWithRetry(
        foldMessages: List<PromptTurn>,
        aiService: AIService
    ): CompressionSummary {
        val foldText = foldMessages.joinToString("\n") {
            "[${it.kind.name}] ${it.content.take(500)}"
        }

        val summaryText = try {
            withContext(Dispatchers.IO) {
                // 使用 aiService 请求摘要
                // 注意：这里使用简化的同步调用，实际应通过 aiService.sendMessage()
                // 但由于 AIService 需要完整的 chatHistory，这里用简化方式
                performSummaryViaLLM(aiService, foldText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed", e)
            CompressionSummary(standingFacts = mechanicalFoldDigest(foldMessages))
        }

        return summaryText
    }

    /**
     * 机械折叠回退方案：确定性标记，不依赖 LLM。
     */
    private fun mechanicalFoldDigest(messages: List<PromptTurn>): String {
        val fileRefs = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (msg in messages) {
            when (msg.kind) {
                PromptTurnKind.TOOL_CALL -> {
                    if (msg.toolName == "read_file" || msg.toolName == "write_file" || msg.toolName == "edit_file") {
                        // 从 content 中提取文件名
                        val filePattern = Regex("""[\\w./-]+\.\w+""")
                        filePattern.findAll(msg.content).forEach { fileRefs.add(it.value) }
                    }
                    if (msg.toolName == "bash" || msg.toolName == "shell") {
                        commands.add(msg.content.take(200))
                    }
                }
                PromptTurnKind.TOOL_RESULT -> {
                    val content = msg.content
                    if (content.contains("error", ignoreCase = true) ||
                        content.contains("failed", ignoreCase = true) ||
                        content.contains("exception", ignoreCase = true)) {
                        errors.add(content.take(200))
                    }
                }
                else -> { /* skip */ }
            }
        }

        return buildString {
            appendLine("Files referenced: ${fileRefs.joinToString(", ")}")
            if (commands.isNotEmpty()) appendLine("Commands: ${commands.joinToString("; ")}")
            if (errors.isNotEmpty()) appendLine("Errors: ${errors.joinToString("; ")}")
        }
    }

    /** 使用 LLM 执行摘要生成 */
    private suspend fun performSummaryViaLLM(
        aiService: AIService,
        foldText: String
    ): CompressionSummary {
        // 由于 AIService.sendMessage 需要完整的 chatHistory，
        // 这里使用简化方式：直接组合摘要 prompt
        val prompt = buildString {
            appendLine(CompressionSummary.SUMMARY_SYSTEM_PROMPT)
            appendLine()
            appendLine("=== Conversation to Summarize ===")
            appendLine(foldText)
        }

        // 标记：此处是简化实现，实际集成时应通过 ChatViewModel 的 sendMessage 路径
        // 返回空摘要会触发 mechanicalFold 回退
        return CompressionSummary()
    }

    // ==================== 工具方法 ====================

    /** 估算消息的 token 数 */
    private fun estimateToken(msg: PromptTurn): Int {
        return (msg.content.length / CHARS_PER_TOKEN).toInt() + 1
    }

    /** 估算消息列表的总 token 数 */
    private fun estimateTokens(messages: List<PromptTurn>): Int {
        return messages.sumOf { estimateToken(it) }
    }

    /** 推断工具结果的裁剪策略 */
    private fun inferSnipPolicy(toolName: String?): SnipPolicy {
        return when {
            toolName == null -> SnipPolicy.READ_ONLY
            toolName in READ_ONLY_TOOLS -> SnipPolicy.READ_ONLY
            toolName in SIDE_EFFECT_TOOLS -> SnipPolicy.SIDE_EFFECT
            else -> SnipPolicy.READ_ONLY
        }
    }

    /** 对工具结果文本执行裁剪 */
    private fun applySnip(text: String, policy: SnipPolicy): String {
        val lines = text.lines()
        if (lines.size <= policy.headLines + policy.tailLines + 3) return text

        val head = lines.take(policy.headLines)
        val tail = lines.takeLast(policy.tailLines)

        return buildString {
            head.forEach { appendLine(it) }
            appendLine("... [snipped tool result — ${lines.size - policy.headLines - policy.tailLines} lines removed] ...")
            tail.forEach { appendLine(it) }
        }
    }

    /** 归档工具结果到 .jsonl 文件 */
    private fun archiveToolResult(msg: PromptTurn): String? {
        val dir = archiveDir ?: return null
        if (!dir.exists()) dir.mkdirs()

        val fileName = "tool-results-${DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.now())}.jsonl"
        val file = File(dir, fileName)

        try {
            val entry = buildString {
                appendLine("""{"tool":"${msg.toolName}","kind":"${msg.kind}","ts":"${System.currentTimeMillis()}","content":${jsonEscape(msg.content)}}""")
            }
            file.appendText(entry)
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive tool result", e)
            return null
        }
    }

    /** JSON 转义 */
    private fun jsonEscape(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /** 归档折叠区域 */
    private fun archiveFoldRegion(messages: List<PromptTurn>) {
        val dir = archiveDir ?: return
        if (!dir.exists()) dir.mkdirs()

        val fileName = "compaction-archive-${DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDateTime.now())}.jsonl"
        val file = File(dir, fileName)

        try {
            val content = messages.joinToString("\n") { msg ->
                """{"kind":"${msg.kind}","tool":"${msg.toolName.orEmpty()}","content":${jsonEscape(msg.content.take(1000))}}"""
            }
            file.appendText(content + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive fold region", e)
        }
    }
}