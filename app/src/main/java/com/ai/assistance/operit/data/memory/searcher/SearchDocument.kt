package com.ai.assistance.operit.data.memory.searcher

/**
 * BM25 搜索的输入文档类型。
 *
 * 从 Bm25Searcher.kt 独立出来，避免 kapt stub 生成时的类冲突。
 */
data class SearchDocument(
    val id: Long,
    val title: String,
    val content: String
)