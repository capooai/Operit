package com.ai.assistance.operit.core.chat.context

/**
 * 上下文压缩操作的结果状态。
 *
 * 从 ContextManager.kt 独立出来，避免 kapt stub 生成时的类冲突。
 */
sealed class CompactResult {
    /** 无需压缩 */
    data object Noop : CompactResult()

    /** 压缩卡死，暂停自动压缩 */
    data object Stuck : CompactResult()

    /** 温和提醒已发送 */
    data class Noticed(val ratio: Float) : CompactResult()

    /** 工具结果已裁剪，snipedCount 为裁剪的消息数 */
    data class Snipped(val snipedCount: Int) : CompactResult()

    /** 完整压缩完成，savedTokens 为节省的 token 数 */
    data class Compacted(val savedTokens: Int) : CompactResult()
}