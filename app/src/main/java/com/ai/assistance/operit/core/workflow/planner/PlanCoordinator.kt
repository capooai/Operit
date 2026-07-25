package com.ai.assistance.operit.core.workflow.planner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 规划协调器 —— 双模型协作的核心。
 *
 * 可选运行两个模型：
 * - **规划器（Planner）**：负责分析需求、制定计划（通常在 Plan Mode 下运行）
 * - **执行器（Executor）**：负责实际编码和修改（默认模型）
 *
 * 两个模型运行在各自独立的缓存稳定会话中。
 *
 * ### 工作流
 * ```
 * 用户输入 → Planner 分析 → 输出结构化计划
 *                          → 用户审批 → Executor 分步执行
 * ```
 */
class PlanCoordinator(private val appContext: Context) {

    companion object {
        private const val TAG = "PlanCoordinator"
    }

    /** 规划模式是否激活 */
    @Volatile
    var isPlanModeActive: Boolean = false
        private set

    /** 当前计划文本 */
    @Volatile
    var currentPlan: String = ""
        private set

    /** 当前执行步骤列表 */
    private val executionSteps = mutableListOf<ExecutionStep>()

    // ==================== 核心 API ====================

    /**
     * 进入规划模式。
     *
     * @param userRequest 用户的原始请求
     * @param chatHistory 当前对话历史
     * @param plannerModel 规划模型的 ID（如 "deepseek-reasoner"），null 则使用默认模型
     * @return 规划阶段的流式输出事件
     */
    suspend fun enterPlanMode(
        userRequest: String,
        chatHistory: List<com.ai.assistance.operit.core.chat.hooks.PromptTurn>,
        plannerModel: String? = null
    ): Flow<PlanEvent> = flow {
        isPlanModeActive = true
        currentPlan = ""
        executionSteps.clear()

        emit(PlanEvent.PlanningStarted)

        try {
            // 构建规划 prompt
            val planPrompt = buildString {
                appendLine(userRequest)
                appendLine()
                appendLine(PlanModeMarker.ENTER_PLAN_MODE)
            }

            // TODO: 通过 EnhancedAIService 发送请求
            // 这里标记集成点，实际调用在 ChatViewModel 中完成
            emit(PlanEvent.PlanReady(""))

        } catch (e: Exception) {
            Log.e(TAG, "Planning failed", e)
            emit(PlanEvent.PlanError(e.message ?: "Unknown error"))
            isPlanModeActive = false
        }
    }

    /**
     * 提交计划并进入执行阶段。
     *
     * @param plan 用户确认后的最终计划文本
     * @param chatHistory 当前对话历史
     * @return 执行阶段的流式输出事件
     */
    suspend fun executePlan(
        plan: String,
        chatHistory: List<com.ai.assistance.operit.core.chat.hooks.PromptTurn>
    ): Flow<PlanEvent> = flow {
        currentPlan = plan
        isPlanModeActive = false

        emit(PlanEvent.ExecutionStarted)

        try {
            val execPrompt = buildString {
                appendLine(PlanModeMarker.EXIT_PLAN_MODE)
                appendLine()
                appendLine("## Approved Plan")
                appendLine(plan)
            }

            // TODO: 通过 EnhancedAIService 发送请求
            // 实际调用在 ChatViewModel 中完成
            emit(PlanEvent.ExecutionDone)

        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            emit(PlanEvent.PlanError(e.message ?: "Unknown error"))
        }
    }

    /**
     * 取消当前规划或执行。
     */
    fun cancel() {
        isPlanModeActive = false
        Log.i(TAG, "Plan mode cancelled")
    }

    /**
     * 检查工具在当前模式下是否安全。
     *
     * @return null 表示安全，非 null 为阻止消息
     */
    fun checkToolSafety(toolName: String): String? {
        if (!isPlanModeActive) return null // 不在规划模式，不限制

        // 不安全的工具列表（在 Plan Mode 下禁止使用）
        return if (toolName in UNSAFE_PLAN_TOOLS) {
            PlanModeMarker.blockMessage(toolName)
        } else {
            null
        }
    }

    // ==================== 步骤跟踪 ====================

    /**
     * 添加执行步骤（由 todo_write 工具调用）。
     */
    fun addStep(step: ExecutionStep) {
        synchronized(executionSteps) {
            executionSteps.add(step)
        }
    }

    /**
     * 将步骤标记为完成（由 complete_step 工具调用）。
     */
    fun completeStep(stepId: String): Boolean {
        synchronized(executionSteps) {
            val step = executionSteps.find { it.id == stepId } ?: return false
            executionSteps.remove(step)
            executionSteps.add(step.copy(status = StepStatus.COMPLETED))
            return true
        }
    }

    /**
     * 获取执行进度。
     */
    fun getProgress(): PlanProgress {
        synchronized(executionSteps) {
            val total = executionSteps.size
            val completed = executionSteps.count { it.status == StepStatus.COMPLETED }
            return PlanProgress(
                total = total,
                completed = completed,
                remaining = total - completed,
                steps = executionSteps.toList()
            )
        }
    }

    // ==================== 工具定义 ====================

    /** 规划模式下不安全的工具 */
    val UNSAFE_PLAN_TOOLS = setOf(
        "write_file", "edit_file", "multi_edit", "move_file",
        "delete_file", "create_file", "mkdir",
        "bash", "shell", "execute_command",
        "npm_install", "git_commit", "git_push",
        "complete_step"
    )
}

// ==================== 事件类型 ====================

/** 规划/执行阶段的事件 */
sealed class PlanEvent {
    /** 规划阶段开始 */
    data object PlanningStarted : PlanEvent()

    /** 规划完成，等待审批 */
    data class PlanReady(val planText: String) : PlanEvent()

    /** 用户审批通过 */
    data object PlanApproved : PlanEvent()

    /** 用户拒绝计划 */
    data object PlanRejected : PlanEvent()

    /** 执行阶段开始 */
    data object ExecutionStarted : PlanEvent()

    /** 执行进度 */
    data class ExecutionProgress(val stepId: String, val status: String) : PlanEvent()

    /** 执行完成 */
    data object ExecutionDone : PlanEvent()

    /** 错误 */
    data class PlanError(val message: String) : PlanEvent()
}

// ==================== 数据结构 ====================

/** 执行步骤 */
data class ExecutionStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val status: StepStatus = StepStatus.PENDING
)

/** 步骤状态 */
enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED
}

/** 执行进度 */
data class PlanProgress(
    val total: Int,
    val completed: Int,
    val remaining: Int,
    val steps: List<ExecutionStep>
)