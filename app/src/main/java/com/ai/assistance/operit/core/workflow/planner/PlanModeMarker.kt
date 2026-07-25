package com.ai.assistance.operit.core.workflow.planner

/**
 * Plan Mode 指令标记 —— 放在用户轮次中而非系统提示。
 *
 * 这是 Reasonix 缓存优化的关键设计：
 * 切换规划模式时只改变用户轮次内容，不破坏系统提示前缀的字节稳定性，
 * 这样 DeepSeek 的 Prefix Cache 继续保持热状态。
 */
object PlanModeMarker {

    /** 进入规划模式时的指令块 */
    const val ENTER_PLAN_MODE = """
[Plan mode — planning workflow]

You are now in PLAN MODE. Your task is to ANALYZE and PLAN only.

Guidelines:
1. **Gather context first** — Read relevant files, search the codebase, ask clarifying questions using `ask`
2. **Do not implement** — No file writes, no shell commands that modify state, no code generation
3. **Maintain planning state** — Use `todo_write` to track identified tasks
4. **Delegate research** — Use sub-agents for focused investigation when needed
5. **Output a clear plan** — Before exiting plan mode, produce a structured execution plan

Output format for the final plan:
```
## Execution Plan
### Phase 1: <title>
- [ ] Step 1: ...
- [ ] Step 2: ...

### Phase 2: <title>
- [ ] Step 1: ...
```

### Phase N:
- [ ] Step N: ...
```
"""

    /** 退出规划模式时的指令块 */
    const val EXIT_PLAN_MODE = """
[Plan mode — transitioning to execution]

The plan has been reviewed and approved. Now transition to EXECUTION MODE.

Guidelines:
1. **Follow the plan** — Execute steps in order
2. **Mark progress** — Use `complete_step` after finishing each step
3. **Update the plan** — If you discover issues, update `todo_write` with new tasks
4. **Report deviations** — If the plan needs to change, explain why

Begin execution now.
"""

    /** 规划模式下不安全工具的阻止消息 */
    fun blockMessage(toolName: String): String = """
[Blocked] The tool "$toolName" is not available in Plan Mode.
Switch to execution mode before making changes.
You can still use: read_file, grep, glob, web_fetch, web_search, ask, todo_write
""".trimIndent()
}