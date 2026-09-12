# Bid Arena（拍卖间）

仓库地址：<https://github.com/Ayong-ui/bid-arena>（公开，含完整提交历史；`main` 已开启分支保护）

这是一个公开管理的 Bid Arena 拍卖系统仓库。当前包含可运行的领域核心、Vue Mock 前端、数据库迁移草案和完整的后端设计契约；真实 HTTP、MySQL Repository、鉴权和 WebSocket 正按文档逐步实现。

实现路线、当前进度与未完成边界见 [docs/STATUS.md](docs/STATUS.md)，文档权威边界见 [docs/DOCS.md](docs/DOCS.md)，技术选型与被否决方案见 [DECISIONS.md](DECISIONS.md)。

## 运行核心测试

```powershell
mvn -q test-compile
java -ea -cp "target/classes;target/test-classes" com.bidarena.AuctionEngineTest
```

## 开发环境

已提供最小开发环境骨架：Solon 3.x 健康检查接口、Vue 3 + TypeScript + Vite 前端，以及 MySQL 8 Docker Compose。

```powershell
Copy-Item .env.example .env
docker compose up -d mysql
mvn -q test-compile
java -ea -cp "target/classes;target/test-classes" com.bidarena.AuctionEngineTest
cd frontend
npm install
npm run dev
```

- 后端当前可通过 Maven 编译；完整 HTTP 业务接口、MySQL Repository、鉴权和 WebSocket 将在后续阶段接入。
- 前端开发地址：`http://localhost:5173`
- 健康检查：`GET http://localhost:8080/api/v1/health`

## 设计与决策

- 业务全景（角色、主链路、四条不变式）、分层与一致性方案见 [DESIGN.md](DESIGN.md)。
- 技术选型与取舍（背景、候选、代价、验证结果）见 [DECISIONS.md](DECISIONS.md)。
- HTTP 契约见 [docs/openapi.yaml](docs/openapi.yaml)。
- WebSocket 事件与 `seq` 恢复见 [docs/REALTIME_AND_COMMAND_FLOW.md](docs/REALTIME_AND_COMMAND_FLOW.md)。
- 领域边界与不变量见 [docs/DOMAIN_DESIGN.md](docs/DOMAIN_DESIGN.md)。
- 资金冻结、锁顺序与幂等见 [docs/FUNDING_AND_CONCURRENCY.md](docs/FUNDING_AND_CONCURRENCY.md)。
- 页面与交互原型见 [docs/PRODUCT_PROTOTYPE.md](docs/PRODUCT_PROTOTYPE.md)。
- 进度看板见 [docs/STATUS.md](docs/STATUS.md)，文档地图与权威边界见 [docs/DOCS.md](docs/DOCS.md)。
- 提交与交付规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，验收追溯见 [docs/TRACEABILITY.md](docs/TRACEABILITY.md)。

文档明确区分目标架构与当前实现状态；不要把 Mock 前端或内存领域引擎当作生产资金系统。

## 公开仓库约定

原始评测 PDF、`.env`、依赖目录、构建产物和本地运行数据不会提交。需求原文和业务分析以 Markdown 形式保留，便于审阅和版本追踪。提交前执行 `git status --short`，确认没有 Token、密码或个人配置。

## 前端 MVP（当前可运行）

前端 MVP 位于 `frontend/`，不依赖后端，使用 Vue 3 + TypeScript + Pinia 和本地 Mock 数据验证核心页面流程。

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173/>。页面右上角可切换演示身份：

- `林默`：竞拍者，可加入拍卖、出价、查看钱包和流水。
- `周航`：另一位竞拍者，作为已有竞价数据展示。
- `管理员`：可创建、开始和取消本地拍卖。

建议验证路径：管理员创建并开始草稿拍卖 → 切换为林默 → 加入并出价 → 查看钱包冻结积分 → 在拍卖详情中等待倒计时结束 → 查看最终结果。MVP 的数据只保存在当前页面内，刷新后会恢复初始演示数据；真实 HTTP、MySQL 和 WebSocket 接入按 `DESIGN.md` 与 `docs/openapi.yaml` 后续替换 Mock 层（见 `docs/STATUS.md` 的 P4）。
