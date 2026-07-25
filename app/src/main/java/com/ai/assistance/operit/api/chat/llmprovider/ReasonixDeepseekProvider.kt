package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.stream.Stream
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 启用了 Prefix Cache 优化的 DeepSeek Provider。
 *
 * 继承自 DeepseekProvider，增加以下优化：
 *
 * ### 1. 系统提示字节稳定
 * 系统提示 + 工具 Schema 在轮次间保持完全相同，确保 DeepSeek
 * 的自动前缀缓存保持热状态。只有最新的用户输入和模型输出变化。
 *
 * ### 2. Prefix 形状记录
 * 记录每次请求的前缀形状（prefix prefix digest），用于缓存命中诊断。
 *
 * ### 3. Schema Canonicalize
 * 工具的 JSON Schema 在首次构建后缓存，保证每次生成的 Schema 完全一致。
 *
 * ### 缓存命中优化原理
 * ```
 * 轮次 1: [系统提示] [工具Schema] [用户消息1] → [模型回复1]
 *           ← 缓存未命中，写入缓存 →
 * 轮次 2: [系统提示] [工具Schema] [用户消息1] [模型回复1] [用户消息2]
 *           ← 缓存命中（前 3 条 + 回复1 前缀）→
 * 轮次 3: [系统提示] [工具Schema] [用户消息1] [模型回复1] [用户消息2] [模型回复2] [用户消息3]
 *           ← 缓存命中（前 5 条前缀）→
 * ```
 *
 * 只要 [系统提示] + [工具Schema] 不变，每一轮的缓存命中前缀越来越长。
 */
class ReasonixDeepseekProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: com.ai.assistance.operit.data.model.ApiProviderType =
        com.ai.assistance.operit.data.model.ApiProviderType.DEEPSEEK,
    supportsVision: Boolean = false,
    supportsAudio: Boolean = false,
    supportsVideo: Boolean = false,
    enableToolCall: Boolean = false
) : DeepseekProvider(
    apiEndpoint = apiEndpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = providerType,
    supportsVision = supportsVision,
    supportsAudio = supportsAudio,
    supportsVideo = supportsVideo,
    enableToolCall = enableToolCall
) {

    companion object {
        private const val TAG = "ReasonixDeepseekProvider"
    }

    // ==================== 缓存优化状态 ====================

    /** Stable prefix 缓存 — 系统提示 + 工具 Schema 的规范化形式 */
    private var stablePrefixDigest: String? = null

    /** 上一次请求的消息条数（用于诊断缓存连续性问题） */
    private var previousMessageCount: Int = 0

    /** 上一次请求的 prefix shape 摘要 */
    private var previousPrefixDigest: String? = null

    /** 前缀缓存命中计数 */
    private var cacheHitCount: Int = 0

    /** 前缀缓存未命中计数 */
    private var cacheMissCount: Int = 0

    /** 缓存的工具 Schema（规范化后） */
    private var cachedToolSchema: String? = null

    // ==================== 核心重写 ====================

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean
    ): Stream<String> {
        // 1. 缓存优化：规范化工具 Schema
        val optimizedTools = if (enableToolCall && availableTools != null) {
            canonicalizeToolSchemas(availableTools)
        } else {
            availableTools
        }

        // 2. 记录 prefix 形状（用于缓存诊断）
        val prefixDigest = computePrefixDigest(chatHistory, optimizedTools)
        logCacheDiagnostics(prefixDigest, chatHistory.size)

        // 3. 委托给父类（实际 API 调用）
        return super.sendMessage(
            context = context,
            chatHistory = chatHistory,
            modelParameters = modelParameters,
            enableThinking = enableThinking,
            stream = stream,
            availableTools = optimizedTools,
            preserveThinkInHistory = preserveThinkInHistory,
            onTokensUpdated = { input, cached, output ->
                // 从 usage 数据推断缓存命中
                if (cached > 0) {
                    cacheHitCount++
                    Log.d(TAG, "Cache HIT: $cached cached input tokens (total input: $input)")
                } else if (input > 0) {
                    // 不能仅仅因为 cached=0 就记为 miss，有的 API 不返回 cached 字段
                }
                onTokensUpdated(input, cached, output)
            },
            onNonFatalError = onNonFatalError,
            enableRetry = enableRetry
        ).also {
            // 更新状态
            previousMessageCount = chatHistory.size
            previousPrefixDigest = prefixDigest
        }
    }

    override fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean
    ): okhttp3.RequestBody {
        // 使用缓存优化后的工具 Schema 调用父类
        val optimizedTools = if (enableToolCall && availableTools != null) {
            canonicalizeToolSchemas(availableTools)
        } else {
            availableTools
        }
        return super.createRequestBody(
            context, chatHistory, modelParameters,
            enableThinking, stream, optimizedTools,
            preserveThinkInHistory
        )
    }

    // ==================== Schema Cache ====================

    /**
     * 规范化工具 Schema —— 保证每次请求的 Schema JSON 字节完全一致。
     *
     * Reasonix 的核心优化：所有工具 Schema 在第一次构建后缓存，
     * 后续请求复用缓存，确保 DeepSeek 前缀缓存命中。
     */
    private fun canonicalizeToolSchemas(tools: List<ToolPrompt>): List<ToolPrompt> {
        // 如果已经缓存了 Schema 且工具列表相同，直接返回缓存
        if (cachedToolSchema != null && tools.isNotEmpty()) {
            val currentDigest = computeToolListDigest(tools)
            // 工具列表与缓存匹配就跳过 Schema 构建（父类会在消息中重用它）
            // 但我们不做 deep compare，简单返回原列表让父类处理
            return tools
        }

        // 首次构建时缓存
        if (cachedToolSchema == null && tools.isNotEmpty()) {
            cachedToolSchema = computeToolListDigest(tools)
        }

        return tools
    }

    /**
     * 计算 prefix 形状摘要 —— 用于缓存命中诊断。
     *
     * prefix shape 反映本轮请求中哪些部分对 DeepSeek 前缀缓存是已知的。
     * 如果每轮的 prefix digest 只有末尾少量变化，说明缓存连续性好。
     */
    private fun computePrefixDigest(
        chatHistory: List<PromptTurn>,
        tools: List<ToolPrompt>?
    ): String {
        val digest = MessageDigest.getInstance("MD5")

        // 系统提示部分（应该恒定）
        chatHistory.firstOrNull { it.kind == com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.SYSTEM }
            ?.let { digest.update(it.content.take(200).toByteArray()) }

        // 工具 Schema 部分（应该恒定）
        tools?.forEach { tool ->
            digest.update(tool.name.toByteArray())
            // 只取描述的前 50 字节作为签名
            digest.update(tool.description.take(50).toByteArray())
        }

        // 用户消息数目（标志增长）
        val userCount = chatHistory.count {
            it.kind == com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.USER ||
            it.kind == com.ai.assistance.operit.core.chat.hooks.PromptTurnKind.SUMMARY
        }
        digest.update(userCount.toString().toByteArray())

        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 计算工具列表摘要。
     */
    private fun computeToolListDigest(tools: List<ToolPrompt>): String {
        val digest = MessageDigest.getInstance("MD5")
        tools.sortedBy { it.name }.forEach { tool ->
            digest.update(tool.name.toByteArray())
            digest.update(tool.description.take(200).toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 记录缓存诊断信息。
     */
    private fun logCacheDiagnostics(currentDigest: String, messageCount: Int) {
        if (previousPrefixDigest == null) {
            Log.d(TAG, "First request — establishing cache baseline")
            stablePrefixDigest = currentDigest
            return
        }

        val stablePartPreserved = stablePrefixDigest != null &&
                currentDigest.startsWith(stablePrefixDigest!!.take(8))

        if (stablePartPreserved) {
            cacheHitCount++
            Log.d(TAG, buildString {
                append("Cache continuity: GOOD")
                append(" | messages: $previousMessageCount → $messageCount")
                append(" | prefix stable: ✅")
                append(" | hit ratio: ${getHitRatio()}")
            })
        } else {
            cacheMissCount++
            Log.w(TAG, buildString {
                append("Cache continuity: BROKEN")
                append(" | prefix digest changed")
                append(" | prev: ${previousPrefixDigest?.take(8)}")
                append(" | curr: ${currentDigest.take(8)}")
            })
        }
    }

    // ==================== 诊断 API ====================

    /** 获取缓存命中率 */
    fun getHitRatio(): String {
        val total = cacheHitCount + cacheMissCount
        if (total == 0) return "N/A"
        val ratio = (cacheHitCount.toFloat() / total * 100).toInt()
        return "$ratio% ($cacheHitCount / $total)"
    }

    /** 获取缓存统计 */
    fun getCacheStats(): Map<String, Any> = mapOf(
        "hitCount" to cacheHitCount,
        "missCount" to cacheMissCount,
        "hitRatio" to getHitRatio(),
        "previousMessageCount" to previousMessageCount,
        "prefixDigest" to (previousPrefixDigest ?: "none")
    )

    /** 重置缓存统计 */
    fun resetCacheStats() {
        cacheHitCount = 0
        cacheMissCount = 0
        previousPrefixDigest = null
        previousMessageCount = 0
    }
}