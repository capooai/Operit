package com.ai.assistance.operit.data.memory.searcher

import android.util.Log

/**
 * 混合搜索器 —— 结合 BM25 关键词搜索和 HNSW 向量搜索。
 *
 * 两种搜索方法互补：
 * - BM25：关键词精确匹配，适合名称、代码标识符、精确短语搜索
 * - HNSW：语义相似度搜索，适合概念、意图层面的模糊匹配
 *
 * 搜索结果通过 RRF（Reciprocal Rank Fusion）算法合并排序。
 */
class HybridSearcher(
    private val bm25Searcher: Bm25Searcher = Bm25Searcher(),
    /** BM25 结果的权重（0-1），值越高 BM25 排名越靠前 */
    private val bm25Weight: Float = 0.5f,
    /** RRF 常数 k（融合参数） */
    private val rrfK: Int = 60
) {
    companion object {
        private const val TAG = "HybridSearcher"
    }

    /**
     * 执行混合搜索。
     *
     * @param query 搜索查询
     * @param documents BM25 搜索的文档列表（如果启用了 BM25）
     * @param hnswResults 向量搜索的结果（id → 分数对）
     * @param topK 返回结果数
     * @return 融合后的排序结果
     */
    fun hybridSearch(
        query: String,
        documents: List<SearchDocument>,
        hnswResults: Map<Long, Float>,
        topK: Int = 10
    ): List<HybridSearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. 执行 BM25 搜索
        val bm25Results = if (documents.isNotEmpty()) {
            bm25Searcher.search(query, documents, topK * 2)
        } else {
            emptyList()
        }

        // 2. RRF 融合
        return fuseRankings(bm25Results, hnswResults, topK)
    }

    /**
     * 使用 Reciprocal Rank Fusion 算法融合两个搜索的结果。
     *
     * RRF 公式: score(d) = Σ 1 / (k + rank(d))
     * 其中 rank(d) 是文档 d 在各搜索中的排名，k 是常数。
     */
    private fun fuseRankings(
        bm25Results: List<Bm25Searcher.ScoredDocument>,
        hnswResults: Map<Long, Float>,
        topK: Int
    ): List<HybridSearchResult> {
        val fusedScores = mutableMapOf<Long, HybridSearchResult.Builder>()

        // BM25 排名
        for ((rank, result) in bm25Results.withIndex()) {
            val rrfScore = 1.0f / (rrfK + rank + 1)
            fusedScores.getOrPut(result.id) {
                HybridSearchResult.Builder(id = result.id)
            }.apply {
                title = result.title
                snippet = result.snippet
                bm25Score = result.score
                bm25Rank = rank
                rrfScore += rrfScore * bm25Weight
                sources.add("bm25")
            }
        }

        // HNSW 排名
        val sortedHnsw = hnswResults.entries
            .sortedByDescending { it.value }
            .take(topK * 2)

        for ((rank, entry) in sortedHnsw.withIndex()) {
            val rrfScore = 1.0f / (rrfK + rank + 1)
            fusedScores.getOrPut(entry.key) {
                HybridSearchResult.Builder(id = entry.key)
            }.apply {
                vectorScore = entry.value
                vectorRank = rank
                rrfScore += rrfScore * (1.0f - bm25Weight)
                sources.add("hnsw")
            }
        }

        // 排序并返回
        return fusedScores.values
            .map { it.build() }
            .sortedByDescending { it.rrfScore }
            .take(topK)
    }

    /**
     * 仅执行 BM25 搜索（不融合向量结果）。
     */
    fun searchBm25Only(
        query: String,
        documents: List<SearchDocument>,
        topK: Int = 10
    ): List<Bm25Searcher.ScoredDocument> {
        return bm25Searcher.search(query, documents, topK)
    }

    /**
     * 分词辅助（暴露给外部使用）。
     */
    fun tokenize(text: String): List<String> {
        return bm25Searcher.tokenize(text)
    }
}

/**
 * 混合搜索的结果项
 */
data class HybridSearchResult(
    val id: Long,
    val title: String,
    val snippet: String,
    val rrfScore: Float,
    val bm25Score: Float,
    val bm25Rank: Int,
    val vectorScore: Float,
    val vectorRank: Int,
    val sources: List<String>
) {
    /** 是否来自两个搜索源 */
    val isHybridMatch: Boolean get() = sources.size >= 2

    data class Builder(
        val id: Long,
        var title: String = "",
        var snippet: String = "",
        var rrfScore: Float = 0f,
        var bm25Score: Float = 0f,
        var bm25Rank: Int = -1,
        var vectorScore: Float = 0f,
        var vectorRank: Int = -1,
        val sources: MutableList<String> = mutableListOf()
    ) {
        fun build() = HybridSearchResult(
            id = id,
            title = title,
            snippet = snippet,
            rrfScore = rrfScore,
            bm25Score = bm25Score,
            bm25Rank = bm25Rank,
            vectorScore = vectorScore,
            vectorRank = vectorRank,
            sources = sources.toList()
        )
    }
}