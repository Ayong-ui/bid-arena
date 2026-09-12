# 文档地图与权威边界

> 本文件是整个仓库唯一的"元文档"：它规定**每类事实由哪份文件定义、冲突时谁说了算、改一处必须同步哪些文件**。
> 所有文档改动与代码开发都以本文件为流程依据。本文件本身不定义业务规则。

## 1. 唯一事实依据

[`全栈评测-拍卖间-原文.md`](../全栈评测-拍卖间-原文.md) 是本题唯一事实依据，**冻结只读**。

- 任何其他文档都不得削减、改写或与之冲突。
- 原文已经写明的规则、数值、验收项，其他文档**只允许引用，不允许复述**。
- 只有原文未规定的实现细节，才由下层文档定义。

## 2. 文档分层与权威范围

每类事实只有一个权威出处。表格中的"权威范围"= 该文件**唯一有权定义**的内容。

### 第 0 层｜事实依据

| 文件 | 权威范围 | 状态 |
|---|---|---|
| `全栈评测-拍卖间-原文.md` | 业务规则、数值常量、验收标准、交付要求、评分标准 | ✅ 冻结 |

### 第 1 层｜契约（机器可校验，接口/事件的唯一出处）

| 文件 | 权威范围 | 状态 |
|---|---|---|
| `docs/openapi.yaml` | HTTP 路径、请求/响应字段、错误码、HTTP 状态码 | ✅ 已修正（见 §6） |
| `docs/REALTIME_AND_COMMAND_FLOW.md` | WebSocket 事件类型、`seq` 语义、快照恢复、命令流顺序 | ✅ 已重写 |
| `db/migration/*.sql` | 数据库表、列、约束、索引 | ✅ V1+V2 已在空库执行验证（后续只追加新版本） |

### 第 2 层｜设计与决策（只写 why / how，不复述规则）

| 文件 | 权威范围 | 状态 |
|---|---|---|
| `DESIGN.md` | 架构边界、出价事务与结算方案、并发与失败恢复（原文必交） | ✅ 保留 |
| `DECISIONS.md` | 技术选型与取舍：背景、候选、选择、代价、验证（原文必交） | ✅ 已写 D-1~D-9（验证结果随实现补全） |
| `docs/DOMAIN_DESIGN.md` | 领域边界、聚合、不变量、两个一致性边界 | ✅ 已重写 |
| `docs/FUNDING_AND_CONCURRENCY.md` | 差额冻结、锁顺序、幂等、结算/取消的失败分支 | ✅ 已重写 |
| `docs/PRODUCT_PROTOTYPE.md` | 页面地图、组件、交互原型 | ✅ 已创建 |

### 第 3 层｜交付物（原文强制）

| 文件 | 权威范围 | 状态 |
|---|---|---|
| `README.md` | 快速启动完整路径、演示账号、**未完成边界** | 🔁 待更新 |
| `AI_USAGE.md` | AI 分工比例、本人设计决定、未采用方案、真实错误（原文必交） | ➕ 待创建 |
| `DEBUG_LOG.md` | 开发期间真实问题的现象/日志/定位/修复/验证（原文必交） | ➕ 待创建 |
| `AGENT_TOOL_SPEC.md` | 评审如何用 Token 让 Coding Agent 查询与出价（原文必交） | ➕ 待创建 |

### 第 4 层｜工程控制（防偏差机制）

| 文件 | 权威范围 | 状态 |
|---|---|---|
| `docs/DOCS.md` | 本文件：文档地图、冲突裁决、改动联动 | ✅ 已创建 |
| `CONTRIBUTING.md` | 提交规范、完成定义（DoD）、GitHub 仓库管理、交付清单 | ✅ 已创建 |
| `docs/STATUS.md` | 唯一进度看板（含四个必交文档状态） | ✅ 已创建 |
| `docs/TRACEABILITY.md` | 验收项 ↔ 代码 ↔ 测试 映射 | ✅ 已创建 |

## 3. 冲突裁决

按以下顺序裁决，**不投票、不"综合理解"**：

1. **原文**：定义"必须有什么"。任何文档不得削减原文要求。
2. **第 1 层契约**：定义原文未规定的实现细节（字段名、类型、错误码、事件结构）。
3. **第 2 层设计**：解释为什么这么做。若与契约冲突，改设计。
4. **第 3 层交付**：面向评审的说明。若与设计冲突，改交付文档。

常见场景：

| 冲突 | 裁决 |
|---|---|
| 原文要求某能力，但 `openapi.yaml` 没写 | **原文胜**，`openapi.yaml` 必须补齐 |
| `openapi.yaml` 与联调说明不一致 | `openapi.yaml` 胜 |
| 事件结构在正文与 `REALTIME_AND_COMMAND_FLOW.md` 不一致 | `REALTIME_AND_COMMAND_FLOW.md` 胜 |
| 某文档写了具体数值，与原文不同 | **原文胜**，删除该文档中的数值复述 |
| 设计文档描述的方案已被新决策替代 | 更新 `DECISIONS.md`，再改设计文档 |

## 4. 禁止复述原则

以下内容**只允许在唯一出处出现**，其他文档一律改为引用链接：

| 内容 | 唯一出处 |
|---|---|
| 数值常量（起拍价、加价、时长、余额、延时窗口与次数） | 原文 |
| 状态机定义 | 原文（契约中只以 enum 体现） |
| HTTP 路径与字段 | `docs/openapi.yaml` |
| WS 事件与 `seq` 语义 | `docs/REALTIME_AND_COMMAND_FLOW.md` |
| 表结构 | `db/migration/*.sql` |

**允许的写法**：`延时规则见原文《拍卖与资金规则》第 6 条`，而不是把"截止前 5 秒延长 10 秒、最多 3 次"再抄一遍。

## 5. 改动联动表

**改动任何一项，必须同步右侧文件；提交前逐项确认。**

| 改动类型 | 必须同步 |
|---|---|
| 原文规则/数值/验收变更 | 不变量测试 → 受影响设计文档的引用 |
| HTTP 接口、字段、错误码 | `openapi.yaml` → 前端生成类型 → 前端 API client |
| WS 事件、`seq`、快照语义 | `REALTIME_AND_COMMAND_FLOW.md` → 后端事件类 → 前端 store |
| 数据库表/列/约束 | 新增 `db/migration/V2+` → 受影响 Repository → 集成测试 |
| 资金冻结/释放/结算语义 | `FUNDING_AND_CONCURRENCY.md` → 不变量测试 → `DECISIONS.md` |
| 领域边界/状态机 | `DOMAIN_DESIGN.md` → `openapi.yaml` enum → 前端类型 → 测试 |
| 架构或技术选型 | `DESIGN.md` 与 `DECISIONS.md` |
| 修复真实 bug | `DEBUG_LOG.md` |
| 引入/放弃方案 | `DECISIONS.md` 与 `AI_USAGE.md` |
| 交付范围、完成度、未完成项 | `README.md` 未完成边界 → `docs/STATUS.md` |
| 任意一次提交 | `docs/STATUS.md`（进度与必交文档状态） |

## 6. 现有文档处理决定

| 现有文件 | 处理 | 状态 | 理由 |
|---|---|---|---|
| `全栈评测-拍卖间-业务理解与产品原型分析.md` | 重写为 `docs/PRODUCT_PROTOTYPE.md` | ✅ 已删除原文 | 大量复述原文规则，仅页面/组件原型部分不可替代 |
| `FRONTEND_BACKEND_INTEGRATION.md` | 拆解后删除 | ✅ 已删除 | 接口→`openapi.yaml`，事件→`REALTIME`，步骤→`CONTRIBUTING`/`README` |
| `REUSE_MAP.md` | 删除 | ✅ 已删除（已并入 `DECISIONS.md` 的“未采用方案汇总”） | 所述“跑腿项目”不存在 |
| `docs/DOMAIN_DESIGN.md` | 删去数值与状态机复述 | ✅ 已重写 | 保留边界、聚合、不变量 |
| `docs/FUNDING_AND_CONCURRENCY.md` | 聚焦差额冻结/锁顺序/幂等 | ✅ 已重写 | 删除对原文规则的转述 |
| `docs/openapi.yaml` | 补缺口 | ✅ 已修正 | 见下方清单 |

### openapi.yaml 修正记录

- ✅ 补 `GET /agent/auctions/{auctionId}/result`（Agent 可读结果）。
- ✅ 补 `POST /admin/agent-tokens/{tokenId}/revoke`（吊销生效）。
- ✅ ledger 归属：保留独立 `GET /wallets/me/ledger`；`/wallets/me` 只返回余额，冲突随联调文档删除而消除。
- ✅ Agent 路径补充独立 server（`:8090`）；`CreateAgentTokenRequest` 补 `agentUserId`；新增 `AuctionResult` schema。
- ⏳ `SETTLING` 在前端类型中的体现：待 P4 由 openapi 生成前端类型时解决。

## 7. 文档更新时机

文档不是"有空再补"，而是**完成定义（DoD）的一部分**：

```
代码 + 测试通过 + openapi.yaml 已同步 + 受影响文档已更新
+ 修 bug 则记 DEBUG_LOG + 用 AI/换方案则记 AI_USAGE/DECISIONS
+ README 未完成边界已更新 + docs/STATUS.md 已更新
+ 一个可独立编译、可独立验证的提交
```

具体提交规范与 DoD 细则见 `CONTRIBUTING.md`（待创建）。

## 8. 命名与放置约定

- 交付物（原文点名）：放仓库根目录。
- 契约与设计子文档：放 `docs/`。
- 数据库迁移：放 `db/migration/`，文件名 `V<序号>__<描述>.sql`。
- 工程控制文档：`CONTRIBUTING.md` 在根目录，其余在 `docs/`。
- 不提交：`.env`、Token、密码、`frontend/dist`、`node_modules`、PDF、构建产物。
