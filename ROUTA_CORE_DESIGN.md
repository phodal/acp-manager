# Routa-Core: Multi-Agent Coordination Module

## 概述

基于 [Issue #21](https://github.com/phodal/agent-dispatcher/issues/21) 中对 Intent by Augment 多 Agent 架构的分析，我们实现了一个纯 Kotlin 的多 Agent 协调核心模块 `routa-core`。

该模块**不包含 UI**，**不依赖 IntelliJ Platform**，方便跨平台实现和集成到不同的执行环境（IDE 插件、CLI 工具、服务器）。

## 命名映射

| Intent by Augment | Routa-Core | 职责 |
|-------------------|-----------|------|
| Coordinator | **Routa** | 规划任务，委托工作，永不直接编辑文件 |
| Implementor | **Crafter** | 执行任务，编写代码，避免冲突 |
| Verifier | **Gate** | 验证完成度，检查质量，输出报告 |

## 架构设计

### 核心组件

```
routa-core/
├── model/              # 数据模型
│   ├── Agent           # Agent + AgentRole + AgentStatus
│   ├── Message         # 对话消息
│   ├── Task            # 任务定义 + TaskStatus
│   └── DelegationConfig # 委托配置
├── store/              # 存储层（接口 + 内存实现）
│   ├── AgentStore
│   ├── ConversationStore
│   └── TaskStore
├── event/              # 事件驱动通信
│   ├── AgentEvent      # 事件类型
│   └── EventBus        # SharedFlow-based 发布订阅
├── tool/               # Agent 协调工具
│   └── AgentTools      # 7 个协调工具
├── role/               # Agent 角色定义
│   └── RouteDefinitions # 角色系统提示词
└── coordinator/        # 协调器
    ├── TaskParser      # 解析 @@@task 块
    └── RoutaCoordinator # 状态机
```

### 工作流状态机

```
User Request
  ↓
PLANNING (Routa 规划 @@@task 块)
  ↓
READY (计划准备就绪)
  ↓
EXECUTING (Crafter 并行执行)
  ↓
WAVE_COMPLETE (当前波次完成)
  ↓
VERIFYING (Gate 验证)
  ↓
NEEDS_FIX (未通过) → 返回 EXECUTING
  或
COMPLETED (全部完成)
```

### 7 个 Agent 协调工具

| 工具 | 描述 | 使用场景 |
|------|------|---------|
| `list_agents()` | 列出所有 agents | Crafter 发现兄弟 agents，Gate 找到实现者 |
| `read_agent_conversation()` | 读取 agent 对话历史 | 冲突检测，验证工作 |
| `create_agent()` | 创建新 agent | Routa 创建 Crafter/Gate |
| `delegate()` | 委托任务给 agent | Routa 分配任务 |
| `message_agent()` | 发送消息给 agent | 跨 agent 通信 |
| `wait_for_agent()` | 等待 agent 完成 | 同步等待 |
| `report_to_parent()` | 向父 agent 报告完成 | Crafter/Gate 完成时**必须调用** |

## 集成方案

### 1. MCP 工具暴露

```kotlin
// 在 McpToolManager 中注册 Routa 工具
class McpToolManager(private val routaTools: AgentTools) {
    fun registerRoutaTools() {
        server.addTool(Tool(
            name = "list_agents",
            description = "List all agents in the workspace",
            inputSchema = JsonObject(...),
            handler = { args -> 
                routaTools.listAgents(args["workspaceId"].asString)
            }
        ))
        // ... 其他 6 个工具
    }
}
```

### 2. ACP 执行后端

```kotlin
// 使用现有的 IdeaAgentExecutor 驱动 Crafter/Gate
val executor = IdeaAgentExecutor(acpSessionManager, project)

// Crafter 执行任务
val context = coordinator.buildAgentContext(crafterId)
executor.execute(
    agentKey = "codex", // ACP agent
    prompt = context,   // 包含任务定义和角色提示词
    taskId = taskId
)
```

### 3. Koog 工具适配

```kotlin
// 将 AgentTools 包装为 Koog 的 Tool 类型
class ListAgentsTool(private val routaTools: AgentTools) : SimpleTool<ListAgentsArgs>(
    argsSerializer = ListAgentsArgs.serializer(),
    name = "list_agents",
    description = "List all agents in the workspace"
) {
    override suspend fun execute(args: ListAgentsArgs): String {
        val result = routaTools.listAgents(args.workspaceId)
        return result.data
    }
}

// 注册到 Koog agent
val toolRegistry = ToolRegistry {
    tool(ListAgentsTool(routa.tools))
    tool(CreateAgentTool(routa.tools))
    // ... 其他工具
}

val agent = AIAgent(
    promptExecutor = simpleOpenAIExecutor(apiKey),
    systemPrompt = RouteDefinitions.ROUTA.systemPrompt,
    toolRegistry = toolRegistry
)
```

### 4. 文件持久化

```kotlin
// 实现基于文件的存储（类似 Intent 的 .workspace/agents/）
class FileBasedAgentStore(private val workspacePath: String) : AgentStore {
    private val agentsDir = File(workspacePath, ".routa/agents")
    
    override suspend fun save(agent: Agent) {
        val file = File(agentsDir, "${agent.id}.json")
        file.writeText(Json.encodeToString(agent))
    }
    
    override suspend fun get(agentId: String): Agent? {
        val file = File(agentsDir, "$agentId.json")
        if (!file.exists()) return null
        return Json.decodeFromString(file.readText())
    }
    // ...
}
```

## @@@task 块格式

Routa 规划时输出的任务格式：

```
@@@task
# Task Title

## Objective
Clear statement of what needs to be done

## Scope
- Specific files/components to modify
- What's in scope and out of scope

## Definition of Done
- Acceptance criteria 1
- Acceptance criteria 2

## Verification
- Commands to run (e.g., ./gradlew test)
- What to report_to_parent
@@@
```

## 角色行为规则

### Routa (Coordinator)

- **NEVER edit code** — 没有文件编辑工具
- **NEVER use checkboxes** — 只用 `@@@task` 块
- **Spec first** — 先创建/更新 spec，再委托
- **Wait for approval** — 展示计划后停下，等待用户批准
- **Waves + verification** — 委托一波 → 结束回合 → 等待完成 → 委托 Gate

### Crafter (Implementor)

- **No scope creep** — 只做任务要求的事
- **No refactors** — 需要重构时请求 Routa 创建独立任务
- **Coordinate** — 使用 `list_agents`/`read_agent_conversation` 避免冲突
- **Notes only** — 不创建 markdown 文件协作
- **Don't delegate** — 阻塞时通知 Routa

### Gate (Verifier)

- **Acceptance Criteria is the checklist** — 只验证 AC
- **No evidence, no verification** — 没有证据不能验证
- **No partial approvals** — 所有 AC 都 ✅ 才能 APPROVED
- **If you can't run tests, say so** — 明确说明限制
- **Don't expand scope** — 可以建议后续工作，但不阻塞 approval

## 测试覆盖

```bash
./gradlew :routa-core:test
```

6 个测试全部通过：
- ✅ TaskParser 解析 `@@@task` 块
- ✅ 初始化创建 Routa agent
- ✅ 注册任务到 store
- ✅ 执行下一波任务（创建 Crafter 并委托）
- ✅ Crafter 报告完成，任务转为 REVIEW_REQUIRED
- ✅ Agent 工具（list、read conversation）

## 使用示例

```kotlin
// 1. 创建系统
val routa = RoutaFactory.createInMemory()

// 2. 初始化协调会话
val routaAgentId = routa.coordinator.initialize("my-workspace")

// 3. 让 Routa agent 处理用户请求（通过 ACP/Koog）
// ... Routa 输出包含 @@@task 块的计划 ...

// 4. 注册任务
val planOutput = """
    @@@task
    # Implement Auth Module
    ## Objective
    Add JWT authentication
    ## Definition of Done
    - JWT tokens issued on login
    - Protected endpoints require tokens
    ## Verification
    - ./gradlew test --tests AuthServiceTest
    @@@
"""
routa.coordinator.registerTasks(planOutput)

// 5. 执行任务波次
val delegations = routa.coordinator.executeNextWave()
// 返回 [(crafterId, taskId), ...]

// 6. 驱动 Crafter agents（通过 ACP/Koog）
for ((crafterId, taskId) in delegations) {
    val context = routa.coordinator.buildAgentContext(crafterId)
    // 将 context 发送给 Crafter agent...
    // Crafter 完成后调用 report_to_parent()
}

// 7. 启动验证
val gateAgentId = routa.coordinator.startVerification()
// 驱动 Gate agent 验证任务...
```

## 待办事项

- [ ] 实现 FileBasedAgentStore / ConversationStore / TaskStore
- [ ] 在 McpToolManager 中注册 7 个工具
- [ ] 创建 UI 面板展示协调状态（RoutaPanel）
- [ ] 集成 Koog 作为可选的 agent 执行后端
- [ ] 实现 wave 间的依赖解析（DAG）
- [ ] 添加任务取消/重试机制
- [ ] 实现 Gate 的自动 fix request 循环

## 参考

- [Issue #21: Implement Multi-Agent Coordination Tools](https://github.com/phodal/agent-dispatcher/issues/21)
- [Intent by Augment 架构分析](https://github.com/phodal/agent-dispatcher/issues/21#issuecomment-3877813021)
- [Koog 官方文档](https://docs.koog.ai/)
