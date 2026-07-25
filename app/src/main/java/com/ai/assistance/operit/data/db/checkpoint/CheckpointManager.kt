package com.ai.assistance.operit.data.db.checkpoint

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 会话检查点管理器，移植自 DeepSeek-Reasonix 的 checkpoint 系统。
 *
 * 提供三个核心能力：
 * 1. **检查点（Checkpoint）** — 在指定轮次保存会话快照
 * 2. **回滚（Rewind）** — 恢复到指定检查点
 * 3. **分支（Branch）** — 从检查点创建新的独立会话
 *
 * ### 存储
 * 检查点存储在应用内部存储目录的 `checkpoints/` 下，
 * 每个检查点为一个 .json 文件。
 */
class CheckpointManager(private val appContext: Context) {

    companion object {
        private const val TAG = "CheckpointManager"
        private const val CHECKPOINT_DIR = "checkpoints"
        private const val MAX_CHECKPOINTS_PER_CHAT = 50
    }

    /**
     * 检查点数据
     */
    data class Checkpoint(
        val id: String,
        val chatId: String,
        val label: String,
        val turnIndex: Int,
        val messages: List<PromptTurn>,
        val contextDigest: String,
        val tokenCount: Int,
        val createdAt: Long,
        val parentCheckpointId: String? = null
    )

    /**
     * 创建检查点。
     */
    suspend fun checkpoint(
        chatId: String,
        turnIndex: Int,
        messages: List<PromptTurn>,
        label: String = "turn-$turnIndex",
        parentCheckpointId: String? = null
    ): Checkpoint = withContext(Dispatchers.IO) {
        val digest = buildContextDigest(messages)
        val tokenCount = estimateTokens(messages)

        val cp = Checkpoint(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            label = label,
            turnIndex = turnIndex,
            messages = messages.toList(), // 不可变快照
            contextDigest = digest,
            tokenCount = tokenCount,
            createdAt = System.currentTimeMillis(),
            parentCheckpointId = parentCheckpointId
        )

        // 写入文件
        saveCheckpointToFile(cp)

        // 清理旧的检查点
        enforceMaxCheckpoints(chatId)

        Log.i(TAG, "Checkpoint created: ${cp.id} @ turn $turnIndex for chat $chatId")
        cp
    }

    /**
     * 自动创建检查点（在每轮对话结束后调用）。
     */
    suspend fun autoCheckpoint(
        chatId: String,
        turnIndex: Int,
        messages: List<PromptTurn>
    ): Checkpoint {
        return checkpoint(
            chatId = chatId,
            turnIndex = turnIndex,
            messages = messages,
            label = "auto-turn-$turnIndex"
        )
    }

    /**
     * 回滚到指定检查点。
     *
     * @return 恢复后的消息列表 + 目标轮次索引
     */
    suspend fun rewind(checkpointId: String): RewindResult? = withContext(Dispatchers.IO) {
        val cp = loadCheckpoint(checkpointId) ?: run {
            Log.e(TAG, "Checkpoint not found: $checkpointId")
            return@withContext null
        }

        Log.i(TAG, "Rewinding to checkpoint ${cp.id} (turn ${cp.turnIndex})")
        RewindResult(
            messages = cp.messages,
            turnIndex = cp.turnIndex,
            checkpoint = cp
        )
    }

    /**
     * 从检查点创建分支——新的独立会话。
     *
     * @param checkpointId 源检查点 ID
     * @param newChatId 新会话 ID
     * @param branchLabel 分支标签
     * @return 分支后的消息列表
     */
    suspend fun branch(
        checkpointId: String,
        newChatId: String,
        branchLabel: String = "branch-${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)}"
    ): List<PromptTurn>? = withContext(Dispatchers.IO) {
        val cp = loadCheckpoint(checkpointId) ?: return@withContext null

        Log.i(TAG, "Branching from checkpoint ${cp.id} to new chat $newChatId")

        // 为新分支创建检查点
        checkpoint(
            chatId = newChatId,
            turnIndex = cp.turnIndex,
            messages = cp.messages,
            label = "branch-root: $branchLabel",
            parentCheckpointId = cp.id
        )

        cp.messages
    }

    // ==================== 查询方法 ====================

    /**
     * 获取指定 chat 的所有检查点列表（按时间降序）。
     */
    suspend fun getCheckpoints(chatId: String): List<CheckpointSummary> =
        withContext(Dispatchers.IO) {
            val dir = getChatCheckpointDir(chatId)
            if (!dir.exists()) return@withContext emptyList()

            dir.listFiles { file -> file.extension == "json" }
                ?.mapNotNull { file -> loadCheckpointSummary(file) }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        }

    /**
     * 删除检查点。
     */
    suspend fun deleteCheckpoint(checkpointId: String): Boolean = withContext(Dispatchers.IO) {
        val file = getCheckpointFile(checkpointId)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Deleted checkpoint: $checkpointId")
            true
        } else {
            false
        }
    }

    /**
     * 清空指定 chat 的所有检查点。
     */
    suspend fun clearCheckpoints(chatId: String) = withContext(Dispatchers.IO) {
        val dir = getChatCheckpointDir(chatId)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            Log.i(TAG, "Cleared all checkpoints for chat $chatId")
        }
    }

    // ==================== 内部方法 ====================

    /** 构建上下文摘要（SHA-256） */
    private fun buildContextDigest(messages: List<PromptTurn>): String {
        val content = messages.joinToString("\n") {
            "${it.kind}:${it.toolName}:${it.content.take(100)}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 估算 token 数 */
    private fun estimateTokens(messages: List<PromptTurn>): Int {
        return messages.sumOf { (it.content.length / 3.5f).toInt() + 1 }
    }

    /** 获取 chat 的检查点目录 */
    private fun getChatCheckpointDir(chatId: String): File {
        val base = File(appContext.filesDir, CHECKPOINT_DIR)
        return File(base, sanitizeChatId(chatId))
    }

    /** 获取检查点文件路径 */
    private fun getCheckpointFile(checkpointId: String): File {
        // 在所有 chat 目录中查找
        val base = File(appContext.filesDir, CHECKPOINT_DIR)
        if (!base.exists()) return File(base, "unknown/$checkpointId.json")

        base.listFiles()?.forEach { chatDir ->
            if (chatDir.isDirectory) {
                val file = File(chatDir, "$checkpointId.json")
                if (file.exists()) return file
            }
        }
        return File(base, "unknown/$checkpointId.json")
    }

    /** 保存检查点到文件 */
    private fun saveCheckpointToFile(cp: Checkpoint) {
        val dir = getChatCheckpointDir(cp.chatId)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "${cp.id}.json")
        val json = JSONObject().apply {
            put("id", cp.id)
            put("chatId", cp.chatId)
            put("label", cp.label)
            put("turnIndex", cp.turnIndex)
            put("contextDigest", cp.contextDigest)
            put("tokenCount", cp.tokenCount)
            put("createdAt", cp.createdAt)
            put("parentCheckpointId", cp.parentCheckpointId ?: JSONObject.NULL)

            val messagesArray = JSONArray()
            cp.messages.forEach { msg ->
                messagesArray.put(JSONObject().apply {
                    put("kind", msg.kind.name)
                    put("content", msg.content)
                    put("toolName", msg.toolName ?: JSONObject.NULL)
                })
            }
            put("messages", messagesArray)
        }

        file.writeText(json.toString(2))
    }

    /** 从文件加载检查点 */
    private fun loadCheckpoint(checkpointId: String): Checkpoint? {
        val file = getCheckpointFile(checkpointId)
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            val messagesArray = json.getJSONArray("messages")
            val messages = (0 until messagesArray.length()).map { i ->
                val msgJson = messagesArray.getJSONObject(i)
                val rawToolName = msgJson.optString("toolName", "")
                PromptTurn(
                    kind = PromptTurnKind.valueOf(msgJson.getString("kind")),
                    content = msgJson.getString("content"),
                    toolName = rawToolName.takeIf { it.isNotBlank() }
                )
            }

            val rawParent = json.optString("parentCheckpointId", "")
            Checkpoint(
                id = json.getString("id"),
                chatId = json.getString("chatId"),
                label = json.optString("label", ""),
                turnIndex = json.getInt("turnIndex"),
                messages = messages,
                contextDigest = json.getString("contextDigest"),
                tokenCount = json.getInt("tokenCount"),
                createdAt = json.getLong("createdAt"),
                parentCheckpointId = rawParent.takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load checkpoint $checkpointId", e)
            null
        }
    }

    /** 从文件加载检查点摘要（不含完整消息列表） */
    private fun loadCheckpointSummary(file: File): CheckpointSummary? {
        return try {
            val json = JSONObject(file.readText())
            CheckpointSummary(
                id = json.getString("id"),
                chatId = json.getString("chatId"),
                label = json.optString("label", ""),
                turnIndex = json.getInt("turnIndex"),
                tokenCount = json.getInt("tokenCount"),
                createdAt = json.getLong("createdAt"),
                messageCount = json.getJSONArray("messages").length(),
                hasParent = json.has("parentCheckpointId") &&
                        !json.isNull("parentCheckpointId")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load checkpoint summary from ${file.name}", e)
            null
        }
    }

    /** 限制每个 chat 的最大检查点数 */
    private fun enforceMaxCheckpoints(chatId: String) {
        val dir = getChatCheckpointDir(chatId)
        if (!dir.exists()) return

        val files = dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return

        while (files.size > MAX_CHECKPOINTS_PER_CHAT) {
            val oldest = files.removeAt(0)
            oldest.delete()
            Log.d(TAG, "Removed oldest checkpoint: ${oldest.name}")
        }
    }

    /** 清理 chatId 中的非法路径字符 */
    private fun sanitizeChatId(chatId: String): String {
        return chatId.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }
}

/**
 * 回滚结果
 */
data class RewindResult(
    val messages: List<PromptTurn>,
    val turnIndex: Int,
    val checkpoint: CheckpointManager.Checkpoint
)

/**
 * 检查点摘要（用于列表展示，不含完整消息）
 */
data class CheckpointSummary(
    val id: String,
    val chatId: String,
    val label: String,
    val turnIndex: Int,
    val tokenCount: Int,
    val createdAt: Long,
    val messageCount: Int,
    val hasParent: Boolean
)