package com.ai.assistance.operit.core.chat.context

data class SnipPolicy(
    val headLines: Int,
    val tailLines: Int
) {
    companion object {
        val READ_ONLY = SnipPolicy(headLines = 80, tailLines = 12)
        val SIDE_EFFECT = SnipPolicy(headLines = 40, tailLines = 40)
        val MINIMAL = SnipPolicy(headLines = 10, tailLines = 5)
    }
}
