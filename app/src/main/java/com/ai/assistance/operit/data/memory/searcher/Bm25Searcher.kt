package com.ai.assistance.operit.data.memory.searcher

import kotlin.math.ln

/**
 * BM25 关键词搜索算法，移植自 DeepSeek-Reasonix 的 bm25.go。
 *
 * 轻量级 BM25 实现，无外部依赖。
 * 与 Operit 现有的 HNSW 向量搜索互补使用。
 *
 * ### 算法参数
 * - k1 = 1.2 — 词频饱和参数
 * - b = 0.75 — 长度归一化参数
 *
 * ### 分词策略
 * - 拉丁文本：转为小写后按空格/标点分割
 * - CJK 文本：拆分为单字词
 */
class Bm25Searcher {
    companion object {
        private const val K1 = 1.2f
        private const val B = 0.75f
        private const val DEFAULT_TOP_K = 10
        private const val SCORE_FLOOR = 0.15f
    }

    /**
     * BM25 搜索结果项
     */
    data class ScoredDocument(
        val id: Long,
        val title: String,
        val content: String,
        val score: Float,
        val snippet: String = ""
    )

    /**
     * 对一组文档执行 BM25 搜索。
     *
     * @param query 搜索查询
     * @param documents 待搜索文档列表（id, title, content 三元组）
     * @param topK 返回的 top K 结果
     * @return 按分数降序排列的搜索结果
     */
    fun search(
        query: String,
        documents: List<SearchDocument>,
        topK: Int = DEFAULT_TOP_K
    ): List<ScoredDocument> {
        if (query.isBlank() || documents.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        val totalDocs = documents.size.toFloat()
        val avgLen = documents.map { it.content.length }.average().toFloat()
        if (avgLen <= 0f) return emptyList()

        // 计算每个词的文档频率
        val docFrequencies = computeDocFrequencies(queryTokens, documents)

        // 逐文档评分
        val scored = documents.map { doc ->
            val docLen = doc.content.length.toFloat()
            val docTokens = tokenize(doc.content)

            var score = 0f
            for (term in queryTokens) {
                val freq = docTokens.count { it == term }.toFloat()
                if (freq <= 0f) continue

                val df = docFrequencies[term] ?: 1f
                // IDF 计算（与 Reasonix 相同）
                val idf = ln(1f + (totalDocs - df + 0.5f) / (df + 0.5f))
                // BM25 评分公式
                val numerator = freq * (K1 + 1f)
                val denominator = freq + K1 * (1f - B + B * docLen / avgLen)
                score += idf * numerator / denominator
            }

            // 生成高亮片段
            val snippet = makeSnippet(doc.content, queryTokens)

            ScoredDocument(
                id = doc.id,
                title = doc.title,
                content = doc.content,
                score = score,
                snippet = snippet
            )
        }

        return scored
            .filter { it.score >= SCORE_FLOOR }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 批量搜索（多个查询合并结果）。
     */
    fun searchMultiQuery(
        queries: List<String>,
        documents: List<SearchDocument>,
        topK: Int = DEFAULT_TOP_K
    ): List<ScoredDocument> {
        val allResults = queries.flatMap { query ->
            search(query, documents, topK * 2)
        }
        // 去重 + 按最高分保留
        return allResults
            .groupBy { it.id }
            .mapValues { (_, docs) -> docs.maxByOrNull { it.score }!! }
            .values
            .sortedByDescending { it.score }
            .take(topK)
    }

    // ==================== 分词器 ====================

    /**
     * 将文本拆分为 token 列表。
     * 拉丁文本转为小写后按非字母数字分割；
     * CJK 文本拆分为单字。
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val latinBuffer = StringBuilder()

        for (char in text) {
            when {
                // CJK 字符：直接作为单字 token
                char in CJK_RANGES -> {
                    // 刷新拉丁缓冲区
                    if (latinBuffer.isNotEmpty()) {
                        val word = latinBuffer.toString().lowercase().trim()
                        if (word.length >= 2) tokens.add(word)
                        latinBuffer.clear()
                    }
                    tokens.add(char.toString())
                }
                // 字母或数字：积累到缓冲区
                char.isLetterOrDigit() -> {
                    latinBuffer.append(char)
                }
                // 分隔符：刷新缓冲区
                else -> {
                    if (latinBuffer.isNotEmpty()) {
                        val word = latinBuffer.toString().lowercase().trim()
                        if (word.length >= 2) tokens.add(word)
                        latinBuffer.clear()
                    }
                }
            }
        }

        // 最后的缓冲区
        if (latinBuffer.isNotEmpty()) {
            val word = latinBuffer.toString().lowercase().trim()
            if (word.length >= 2) tokens.add(word)
        }

        return tokens
    }

    // ==================== 辅助方法 ====================

    /** 计算每个查询词的文档频率 */
    private fun computeDocFrequencies(
        queryTokens: List<String>,
        documents: List<SearchDocument>
    ): Map<String, Float> {
        val frequencies = mutableMapOf<String, Int>()

        for (doc in documents) {
            val docTokens = tokenize(doc.content).toSet()
            for (token in queryTokens) {
                if (token in docTokens) {
                    frequencies[token] = (frequencies[token] ?: 0) + 1
                }
            }
        }

        return frequencies.mapValues { it.value.toFloat() }
    }

    /** 生成以匹配词为中心的摘要片段 */
    private fun makeSnippet(text: String, queryTokens: List<String>, maxLen: Int = 200): String {
        val lower = text.lowercase()
        val tokens = tokenize(text)

        // 找到第一个匹配位置
        val firstMatchIndex = tokens.indexOfFirst { it in queryTokens }
        if (firstMatchIndex < 0) return text.take(maxLen)

        // 从匹配位置周围取片段
        val startIndex = maxOf(0, firstMatchIndex - 10)
        val endIndex = minOf(tokens.size, firstMatchIndex + 15)

        val snippetTokens = tokens.subList(startIndex, endIndex)
        val snippet = snippetTokens.joinToString(" ")

        return if (snippet.length > maxLen) snippet.take(maxLen) + "..." else snippet
    }

    companion object {
        /** CJK Unicode 范围 */
        private val CJK_RANGES = setOf(
            '\u4E00'..'\u9FFF',   // CJK 统一表意文字
            '\u3400'..'\u4DBF',   // CJK 扩展 A
            '\uF900'..'\uFAFF',   // CJK 兼容表意文字
            '\u3000'..'\u303F',   // CJK 符号和标点
            '\uFF00'..'\uFFEF'    // 全角形式
        ).flatMap { it.asSequence() }.toSet()
    }
}