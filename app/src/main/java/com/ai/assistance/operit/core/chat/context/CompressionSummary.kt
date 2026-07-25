package com.ai.assistance.operit.core.chat.context

/**
 * 结构化压缩摘要，移植自 DeepSeek-Reasonix 的 7 章节摘要模板。
 *
 * Reasonix 将压缩区域内的对话交给 LLM 生成结构化摘要，
 * 要求按 7 个固定章节组织，确保压缩后仍然保留决策链路。
 */
data class CompressionSummary(
    /** 稳定事实（项目背景、约定、用户偏好等） */
    val standingFacts: String = "",
    /** 当前目标 */
    val goal: String = "",
    /** 已做的决策（架构决定、API选择、命名规则等） */
    val decisions: String = "",
    /** 涉及的文件和代码片段引用 */
    val filesAndCode: String = "",
    /** 执行的命令及其结果 */
    val commands: String = "",
    /** 遇到的错误和修复方式 */
    val errorsAndFixes: String = "",
    /** 待办事项和下一步计划 */
    val pendingAndNextStep: String = ""
) {
    /** 序列化为 <compaction-summary> 包裹的摘要文本 */
    fun toCompactionText(): String = buildString {
        appendLine("<compaction-summary>")
        appendSection("Standing facts", standingFacts)
        appendSection("Goal", goal)
        appendSection("Decisions", decisions)
        appendSection("Files & code", filesAndCode)
        appendSection("Commands", commands)
        appendSection("Errors & fixes", errorsAndFixes)
        appendSection("Pending & next step", pendingAndNextStep)
        appendLine("</compaction-summary>")
    }

    /** 是否为空（没有任何实质内容） */
    fun isEmpty(): Boolean = allFieldsEmpty()

    private fun allFieldsEmpty(): Boolean =
        standingFacts.isBlank() && goal.isBlank() && decisions.isBlank() &&
        filesAndCode.isBlank() && commands.isBlank() && errorsAndFixes.isBlank() &&
        pendingAndNextStep.isBlank()

    private fun StringBuilder.appendSection(title: String, content: String) {
        if (content.isNotBlank()) {
            appendLine("  <$title>")
            appendLine("    ${content.trim()}")
            appendLine("  </$title>")
        }
    }

    companion object {
        /** 用于请求 LLM 生成摘要的系统提示模板 */
        val SUMMARY_SYSTEM_PROMPT = """
You are a conversation summarizer for an AI coding agent.
Compress the following conversation into a structured summary organized by these sections:

1. Standing facts — stable context, project background, user preferences, conventions
2. Goal — what the user is currently trying to accomplish
3. Decisions — architecture choices, API selections, naming conventions already decided
4. Files & code — files that were read, modified, or created, with key code fragments
5. Commands — shell commands executed and their important results
6. Errors & fixes — errors encountered, their root causes and how they were resolved
7. Pending & next step — unfinished tasks and what should be done next

Rules:
- Keep facts precise and actionable
- Omit transient observations and unconfirmed speculation
- Never fabricate information not present in the conversation
- If a section has nothing to report, omit it entirely
- Target: compress to at most 1/4 of the original length
""".trimIndent()

        /** 从 LLM 的原始响应文本解析压缩摘要 */
        fun fromSummaryText(text: String): CompressionSummary {
            val sections = extractSections(text)
            return CompressionSummary(
                standingFacts = sections["Standing facts"].orEmpty(),
                goal = sections["Goal"].orEmpty(),
                decisions = sections["Decisions"].orEmpty(),
                filesAndCode = sections["Files & code"].orEmpty(),
                commands = sections["Commands"].orEmpty(),
                errorsAndFixes = sections["Errors & fixes"].orEmpty(),
                pendingAndNextStep = sections["Pending & next step"].orEmpty()
            )
        }

        private fun extractSections(text: String): Map<String, String> {
            val sections = mutableMapOf<String, String>()
            val regex = Regex("<([^>]+)>([\\s\\S]*?)</\\1>")
            regex.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                val content = match.groupValues[2].trim()
                sections[name] = content
            }
            if (sections.isEmpty()) {
                // 回退：LLM 可能没有使用 XML 标签，尝试按数字标题解析
                sections["Standing facts"] = text
            }
            return sections
        }
    }
}