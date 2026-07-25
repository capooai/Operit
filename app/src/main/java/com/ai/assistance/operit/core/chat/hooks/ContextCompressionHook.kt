package com.ai.assistance.operit.core.chat.hooks

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.context.ContextManager

/**
 * 上下文压缩 Hook —— 注入到 PromptHookRegistry 的 PromptFinalizeHook。
 *
 * 在消息发送前（PromptFinalize 阶段）检查上下文占比，
 * 如果超过阈值则触发多级压缩（Snip → Prune → Compact）。
 *
 * 此 Hook 是 Reasonix 上下文压缩算法与 Operit 现有架构的集成点。
 */
class ContextCompressionHook(
    private val appContext: Context
) : PromptFinalizeHook {

    companion object {
        private const val TAG = "ContextCompressionHook"
        const val HOOK_ID = "reasonix-context-compression"
    }

    override val id: String = HOOK_ID

    /** 上下文管理器实例，按 chatId 缓存 */
    private val contextManagers = mutableMapOf<String, ContextManager>()

    /**
     * 获取或创建 chatId 对应的 ContextManager。
     * 每个对话上下文独立管理。
     */
    private fun getContextManager(chatId: String?): ContextManager {
        val effectiveChatId = chatId ?: "default"
        return contextManagers.getOrPut(effectiveChatId) {
            ContextManager()
        }
    }

    /**
     * 在消息 finalize 阶段触发上下文压缩检查。
     *
     * 不会修改系统提示或 tool prompt，而是在 preparedHistory 上执行
     * Snip/Prune/Compact 操作，并通过 mutation 返回修改后的历史。
     */
    override fun onEvent(context: PromptHookContext): PromptHookMutation {
        val chatHistory = context.preparedHistory
        if (chatHistory.isEmpty()) return PromptHookMutation()

        val chatId = context.chatId
        val manager = getContextManager(chatId)
        val mutableHistory = chatHistory.toMutableList()

        // 异步触发压缩（Hook 是同步接口，这里做轻量检查）
        val windowRatio = manager.getWindowRatio(mutableHistory)
        if (windowRatio < 0.5f) {
            return PromptHookMutation() // 无需压缩
        }

        // 在 Hook 中记录日志，实际压缩由后台协程触发
        Log.d(TAG, "Context window at ${"%.1f".format(windowRatio * 100)}% for chat $chatId")

        // 同步执行 Snip 操作（轻量、零成本）
        if (windowRatio >= 0.6f) {
            val snipped = manager.snipStaleToolResults(mutableHistory)
            if (snipped > 0) {
                Log.d(TAG, "Snipped $snipped stale tool results")
                return PromptHookMutation(preparedHistory = mutableHistory)
            }
        }

        return PromptHookMutation()
    }

    /** 手动触发完整压缩 */
    suspend fun triggerCompact(
        chatId: String?,
        messages: MutableList<PromptTurn>,
        aiService: com.ai.assistance.operit.api.chat.llmprovider.AIService?
    ) {
        val manager = getContextManager(chatId)
        val result = manager.compactManually(messages, aiService)
        Log.i(TAG, "Manual compact result for chat $chatId: $result")
    }

    /** 重置压缩卡死状态 */
    fun resetStuck(chatId: String?) {
        getContextManager(chatId).resetStuck()
    }

    /** 释放 chatId 的上下文管理器 */
    fun releaseContextManager(chatId: String) {
        contextManagers.remove(chatId)
    }
}