package com.ai.assistance.operit.core.chat.context

/**
 * 工具结果裁剪策略，移植自 DeepSeek-Reasonix。
 *
 * Reasonix 为不同类型的工具定义了不同的 Snip 保留行数：
 * - 只读工具（read_file、grep 等）：保留头部 80 行 + 尾部 12 行（前端加载）
 * - 可变工具（write_file、shell 等）：保留头部 40 行 + 尾部 40 行（命令输出）
 * - 工具可自定义实现 SnipHinter 接口
 */
data class SnipPolicy(
    /** 保留的头部行数 */
    val headLines: Int,
    /** 保留的尾部行数 */
    val tailLines: Int
) {
    companion object {
        /** 只读工具的默认裁剪策略：保留头 80 尾 12 */
        val READ_ONLY = SnipPolicy(headLines = 80, tailLines = 12)

        /** 有副作用的工具的默认裁剪策略：保留头 40 尾 40 */
        val SIDE_EFFECT = SnipPolicy(headLines = 40, tailLines = 40)

        /** 极简策略：仅保留头 10 尾 5 行（强制裁剪时使用） */
        val MINIMAL = SnipPolicy(headLines = 10, tailLines = 5)
    }
}