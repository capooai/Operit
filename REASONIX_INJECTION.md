# Reasonix 算法注入说明

本分支将 DeepSeek-Reasonix 的 5 个核心算法模块注入到 Operit 中。

## 注入模块清单

| # | 模块 | 文件 | 说明 |
|---|------|------|------|
| 1 | 多级上下文压缩 | `core/chat/context/` (3 文件) + `ContextCompressionHook` | 四级阈值压缩(50%/60%/80%/90%) + Snip/Prune + 结构化摘要 |
| 2 | DeepSeek Cache 优化 | `ReasonixDeepseekProvider.kt` | Prefix Cache 命中优化 + Schema Canonicalize |
| 3 | 规划模式 + 双模型协作 | `core/workflow/planner/` (3 文件) | Plan Mode + Executor/Planner 双模型 |
| 4 | BM25 关键词搜索 | `data/memory/searcher/` (2 文件) | BM25 算法 + 混合搜索器(HNSW+BM25) |
| 5 | 会话检查点与回滚 | `data/db/checkpoint/CheckpointManager.kt` | Checkpoint/Rewind/Branch 系统 |

## 集成方式

所有模块以 **非侵入方式** 接入 Operit 现有架构：

- **Hook 集成** (`PromptHookRegistry`)：ContextCompressionHook 注册为 `PromptFinalizeHook`
- **Provider 扩展**：ReasonixDeepseekProvider 继承自 DeepseekProvider
- **算法层**：BM25、ContextManager 为独立模块，无 Operit 依赖
- **工具系统**：Plan Mode 的工具安全校验由 PlanCoordinator 提供

## 文件清单

```
app/src/main/java/com/ai/assistance/operit/
├── api/chat/llmprovider/
│   └── ReasonixDeepseekProvider.kt          # 模块2
├── core/chat/context/
│   ├── ContextManager.kt                     # 模块1（核心算法）
│   ├── CompressionSummary.kt                 # 模块1
│   └── SnipPolicy.kt                         # 模块1
├── core/chat/hooks/
│   └── ContextCompressionHook.kt             # 模块1（Hook 集成点）
├── core/workflow/planner/
│   ├── PlanCoordinator.kt                    # 模块3（核心）
│   └── PlanModeMarker.kt                     # 模块3
├── data/db/checkpoint/
│   └── CheckpointManager.kt                  # 模块5
└── data/memory/searcher/
    ├── Bm25Searcher.kt                       # 模块4（算法）
    └── HybridSearcher.kt                     # 模块4（融合检索）
```

## 后续集成 TODO

以下集成点需要在 ChatViewModel / EnhancedAIService 中完成对接：

1. **ContextCompressionHook 注册**
   ```kotlin
   PromptHookRegistry.registerPromptFinalizeHook(hook)
   ```
   建议在 `OperitApplication.onCreate()` 中注册。

2. **ReasonixDeepseekProvider 启用**
   修改 `AIServiceFactory.kt`，将 DeepSeek 的 Provider 实例替换为 `ReasonixDeepseekProvider`。

3. **Plan Mode UI 集成**
   在聊天输入栏增加 Plan Mode 开关，连接 `PlanCoordinator`。

4. **BM25 搜索结果展示**
   在记忆库搜索页面增加"关键词"标签，展示 HybridSearcher 结果。

5. **Checkpoint 时间线**
   在会话历史页面增加检查点时间线和"回滚到此处"入口。