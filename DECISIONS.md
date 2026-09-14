# 关键决策记录（DECISIONS）

> 规则与数值以 [`全栈评测-拍卖间-原文.md`](全栈评测-拍卖间-原文.md) 为准，本文不复述数值。
> 每条决策包含：**背景 / 候选方案 / 最终选择 / 代价 / 验证结果**。
> 验证标记：✅ 已实测（附证据）　⏳ 待实现阶段验证　❌ 已证伪（记录在案）
> 相关文档：[`DESIGN.md`](DESIGN.md)　[`docs/STATUS.md`](docs/STATUS.md)　[`docs/TRACEABILITY.md`](docs/TRACEABILITY.md)

---

## D-1　数据访问方式

**背景**：原文要求"数据访问方式不限，但必须能解释事务、锁与 SQL 边界"。出价与结算的正确性依赖**行锁顺序、条件更新与唯一约束**，因此数据访问层必须让这些语义显式可见。

**候选方案**

| 方案 | 说明 |
|---|---|
| A. MyBatis / MyBatis-Plus | SQL 可控，但需额外集成 Solon，且 Plus 的封装会隐藏部分 SQL |
| B. JPA / Hibernate | 抽象层次高，锁与更新顺序不易直接掌控 |
| C. Solon `solon-data` + HikariCP + 手写 SQL | 连接与事务由框架管，SQL 与锁语义完全自己写 |

**最终选择**：**C**。

**代价**：手写 SQL 与结果集映射需自行维护，没有自动生成与校验；换来的是事务边界、锁顺序、更新条件在代码里逐条可见，可直接对应到 `DESIGN.md` §3 的事务步骤。

**验证结果**：⏳ 待 P1 —— 以"并发出价集成测试 + 压测后校验 INV-1~4"为准。依赖已可解析（`mvn -DskipTests compile` 通过）。

---

## D-2　数据库迁移工具与执行位置

**背景**：原仓库用 Compose 的 `./db/migration:/docker-entrypoint-initdb.d` 挂载执行 `V1__auction_schema.sql`。该方式有两个硬伤：

1. `docker-entrypoint-initdb.d` **只在数据卷为空时首次启动执行**，后续新增 `V2` 不会应用——"看起来做了迁移，其实没做"。
2. 远程 Docker 下，Compose 的**相对路径 bind mount 由守护进程所在主机解析**，Windows 的 `./db/migration` 在 VM 上并不存在，静默失效。

**候选方案**：A. 保留 initdb 挂载；B. 独立迁移容器或人工执行；C. **应用内 Flyway**（随服务启动自动迁移）。

**最终选择**：**C**，同时**删除 Compose 中的 migration 挂载**。

**代价**：应用启动与数据库可用性强耦合，需要启动等待/重试；迁移与代码同版本发布，回滚需自行安排（本项目用版本化 SQL，不依赖自动回滚）。

**验证结果**：
- ✅ 已实测：`docker compose config` 通过，输出中不再包含 migration 挂载，MySQL 发布端口为 `3307`。
- ✅ 已实测（P1）：`V1` + `V2` 在**全新空库**上由应用启动自动执行成功，`flyway_schema_history` 两条记录 `success = 1`；中文种子数据往返校验通过（`title = '演示拍品 · 复古机械键盘'` 返回 1）。
- ✅ 已实测（故障路径）：`V2` 首次因语法错误失败时，Flyway 留下 `success = 0` 记录且 schema 处于半应用状态。处理方式为**整库重建**而非 `flyway repair` 或手工补列，理由与过程见 `DEBUG_LOG.md` DBG-1。

**执行细节**（工程约定，不改变本条决策）：
- 脚本保留在仓库根目录 `db/migration/`（评审直接可见），通过 `pom.xml` 的 `<resources>` + `targetPath` 映射为 `classpath:db/migration`。不能用 `filesystem:` 相对路径，打成 jar 或进容器后该路径不存在。
- Flyway 脚本编码显式固定为 UTF-8，不依赖平台默认字符集（教训见 `DEBUG_LOG.md` DBG-1）。
- 陷阱：Maven 增量资源拷贝可能不刷新 `target/classes` 下的脚本副本（见 `DEBUG_LOG.md` DBG-2），因此一键测试命令统一使用 `mvn clean verify`。

---

## D-3　认证、授权与密码哈希

**背景**：系统内同时存在两类完全不同的调用者——真人用户/管理员（会话式）与竞拍 Agent（受限机器凭据）。原文要求 Agent"使用独立的认证凭据与最小权限边界"。

**候选方案**

| 方案 | 说明 |
|---|---|
| A. 服务端 Session + Cookie | 便于即时登出，但前后端分离 + WebSocket 场景需额外处理 |
| B. **JWT（jjwt）+ BCrypt** 用户令牌，Agent Token 独立 | 无状态，两类凭据天然分离 |
| C. 自研签名令牌 | 无必要地重复造轮子，安全性难自证 |

**最终选择**：**B**。用户/管理员用短期 JWT；Agent Token 是**另一种凭据**，数据库只存 SHA-256 摘要，明文仅在创建响应返回一次，并绑定用户、拍卖范围、权限、过期与吊销状态。

**代价**：无状态 JWT 无法即时失效（靠短 TTL 缓解，管理员强制登出需额外黑名单，本项目不做）；JWT 密钥必须通过环境变量提供，不可入库入仓。

**验证结果**：⏳ 待 P2（登录与 RBAC）、P5（Agent Token 的范围/权限/过期/吊销/限流）。

---

## D-4　并发正确性的归属：数据库，而不是应用层锁

**背景**：现有 `AuctionEngine` 用 `synchronized` + 内存 `Map` 保证"领先者唯一"和"幂等"。这只在**单进程**内有效，与原文"不能只依赖应用层先查询再判断"以及"两个实例同时触发结算"直接冲突。同时它把总余额硬编码为 1000 积分，与真实钱包脱节。

**候选方案**

| 方案 | 说明 | 否决原因 |
|---|---|---|
| A. 应用层 `synchronized` / `ReentrantLock` | 改动最小 | 单进程有效，多实例失效 |
| B. Redis 分布式锁 | 可跨进程 | 引入第二个事实来源，锁失效与 MySQL 不一致时无法自证；原文明确 Redis 非必选 |
| C. **MySQL 唯一约束 + 行锁 + 条件更新** | 正确性由数据库保证 | —— |

**最终选择**：**C**。原有的内存 `AuctionEngine` 已**删除**：它用 `synchronized` + 内存 `Map` 保证唯一赢家与幂等，只能证明“单进程内正确”，而这正是需要被否定的写法；留着它比没有更差，因为它看起来像是并发已验证。规则并入事务型应用服务 `BidService`，在 MySQL 事务内执行；幂等由 `(auction_id, user_id, request_id)` 唯一键兜底，重复结算由拍卖行锁 + `settlements` 主键兜底。

**代价**：并发路径强依赖数据库，关键测试**必须使用真实 MySQL**（原文也如此要求），测试基建成本上升；需要处理死锁重试。

**验证结果**：

- **INV-1~4 已验证**：`BidConcurrencyTest` 5 + `BidServiceTest` 11 + `SettlementConcurrencyTest` 5 + `SettlementServiceTest` 11，共 32 个用例全部跑在真实 MySQL 8.4 上，每次断言后直接用 SQL 校验不变量（`support/Invariants`）。
- **锁顺序固定为** `auctions` 行 → `auction_participants` 行（按 `user_id` 升序）→ `wallets` 行（按 `user_id` 升序）。一次出价可能同时改两个用户（新、旧领先者）的钱包，若两个事务按相反顺序加锁就会成环死锁；统一排序后这类死锁从“偶发”变成“不存在”。残余死锁（1213/1205）由 `Db.tx` 有限重试。
- **变异测试反向确认测试真的有效**（仅凭“全绿”不算证据）：拿掉 `lockAuction` 的 `FOR UPDATE` 后，20 次同额出价中有 14 次既不是成功也不是规则拒绝，而是内部错误——结果不再能用拍卖规则解释，两个并发用例当场失败；拿掉幂等重放短路后，出价记录数与成功幂等记录数不再相等。还原后复跑全绿。
- **一层保不住，所以做了两层**：行锁负责“串行化读”，`applyBid` 的 `AND seq = ?` 条件更新负责“串行化写”。变异测试表明：拿掉行锁后条件更新确实拦住了脏写，但拒绝的原因从“业务规则”退化成“校验失败”——赢家仍然唯一，可解释性却丢了。这正好说明两者职责不同，不能相互替代。

---

## D-5　时间基准

**背景**：截止判定必须"以服务端时间为准"，且"恰好等于截止时刻按迟到处理"。若用各节点本地时钟，多实例部署时判定会不一致。

**候选方案**：A. `System.currentTimeMillis()`；B. 启动时同步 NTP 后本地计时；C. **在每个事务内取数据库时间**。

**最终选择**：**C**。

**代价**：每次出价多一次时间查询；换来的是同一事务内 `server_time`、`ends_at`、流水时间使用**同一时钟源**，不存在跨节点漂移。

**验证结果**：⏳ 待 P1（截止边界单测 + 集成测试）。

---

## D-6　架构形态与进程模型

**背景**：参考方案主张"8 个 Maven 模块 + 独立 worker 进程"。需要判断这个成本在本项目是否成立。

**候选方案**

| 方案 | 说明 |
|---|---|
| A. Maven 多模块（domain/app/infrastructure/worker…） | 编译期阻止非法依赖 |
| B. **单 Maven 模块 + 上下文内四层包结构 + ArchUnit 守卫** | 测试期阻止非法依赖 |
| C. 微服务拆分 | 与原文"小而完整"取向冲突 |

**最终选择**：**B**。

**理由**（两点主动偏离参考方案）：

1. **不拆 8 个（或多）Maven 模块。** 4 个限界上下文 × 层数会产生十几个模块；而"编译期强制"与 ArchUnit 的"测试期强制"在本项目规模下**效果等价**。多模块换来的收益不足以抵消重构成本、构建时间与 pom 样板。上下文边界靠包结构 + `mvn test` 守卫即可。
2. **不拆独立 worker 进程。** 结算扫描器与在线 API **共享同一个应用服务**：定时触发、启动恢复、手动触发走同一条 `SettlementService` 路径。正确性来自数据库条件更新与唯一约束，**进程是否分离不影响正确性**，因此独立 worker 不能增加任何正确性证据。原文要求的"两个实例同时触发结算"，用应用内定时器（每个实例都会扫）+ 并发触发测试即可覆盖。

关于 `settlement` 是否作为独立限界上下文：**不作为**。它与出价共享"拍卖生命周期"这一概念，区别只是**事务边界**不同；把事务边界当上下文边界会凭空制造跨上下文事务。详见 `DESIGN.md` §2.1。

**代价**：放弃编译期强制（改由测试期强制）；放弃 worker 的进程级资源隔离；包结构约束依赖团队自觉 + 测试守卫。

**验证结果**：
- ✅ 已实测：`archunit-junit5:1.5.0` 依赖可解析，`mvn -B -DskipTests test-compile` 通过。
- ⏳ 待 P1：4 条依赖规则随包结构一同落地为可执行的架构测试。

---

## D-7　开发环境拓扑与 Docker 访问方式

**背景**：本机 Windows 需要 MySQL 8；目标 VM（`192.168.117.128`）是**共享中间件开发机**，已有大量容器占用端口。原环境变量指向 `DOCKER_HOST=tcp://...:2376` + `D:\docker-remote` 的 TLS 证书，但该证书 SAN 只包含旧地址，**无法用于当前 IP**。

**候选方案**

| 方案 | 说明 | 否决原因 |
|---|---|---|
| A. 本机安装 MySQL | 不依赖 VM | 与"数据库迁移必须自动执行"的交付要求脱节，且环境不可复现 |
| B. 重签 TLS 证书 + Docker Remote API | 保留原方式 | 必须 `systemctl restart docker`，会重启 VM 上全部容器；且重签后仍需维护证书轮换 |
| C. **Docker over SSH + VM 上独立 MySQL 容器（宿主 3307）** | 不重启任何服务 | —— |

**最终选择**：**C**。

具体编排：

- Docker 访问：`docker context create anolis --docker "host=ssh://anolis-docker"`，作为默认 context；清除 `DOCKER_HOST` / `DOCKER_TLS_VERIFY` / `DOCKER_CERT_PATH`。
- **不使用** VM 上已有 `mysql8`（3306，属其它项目），另起独立 MySQL 容器映射宿主 **3307**，数据隔离。
- 代码与仓库在 Windows，容器在 VM；VM 的 `ens160` 由 DHCP 改为静态同 IP，避免 context 失效。

**代价**：开发依赖网络与 VM 可用性；SSH 方式下相对路径 bind mount 不可用（已由 D-2 一并解决）；需为 VM 配置公钥登录。

**验证结果**：✅ **已实测**

| 证据 | 结果 |
|---|---|
| `docker context show` | `anolis` |
| `docker info` | Server `26.1.3`，Anolis OS 23.5，18 个容器 |
| `nmcli` / `ip addr` | `192.168.117.128/24`，`manual`，网关 `192.168.117.2`，默认路由 `proto static` |
| 密钥登录 | `ssh anolis-docker` 免密成功 |
| 容器改动 | **未重启、未重建任何容器** |

---

## D-8　端口规划

**背景**：VM 上 `8081` 已被 rocketmq-broker 占用，`3306` 已被其它项目的 mysql8 占用。沿用默认端口会直接冲突。

**候选方案**：A. 停掉冲突容器；B. **改用不冲突端口**。

**最终选择**：**B**，且不停用任何他人服务。

| 用途 | 端口 |
|---|---|
| 用户 / 管理 API（含 WebSocket） | `8080` |
| Agent API | `8090` |
| MySQL（宿主机映射） | `3307` |
| 前端开发服务器 | `5173` |
| WebSocket | `18080`（默认值，可配 `server.websocket.port`） |

**代价**：与常见默认端口不同，所有文档与前端配置必须一致；WebSocket 目前是**独立监听端口**而不是与 HTTP 复用同一端口。

**背景补充（P1 实测发现）**：`solon-boot-websocket` 启动的是基于 java_websocket 的独立服务器，不共用 smarthttp 的监听端口。未配置时它静默绑定到 `18080`，而当时这份端口规划里没有它——属于**真实存在的端口漂移**。已实测确认该值可被 `server.websocket.port` 覆盖（设 `18081` 后实际绑定 `18081`，`18080` 不再监听），因此不需要换框架，只需在 P2 建 `app.yml` 时把它纳入环境变量驱动并与前端、nginx 保持一致。

**验证结果**：
- ✅ 已实测：`docs/openapi.yaml`（3 处 Agent server）、`DESIGN.md`、`docs/STATUS.md`、`docs/DOCS.md`、`frontend/src/App.vue`、`docker-compose.yml`、`.env.example` 已全部同步为 8090 / 3307；`docker compose config` 显示 `published: "3307"`。
- ✅ 已实测：后端实际监听 `8080`；`server.websocket.port=18081` 时实际监听 `18081` 且 `18080` 空闲（用 `netstat -ano` 比对监听 PID 与 `jps` 得到的应用 PID 一致，确认不是旧进程残留）。
- ⏳ 待 P2：把 `SERVER_PORT` / `WS_PORT` 改为 `app.yml` 驱动（当前 `SERVER_PORT` 写在 `.env.example` 里但尚未被应用读取，属于已知不一致）。

---

## D-9　Agent 凭据与最小权限

**背景**：原文要求竞拍 Agent"不能直接访问数据库、管理接口或他人私有数据"，并且是"独立的认证凭据与最小权限边界"。

**候选方案**：A. 复用用户 JWT，靠角色区分；B. **独立 Agent Token + 独立端口 + 独立限流**。

**最终选择**：**B**。

**代价**：多出一套凭据生命周期（签发、范围、过期、吊销、限流）与其测试负担；多一个 HTTP 监听端口。

**验证结果**：✅ P5 已实现并验证。签发/吊销走用户 JWT + ADMIN（`agentaccess/adapter/HttpAgentTokenController`），认证与授权走独立端口的 `AgentAuthFilter` + `AgentTokenService`；范围、权限、过期、吊销、限流五项在 `AgentTokenServiceTest`（21）+ `AgentTokenTest`（6）+ `AgentApiIntegrationTest`（23）中逐条断言，并由 `tools/agent_mutation_check.py` 注入 14 种真实缺陷反向确认（G1~G14 全部被杀）。范围语义见 D-29，出价路径复用见 D-30；评审可复制的端到端脚本见 `tools/agent_sim.py`。

---

## D-10　不变量下沉到数据库约束

**背景**：原文要求 INV-1（可用额非负）与 INV-4（成交唯一）在并发下成立。若只靠应用层的 `if` 判断，任何一条遗漏分支、任何绕过服务的写入（运维 SQL、后续新增的入口、Agent 专用路径）都可能破坏不变量，而且事后无法判断是谁写坏的。

**候选方案**：A. 只在应用层校验；B. **应用层校验 + 数据库约束（CHECK / 外键 / 唯一键）**；C. 只用数据库触发器。

**最终选择**：**B**。

**理由**：两者职责不同，不能相互替代——应用层校验的价值是**给出可读的业务错误**（返回哪一个错误码、怎么告诉用户），数据库约束的价值是**“无论如何都写不进去”**。触发器（C）被否决的理由见未采用方案汇总。

**代价**：约束会拒绝部分看起来合理的写入。例如赢家扣款时如果没有把本场冻结同步归零，`frozen_amount <= total_balance` 会直接报错。这强迫“扣总额”与“解冻”必须落在同一句 `UPDATE` 或同一事务里——正是想要的效果，但要求实现者理解约束语义而不是把报错当成噪声。另外，约束报错是 SQL 异常，必须在服务层翻译成业务错误码，否则会以 500 漏给调用方。

**验证结果**：✅ **已实测**——7 项反向验证全部被数据库拒绝，且验证后数据仍与种子一致：

| 反向验证 | 预期 | 实际 |
|---|---|---|
| 冻结额 > 总余额（可用额为负） | 拒绝 | `ERROR 3819 Check constraint 'ck_wallets_available_nonneg' is violated` |
| 总余额写成负数 | 拒绝 | `ERROR 3819`（同样由可用额约束先拦住） |
| 给不存在的用户建钱包 | 拒绝 | `ERROR 1452 ... fk_wallets_user FOREIGN KEY` |
| 非法流水类型（`HACK`） | 拒绝 | `ERROR 3819 ck_ledger_type` |
| 流水金额为 0 | 拒绝 | `ERROR 3819 ck_ledger_amount_positive` |
| 同一 `(auction,user,requestId)` 重复插入 | 拒绝 | `ERROR 1062 Duplicate entry ... for key 'bid_requests.PRIMARY'` |
| 同一拍卖重复成交记录 | 拒绝 | `ERROR 1062 Duplicate entry 'auc_demo_0001' for key 'settlements.PRIMARY'` |

> 注意：INV-2（领先者唯一）与 INV-3（请求幂等）**不能**只靠约束表达，仍需事务内的行锁与条件更新，见 D-4。本条决策只负责把可以静态表达的部分下沉。

---

## D-11　被拒绝的 `requestId` 是否允许重试成功

**背景**：原文只规定“同一个 `requestId` 的重复提交只能生效一次”。若一次出价被拒（例如当时价格偏低），能否用**同一个** `requestId` 重新提交一个现在合法的金额？两种解释都说得通：

| 方案 | 说明 | 代价 |
|---|---|---|
| A. **返回首次结论**（当前实现） | `requestId` 代表“一次请求”，结论被永久缓存 | 客户端拿到拒绝后必须换 `requestId` 才能重试 |
| B. 只防重复生效 | 被拒的请求不算“生效”，允许同 `requestId` 重试 | 同一个 `requestId` 在不同时刻会得到不同结论，超时重试的语义不再可依赖 |

**最终选择**：**A**。幂等的价值在于“客户端可以无脑重发而不必担心结果变化”。若同一 `requestId` 先返回拒绝、后返回成功，客户端就无法区分“这次是新的判断”还是“服务端重复处理了”，重试逻辑反而变危险。A 也是 B 的超集（重复提交仍只生效一次），因此同样满足原文。

**实现**：业务拒绝会回滚全部业务变更，但“该 `requestId` 已被评估过”由一个独立小事务写入 `bid_requests`（`upsertRejectedRequest`）。若该记录写入失败，只记警告、不掩盖原拒绝——否则调用方看到的原因会被替换成无关错误。

**代价**：热路径上业务拒绝多一次写库；被拒记录会持续累积，需定期清理（尚未实现）。

**验证结果**：`BidServiceTest.rejectedRequestIdReplaysTheSameRejection` —— 同一 `requestId` 先被拒，再提交一个合法金额，仍返回原拒绝，且资金分文未动；换新 `requestId` 后正常成交。

**待确认**：这是对原文的解释而非原文直述，**需由项目作者确认是否采纳**。若改为方案 B，只需调整 `BidService.RECORDABLE_REJECTIONS` 与对应测试。

---

## D-12　结算的原子性边界：`SETTLING` 是事务内中间态，不跨事务提交

**背景**：原文的状态机是 `DRAFT → RUNNING → SETTLING → FINISHED`。把 `SETTLING` 写进状态机有两种读法，实现代价差别很大：

| 方案 | 说明 | 代价 |
|---|---|---|
| A. **两阶段** | 事务一：抢占为 `SETTLING`（提交）；事务二：动钱、写成交、置 `FINISHED` | 存在“已抢占但钱还没动”的持久状态。进程在两次提交之间崩溃，拍卖永久卡在 `SETTLING`，钱既没扣也没还，而“扫到期 `RUNNING`”再也找不到它——必须再写一套超时回收逻辑 |
| B. **单事务**（当前实现） | 一把拍卖行锁内完成判定、动钱、写成交、置终态；`SETTLING` 写在事务内部但不跨事务提交 | `SETTLING` 外部观测不到，需在文档里解释清楚；一场结算持有行锁的时间等于整个结算时长 |

**最终选择**：**B**。

**理由**：
1. **自愈比“多一个可见状态”值钱。** 单事务下崩溃等于什么都没发生，拍卖仍是 `RUNNING`（已过截止时间，出价会被 `BID_LATE` 挡住，用户视角上它已经结束了），下一轮扫描自然重试。A 方案的卡死状态没有任何自动恢复路径。
2. **“`SETTLING` 后不可取消”这条规则不需要一个持久状态来表达。** 取消与结算都要先拿拍卖行锁，谁先拿到谁定结局；后到者看到的是已经变了的 `status`，直接被条件更新挡住。
3. **锁持有时间不是问题。** 结算发生在拍卖已过期之后，此时不会再有合法出价与它竞争，长事务不会拖慢任何东西。

**代价**：`SETTLING` 在代码里写了、但在数据库里永远观测不到。已经把这个例外写进 `AuctionStatus`、`SettlementService` 与 `DESIGN.md`，避免后人把它当成漏实现。

**验证结果**：`SettlementConcurrencyTest` 5 个用例全绿，其中“8 线程同时结算同一场”要求 1 次真实结算 + 7 次重放；“两个扫描器同时跑 12 场”要求合计只结算 12 场。变异测试：去掉锁内幂等短路后，4 个用例变红。

---

## D-13　“重放”只适用于同一种结束方式

**背景**：`settleIfDue`（到期结算）与 `cancel`（取消）共享同一条终局路径。当拍卖已有成交记录时，该返回什么？

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 一律返回重放 | 只要已有成交记录就从它返回，`replay = true` | 把“取消”打到已成交的拍卖上时，调用方会收到 `replay = true` + 一个 `TIMEOUT` 结果，字面上被告知“你的取消已生效”，而实际发生的是成交 |
| B. **只在同一种结束时重放**（当前实现） | `cancel` 命中 `CANCELLED` 才重放，命中 `TIMEOUT/NO_BIDS` 报 `INVALID_STATE`；反之亦然 | 调用方需要处理这个错误分支 |

**最终选择**：**B**。

**理由**：`replay = true` 是一句断言——“这个操作已经生效过”。把它用在一个**并没有生效**的操作上，是在对调用方说谎；而客户端依赖这句话做重试决策，被误导的后果比多一个错误分支严重得多。（这个缺陷是在写并发测试时发现的：并发结算与取消的用例出现了“取消返回了成交结果”，见 `DEBUG_LOG.md`。）

**代价**：两个入口需要各自处理 `INVALID_STATE`。

**验证结果**：`SettlementServiceTest.cannotCancelAfterSettlement`、`cannotSettleCancelledAuction`，以及 `SettlementConcurrencyTest.simultaneousSettlementAndCancellationOnlyOneWins`（后者不断言“谁赢”，只断言两种可能的结局都自洽）。

---

## D-14　HTTP 层的错误表达：封套里的 `code` 是权威，HTTP 状态码是它的投影

**背景**：原文只规定“统一响应结构”，没规定错误怎么表达。前端需要区分“余额不够”“没加入这场拍卖”“你重复提交了”这几种情况，而它们全都可能落在同一个 HTTP 状态码上（本领域大多数业务拒绝都是 409）。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 只用 HTTP 状态码 | `409` 表示一切业务拒绝 | 前端靠解析 `message` 字符串分支，改一个字就断；且“重复提交”无法与真错误区分 |
| B. **封套内 `code` 为权威，HTTP 状态码由它与请求性质投影**（当前实现） | `code` 是稳定机读标识（`BID_TOO_LOW` / `INVALID_STATE` / `IDEMPOTENCY_REPLAY`…），每个 code 自带一个默认 HTTP 状态码（`ErrorCode.httpStatus()`） | 客户端必须读 body 才能分支；两层状态可能不同步，需要在过滤器里集中保证一致 |

**最终选择**：**B**，并在实现上把“两层状态不一致”变成一个不可能发生的状态：

- 所有错误都由最外层 `ApiExceptionFilter` 统一渲染，业务代码永远不自己拼错误响应；
- 成功码有两种：`OK(200)` 与 `IDEMPOTENCY_REPLAY(200)`，即幂等重放**不是错误**，用 200 + 专用 code 表达，前端据此提示“已按首次结果处理”；
- 路由层错误（404/405）由框架抛 `StatusException`，过滤器把它翻译成 `NOT_FOUND` / `METHOD_NOT_ALLOWED` 封套——否则框架默认的纯文本 404 会破坏“所有响应都是封套”这条契约。

**代价**：错误码表成为一份需要维护的契约（与 `openapi.yaml` 对齐）；新增错误必须同时决定 HTTP 状态码。换来的是前端可以只按 `code` 分支，且日志、测试、文档引用同一个标识。

**验证结果**：`HttpApiIntegrationTest` 19 个用例逐条断言封套四字段与 `code`（含 401/403/404/405/409/400 六类失败路径）；`IdentityServiceTest` 断言“未知邮箱 / 密码错 / 已禁用”三种情况返回**完全相同的** `UNAUTHENTICATED` 与同一条消息。

---

## D-15　鉴权默认拒绝：白名单之外的路径（含不存在的路径）一律先要令牌

**背景**：`AuthFilter` 需要对 `/api/v1/**` 做鉴权。一个容易被忽略的选择是：**不存在的路径**要不要先过鉴权？

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 先路由再看是否需要鉴权 | 不存在的路径直接 404，已知路径按白名单鉴权 | 未登录者可以用 404 / 401 的差异枚举出哪些路径存在（路由表泄露）；新加接口时若忘了登记，默认是公开的 |
| B. **默认拒绝**（当前实现） | 除显式白名单（`POST /auth/login`、`GET /health`）外，任何 `/api/v1/**` 请求先验令牌，再交给路由 | 未登录访问不存在的路径收到 401 而不是 404，初看反直觉 |

**最终选择**：**B**。

**理由**：默认拒绝的失败模式是“有人报告某接口打不开”，默认放开的失败模式是“某个接口默默对全网公开”。两者代价不对称，因此宁可让错误信息的可用性差一点。同时过滤器放在路由**之前**，也顺带保证了鉴权失败不会被业务代码绕过。

**代价**：调试时需要记住“先拿令牌再看路由”。已在用例中把这条行为固定下来（无令牌访问未知路径 → 401；有令牌访问未知路径 → 404 封套）。

**验证结果**：`HttpApiIntegrationTest.unknownPathWithoutTokenIsRejectedBeforeRouting` 与 `unknownPathWithTokenIsNotFoundEnvelope`；`protectedRouteWithoutTokenIsUnauthenticated`。

---

## D-16　CORS 由显式白名单驱动，未配置即不放开

**背景**：前端（`5173`）与后端（`8080`）不同源，必须允许跨域。原文未规定 CORS 策略。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. `Access-Control-Allow-Origin: *` | 一行搞定，任何前端都连得上 | 任何网站的 JS 都能用访客的浏览器调本服务的接口（预检也不带凭证），对一个涉及资金的服务等于默认对外开放 |
| B. **`CORS_ORIGINS` 白名单，未配置时为空**（当前实现） | 逐请求比对 `Origin`，命中才回 `Access-Control-Allow-Origin`；预检请求由 `CorsFilter` 直接短路返回 204，不进业务与鉴权 | 部署时必须记得配 `CORS_ORIGINS`，否则前端连不上且报错信息来自浏览器、比较隐晦 |

**最终选择**：**B**。

**理由**：开放是显式动作，必须由部署者写出来。“忘了配”的后果（前端连不上，人马上就发现）比“忘了关”的后果（对外裸奔，没人会发现）轻得多。预检短路放在鉴权之前也是刻意的：**浏览器预检请求不携带凭证**，被鉴权拦下就永远过不了。

**代价**：`.env.example` 必须写明 `CORS_ORIGINS` 的含义与格式，且前端端口变更时要同步。

**验证结果**：`HttpApiIntegrationTest.corsFollowsAllowList`（白名单内回显 origin + 预检 204；白名单外不回显 `Allow-Origin`）。编写该用例时踩到的坑记在 [`DEBUG_LOG.md`](DEBUG_LOG.md) DBG-9：JDK 的 `HttpURLConnection` 默认丢弃 `Origin` / `Access-Control-Request-Method` 等“受限头”，导致预检在测试里看起来是坏的。

---

## D-17　测试期配置覆盖：用系统属性覆盖 yml 真实键，并用断言证明覆盖生效

**背景**：生产环境的配置值只能来自 `.env` / 环境变量（C- 系列约定），但**测试**必须自己指定端口、数据库、JWT 密钥等，且不能污染开发库与 8080 端口。这里存在一个很容易静默出错的点：覆盖了配置，但服务其实没读到（`DEBUG_LOG.md` DBG-10）。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 测试前写临时 `.env` / 改 `app.yml` | 与生产读取路径一致 | 改工作区文件，易被误提交；并行跑多个测试类会相互覆盖 |
| B. **设系统属性 + 启动后断言**（当前实现） | `.env` 仍是生产唯一来源；测试只设系统属性，并在启动后断言服务真的用了它 | 测试需要知道 yml 里的**真实键名**（如 `server.port`，而不是环境变量名 `SERVER_PORT`） |

**最终选择**：**B**。关键技术事实（已用 `javap` 核实）：`SolonProps.loadInit` 会把系统属性覆盖到**已加载 yml 的同名键**上，而 `${ENV_VAR:default}` 占位符只读**环境变量**。因此 `SERVER_PORT` 系统属性是无效的，`server.port` 才有效。

**代价**：测试与配置文件的键名耦合；yml 改键名时测试会失败——这正是我们想要的行为（比静默连到另一个实例好）。

**验证结果**：测试基座启动后断言 `assertEquals(port, Solon.cfg().serverPort())`（`ApiTestHarness.verifyServerPort`）；反证成立（改回 `SERVER_PORT` 则该断言立即失败）。完整现象与排查过程见 `DEBUG_LOG.md` DBG-10。

---

## D-18　实时通道的鉴权：一次性短票，而不是把 JWT 放进 URL

**背景**：浏览器无法在 WebSocket 握手上自定义头（`Authorization` 用不了），而把长效 JWT 放进查询串会落进访问日志、浏览器历史与反代日志——等于把一个长效凭证写进日志。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 把 JWT 放进查询串 | 实现最简单，与 HTTP 同一套令牌 | 长效凭证进入日志/历史；无法限制“只能连一次” |
| B. 握手后发一条“认证消息” | 不泄露凭证到 URL | 需要一个“未认证连接”的存活状态与超时回收，服务端要为每个未认证连接预留资源（DoS 面） |
| C. **一次性短票**（当前实现） | URL 里只有 60 秒、单次核销、有容量上限的随机串；凭证来源仍是已认证的 HTTP 请求 | 多一次往返；票需要存放（内存，重启即失效——可接受，重连只需要再取一张） |

**最终选择**：**C**。票用 `SecureRandom` 32 字节、base64url 无填充（可直接放查询串、不需转义）；核销即删除（`remove`），因此重复使用、并发核销都只会成功一次；超过容量上限返回 `RATE_LIMITED`（429）而不是无界增长。

**代价**：多一次 HTTP 往返；票是有状态的（不像 JWT 可以纯无状态校验），因此**不能**多实例共享——当前是单进程部署，已在 `docs/REALTIME_AND_COMMAND_FLOW.md` §5 记录；多实例时需要换成共享存储或短期签名票。

**验证结果**：`WsTicketServiceTest` 10 个（含“恰好到期即失效”与容量上限）、`WsIntegrationTest.ticketIsSingleUse`/`invalidOrMissingTicketIsRejected`（真连接）。变异“把 `remove` 改成 `get`”被杀。

---

## D-19　`seq` 的归属：已提交状态变更的版本号，而不是“每条消息一个号”

**背景**：客户端靠 `seq` 判断“有没有漏消息”。如果序号语义不清（谁推、何时推、一次命令推几个），客户端就会既漏又重。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 每条事件各自一个号 | 每条消息序号唯一 | 一次出价（接受 + 延时）会占两个号，客户端必须处理“同一动作的两个号”，而且“缺号”不再等于“漏消息” |
| B. 服务端维护内存递增号（如 `AtomicLong`） | 实现简单 | 与事务无关：提交失败/回滚也会占号，重启后号丢失；多实例更是各发各的 |
| C. **`auctions.seq` 作为已提交状态版本号**（当前实现） | 与事务同生共死，持久化、可对比、可恢复；一次命令 +1，一条命令的所有事件共享它 | 需要每个命令路径显式推号（容易忘，见下方验证） |

**最终选择**：**C**。规则固定为：成功命令（开拍/首次加入/出价/结算或取消）+1；**被拒的出价 +0**（失败不是状态变更）；**重放 +0**；**重复加入 +0**（否则任何人都能靠连点 `join` 逼全场不停重同步）。客户端按 `(auctionId, seq, type)` 去重（契约 §3）。

**代价**：出价与延时共享同一个 `seq`，客户端必须能接受“同一版本里多条不同类型的事件”——已写入契约，并有测试固定（`EventPublishingTest.extensionSharesSeqWithAccepted`）。

**验证结果**：`EventPublishingTest` 逐场景断言事件 `seq` 与 `Fixtures.seq`（直接查库）相等；`WsIntegrationTest.seqIsGaplessForSubscriber` 断言订阅者收到的版本号只允许相等或 +1，且末端追上库内当前值。变异“重复 join 也推 seq”被 `firstJoinBumpsSeqOnlyOnce` 杀死。

---

## D-20　广播失败的边界：已提交的事务不回滚（A8）

**背景**：原文规则 8 明确“广播失败不得影响已提交的事务”。这是一条一旦写反就非常贵重的错误：回滚会把钱退回去而价格留在库里，或者相反。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 在事务内发布（发布失败则回滚） | 不会出现“库变了而客户端没收到” | 把推送通道的可用性变成了业务提交的前提；一次网络抖动就丢掉一次真实出价 |
| B. 事务提交后发布 + 失败向上抛 | 没有 A 的数据丢失 | 接口会对一个已经生效的提交报错，调用方会重试（而重试会变成幂等重放），语义混乱 |
| C. **事务提交后发布 + 失败只记日志**（当前实现） | 提交的可见性只取决于数据库；“没收到”由重连快照补 | 客户端可能短暂晚于库变化；需要快照重同步机制（C5）才完整 |

**最终选择**：**C**。`AuctionEventPublisher` 的端口注释里把这个约定说明白，各命令服务用 `publishQuietly`（吞掉 `RuntimeException` 并记 WARN）。

**代价**：事件不保证与客户端实际收到的一致（可能丢帧），因此“以快照为准”不是建议而是必需——契约 §6 的客户端规则就是它的配套。

**验证结果**：`EventPublishingTest.broadcastFailureDoesNotRollbackCommittedBid` 用一个“每次都抛异常”的发布器跑真实出价，断言领先者、当前价、本场冻结、流水四项都已落库。去掉吞异常逻辑（变异 M3）该用例立即失败。

---

## D-21　事件里的用户标识：确定性匿名标识（HTTP 快照不受影响）

**背景**：事件是**发给全场的**，而“领先者是谁”又必须让本人认出来（要高亮“我当前领先”）。两者看起来矛盾。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 直接广播 `user_id` | 最简单 | 把用户标识体系广播给所有同场用户，还会被前端缓存、进日志、进回放 |
| B. 随机匿名 ID + 服务端为每个连接单独生成 payload | 不泄露 ID | “一次广播”变成“N 次序列化”，而且客户端无法自行判断“那个是不是我” |
| C. **确定性匿名 ID**（当前实现） | 一个 payload 广播给所有人；客户端对自己的 `userId` 算一次就能比对 | 客户端必须实现同一算法（属跨端契约，因此写进文档 + 固定向量测试） |

**最终选择**：**C**，算法固定为 `"anon-" + SHA-256(userId) 前 8 位十六进制`，`null` 返回 `null`（“无赢家”就是字段缺席）。它不是加密，目标是“不可枚举 + 不泄露内部 ID”。HTTP 快照仍返回原始 ID（那里是本人或有管理权限的人在看，且需要与出价记录对账），差异已在契约中写明。

**代价**：匿名 ID 是**稳定**的，因此同一用户在不同拍卖里标识相同（可被关联）；在本项目单场次演示的尺度内可接受，若上生产需要改为按场加盐。

**验证结果**：`AnonymousIdTest.pinnedVector` 固定 `anon-2952873c` / `anon-76d6c64f`（外部工具算出）；`EventPublishingTest.noEventEverCarriesRawUserId` 扫一条完整流程的全部事件。变异“改成随机值”杀死 10 个用例。

---

## D-22　客户端→WS 的消息一律忽略

**背景**：WebSocket 是双向的，很容易顺手指望它也能收命令（前端一句 `socket.send({type:'BID'})` 很自然）。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 允许通过 WS 出价 | 前端少一次 HTTP 往返 | 需要第二套鉴权、RBAC、幂等键、限流与审计；两条入口的事实来源不一致时难以判定谁对 |
| B. **忽略客户端消息**（当前实现） | 命令入口唯一（HTTP/Agent），WS 只是通知通道 | 客户端发消息会“没反应”，因此需在契约里写明（§6.4） |

**最终选择**：**B**。`AuctionSocketHandler.onMessage` 只记 debug 日志，不解析、不回复、不断连。

**代价**：不能用 WS 做“客户端告诉我漏了哪些事件”的优化；重新同步走 HTTP 快照（契约 §6），因此也没了“伪造缺口拉取他人快照”的入口。

**验证结果**：`WsIntegrationTest.clientMessagesAreIgnored`：发包后无任何业务帧、连接仍开、库内领先者仍为空。

---

## D-23　测试基座的服务生命周期：一个 JVM 只起一个实例，由根上下文存储在整轮结束时停服

**背景**：P3 的 HTTP 与 WS 两组用例需要**同一个**运行中服务（同端口、同一张对象图）。最初把“起停”做成引用计数（最后一个类用完就停），结果第二个用到的测试类启动失败（`DEBUG_LOG.md` DBG-13）。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 每个测试类各起一次（引用计数或 `@BeforeAll`） | 隔离性好 | Solon 是进程级单例，`Solon.cfg()` 启动一次后就缓存住，第二次启动会继续绑到旧端口——不是“重启”，而是“起在没人监听的端口上” |
| B. 起一次，永不停服 | 最简单 | 服务线程还活着，surefire 的 fork 可能卡住不退出 |
| C. **起一次，停服挂在 JUnit 根上下文存储上**（当前实现） | 起停各一次；`ExtensionContext.Store.CloseableResource` 在整轮测试结束时关闭；每个类结束后清掉带凭证的系统属性（DBG-12） | 测试基座多一个扩展类；顺序上依赖“扩展的 `beforeAll` 先于 `@BeforeAll` 方法” |

**最终选择**：**C**。顺带得到一个额外好处：HTTP 与 WS 用例跑的是**同一个实例**，“WS 与 HTTP 读写同一张对象图”这件事本身也就被验证了。

**代价**：测试基座必须用 `@RegisterExtension` 而不是“纯 `@BeforeAll`”，因为 JUnit 不给 `@BeforeAll` 方法注入 `ExtensionContext`（DBG-16）；两个测试类共享实例，因此**不能**并行跑（当前 surefire 未开并行，符合需求规模）。

**验证结果**：全量 116 个用例绿且 fork 正常退出（日志中 `App: Start loading` 只出现一次，结尾有 `App: End stop`）；测试报告里不再出现 `DB_PASSWORD`。

## D-24　出站适配器独立成 `persistence` 包：让“无循环依赖”成为可验证的规则

**背景**：`DESIGN.md` §2.2/§2.4 写着“`application` 不得依赖 `adapter`”“任意两个包之间不得存在循环依赖”。准备写 `ArchUnit` 守卫时先照实检查了一遍代码，发现**这两条当时都不成立**：四个 JDBC 仓储（`AuctionRepository`、`SettlementRepository`、`WalletRepository`、`UserRepository`）与控制器同在 `<ctx>.adapter` 包里，于是每个上下文都有 `adapter`（控制器）→ `application`（用例）→ `adapter`（仓储）的**包级循环**；同时 `application` 直接依赖 `adapter`。另外 `auction/domain/AuctionEvent` 还 import 了 HTTP 侧的时间工具 `api.ApiTime`，违反“`domain` 仅 JDK”。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 放宽规则去迎合现状（只留“`domain` 不依赖框架”） | 零改动，规则立刻全绿 | 文档里的两条约束变成空话；“无循环”这条最容易被破坏的约束失去守护 |
| B. 把仓储抽成 `domain` 端口接口，实现放 `adapter` | 纸面上最“端口适配器” | 事务边界会被打散：每条 SQL 与应用层必须共用同一个 `Connection`，接口化后 `Connection` 要穿过端口，得引入工作单元/会话对象，改动面远大于收益（见 §2.2 折中说明） |
| C. **把出站 JDBC 适配器移进 `<ctx>.persistence`，视图 DTO 归 `<ctx>.application`，时间与分页等基础类型归 `shared`**（当前实现） | 依赖方向固定为 入站 → 应用 → 出站，循环消失；规则可以照原文写 | 一次跨 20 个文件的包改名（纯搬运，行为不变）；`application` 仍依赖具体仓储类，不是端口接口 |

**最终选择**：**C**。`persistence` 是“出站适配器”的独立包，而不是与控制器混住的 `adapter`；`adapter` 从此只指入站。`ApiTime`、`PageQuery` 移入 `shared`（`domain` 得以回归纯 JDK）；HTTP 查询串解析拆到 `api.PageParams`，应用层只见到整数（D-25）。

**代价**：`application` → `persistence` 这条依赖留在明面上（`DESIGN.md` §2.2 表格里写着“允许”），读者必须理解它是刻意的：事务边界在应用层，SQL 在仓储层，两者共用同一个 `Connection`。跨上下文的 `application` 依赖仍然照旧（auction 用 wallet 的仓储/用例）。

**验证结果**：搬运后全量 116 个用例仍绿（行为不变）；新增 `ArchitectureTest` 九条规则全绿；`tools/arch_mutation_check.py` 注入九种真实违规，**9/9 KILLED**——包括“`domain` import slf4j”“`application` 引用控制器”“仓储反向引用用例”“跨上下文引用”与两种环路。

---

## D-25　分页参数与 HTTP 解析分离：`shared.PageQuery` + `api.PageParams`

**背景**：D-24 之后 `application` 已经不依赖 `adapter`，但查询用例仍接收 `api.PageQuery`，而它内部有 `parse(Context)`——于是应用层间接依赖了 HTTP 框架类型，“`application` 不得使用框架 web 类型”这条规则只能写成半条。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 保留 `api.PageQuery`，规则放宽成“不直接 import `org.noear`” | 改动最小 | 规则看不出“用例签名里带着 HTTP 上下文对象”这种真实耦合；换掉 HTTP 层会牵动用例 |
| B. 用例改为接收 `(int page, int size)` | 彻底解耦 | 两个服务与三个控制器各拆一次参数，`Page<T>` 还要另找地方放 |
| C. **`PageQuery`（含上限校验常量与 `Page<T>`）移入 `shared`，HTTP 解析逻辑留在 `api.PageParams`**（当前实现） | 应用层签名不变，控制器只把 `PageQuery.parse(ctx)` 换成 `PageParams.parse(ctx)` | 多一个类；分页上限的“契约数字”与它的 HTTP 解析分处两个包，需要靠注释互相指引 |

**最终选择**：**C**。契约里的 `size <= 100`、非法值一律拒绝（不静默截断）等语义留在 `PageQuery`，看用例的人不需要跳到 HTTP 层才知道上限。

**代价**：理解分页要同时看两个类；`api` 包现在同时有封套、过滤器、当前用户与查询串解析四种横切职责，靠包注释与 `DESIGN.md` §2.3 说明边界。

**验证结果**：`ArchitectureTest.applicationLayerDoesNotDependOnInboundAdaptersOrBootstrap` 把 `com.bidarena.api..` 也列入禁止项并通过；全量 125 个用例绿；分页相关 HTTP 用例（`HttpApiIntegrationTest`）未改一行仍通过。

---

## D-26　前端类型从契约生成，不手写：`openapi-typescript`

**背景**：P4 要拆掉 Mock、接真实 HTTP。风险表里已经登记过一条真实漂移：前端 `Status` 枚举少了 `SETTLING`，编译期毫无察觉。前端手写一套 interface 等于把契约抄了第二遍，两边迟早不一致——而资金相关的字段（金额、`seq`、错误码）对不上时，症状是静默算错，不是报错。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 前端手写 `types.ts` | 零依赖、零构建步骤 | 契约改了没人提醒；漂移只在运行时以 `undefined` 的形式暴露 |
| B. **由 `openapi-typescript` 从 `docs/openapi.yaml` 生成 `schema.d.ts`**（当前实现） | 契约是唯一来源；改完契约跑 `npm run gen:api`，前端编译期就能看到缺字段 | 生成文件入库（评审可读，但不得手改）；多一个 devDependency 与一条生成命令 |
| C. 运行时 `any` + 手动校验 | 不用生成 | 把静态契约降级成运行期约定，资金字段最容易漏校验 |

**最终选择**：**B**。生成物 `src/api/schema.d.ts` 提交入库（评审不必先跑生成），但文件头写明"生成物，勿手改"；契约变更后先 `gen:api` 再改代码。运行时仍在封套层显式校验（见 D-14），静态类型只是第一道。

**代价**：前端构建多一步生成（已由 `package.json` 脚本固定）；`openapi-typescript` 的版本要固定，避免生成物随工具升级变化。

**验证结果**：`src/api/contract.test.ts` 断言生成类型覆盖关键字段与错误码；`npm run typecheck`（`vue-tsc`）通过；它也是 F1~F10 十条客户端变异能被测试抓住的前提。

---

## D-27　前端不得自己算钱与倒计时：服务端是唯一事实来源

**背景**：Mock 版前端自己维护余额、领先者和倒计时，因此能和真实规则不一致（例如本地把剩余时间算成负数、把未结算结果猜成"流拍"）。一旦界面显示一个服务端并不认可的事实，用户的操作就建立在假前提上。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 本地乐观更新（点完立刻算新价与余额） | 手感最快 | 与服务端不一致时没有回收路径；网络失败会留下假数据 |
| B. **所有数字都来自 HTTP 快照与 WS 事件，本地只保留输入态**（当前实现） | 与服务端永远一致；出价价格取响应里的 `price` 而非提交的 `amount`；倒计时用服务端时间校准（`clock.sync`） | 每次操作后要多拉一次钱包/出价记录；断线时数字会停住，必须如实显示连接状态而不是假装在走 |

**最终选择**：**B**。倒计时用 `serverTime` 校准本地时钟：开发机与虚拟机时钟差几小时是常态（实测见过 2 分 40 秒），用本机时间会把进行中的拍卖算成已结束。断线时状态机切到 `retrying`/`resyncing`，界面文案随之变化——**不知道**就说不知道，不猜。

**代价**：多几次读请求；界面必须处理"正在恢复快照"这类中间态，不能只画一个理想状态。

**验证结果**：`arena.test.ts` 断言倒计时在本机时钟偏差 `SKEW_MS` 下仍为 5 分钟、出价后价格取自服务端结果；变异 F15（改用本机时钟）被杀。

---

## D-28　客户端 `seq` 缺口恢复：按 `(auctionId, seq, type)` 去重，缺口拉快照不猜测

**背景**：`seq` 是"已提交状态变更的版本号"（D-19），但**一次提交会发多个 `seq` 相同的事件**（最后 5 秒内的出价同时发 `BID_ACCEPTED` 与 `AUCTION_EXTENDED`）。断线期间错过的版本，客户端无法从后续帧推理出来。两条都容易做错。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 只按 `seq` 去重 | 实现最简单 | 同一次提交的第二个事件被误判为重复丢掉——"延时"在界面上永远不显示 |
| B. 本地缓冲、等缺失的帧到达 | 不额外请求 | 服务端不存事件回溯缓冲，缺的帧不会自己来；界面会永远卡在"等待" |
| C. **`(auctionId, seq, type)` 去重；发现 `seq > baseline + 1` 立刻拉权威快照，对 `<= snapshot.seq` 的缓存事件作废、`> snapshot.seq` 的按序补放；重连换新票并重置基线**（当前实现） | 不猜测、可自愈；连续几次快照仍落后时有重试上限并如实提示 | 每次缺口多一次 HTTP 往返；实现是一个带缓冲与重试的状态机 |

**最终选择**：**C**。体现在 `AuctionFeed`（`feed.ts`）：去重键含 `type`、缺口一律走 HTTP 快照、重连必须重新取一次性票（票用后即废，D-18）并清空基线；快照请求同一时刻只允许一个在飞，避免旧响应覆盖新状态。状态机的归属也刻意放在 `feed.ts` 而不是组件里——组件会被卸载/重复挂载，而"当前基线是哪一版"必须是单一来源。

**代价**：多一个状态机与几次读请求；连续补不上时会在丢弃过期事件后如实提示"快照落后于推送"，而不是无限打接口。

**验证结果**：`feed.test.ts` 14 个用例（含"多事件共享 seq 必须按类型去重""缺口拉快照并丢弃旧事件""补放不丢失""重连续不上时有限重试后如实切回 live"）；变异 F11（去重丢 `type`）、F12（不检测缺口）、F13（恢复时不丢弃旧事件）全部被杀。

---

## D-29　Agent Token 的 `auctionIds` 缺省即“默认拒绝”，而不是“全部允许”

**背景**：签发 Token 时 `auctionIds` 允许省略（契约里不是必填）。于是“省略”必须有一个确定的语义，而这个语义决定了**漏填一个字段的后果是保守还是危险**。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 省略 = 全部允许 | 管理员“先发个能用的”，不用想范围 | 一旦签发接口被越权调用（或管理员笔误），得到的是一枚**对全站拍卖都有权**的 Token；权限边界默认打开，安全模型从“白名单”退化成“黑名单” |
| B. 把 `auctionIds` 改成必填 | 强迫每次都写范围 | 在契约层制造另一种“必填”错误：漏填时得到的是 400 签发失败，管理员无从判断是“范围写窄了”还是“请求格式不对”；且空数组仍然需要一个语义 |
| C. **省略 = 空集合 = 对任何拍卖都无权（默认拒绝）**（当前实现） | 与工程整体的“默认拒绝鉴权”（D-15）一致；漏填的后果由后续 403 明确暴露 | 管理员若漏填，Agent 会读到一串 403，需要回到签发处排查；这是**可发现的失败**而不是静默放权 |

**最终选择**：**C**。`AgentToken.covers(auctionId)` 对空集合恒为 `false`，`AgentTokenService` 不去做“空即全部”的宽容；授权失败按 D-9 的分工返回 403（凭证有效但越权），而不是 401（不认识凭证）——这样调用方知道“换一枚 Token 就行”，而不是以为凭证本身坏了。

**代价**：签发出“对外界什么也做不了”的 Token 是可能的（是特性不是缺陷）；管理员必须理解 403 与 401 的区别。

**验证结果**：✅ `AgentTokenTest`（空集合不覆盖任何拍卖）、`AgentTokenServiceTest.authorizeRejectsAuctionOutsideScope`、`AgentApiIntegrationTest`（空范围 Token 访问任意拍卖得到 403 `FORBIDDEN`）；变异 G3（`covers` 恒为 `true`）被杀，G12（授权不检查范围）被杀。

---

## D-30　Agent 出价复用同一条出价事务，并在事务内自动补参与记录

**背景**：契约里 Agent 只有“读状态（`auction:read`）”与“出价（`auction:bid`）”两个动作，**没有** `join`。而 [`BidService`](src/main/java/com/bidarena/auction/application/BidService.java) 要求出价者必须是拍卖参与者。两条设计必须二选一，且它同时决定“Agent 出价会不会绕过资金不变量”。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 给 Agent 加一个契约里没有的 `/agent/.../join` | 与真人接口同形，Agent 多一步 | Agent 需要理解一个状态机（“先加入再出价”），而它的自主决策循环里这一步没有业务含义；契约也要跟着长出一个新端点 |
| B. 放宽 `BidService`，允许非参与者出价 | 少一次判断 | 真人与 Agent 的行为分叉：同一个服务对两个入口有不同前置条件，参与记录不再可信（旁观者可以凭空成为领先者） |
| C. **Agent 出价时，在同一个事务里补上参与记录（类型 `AGENT`）**（当前实现） | “读到值得出价的状态时，加入是出价的隐含前提”；Agent 出价与真人出价落在**同一张 `bids` 表、同一个事务、同一套 INV 约束**下 | 参与记录的产生与出价耦合，需要在参与者表里区分 `AGENT`/`HUMAN` 类型；`BidService` 多一个“自动加入”的分支参数 |

**最终选择**：**C**。`AgentAuctionService.bid` 只做三件事：授权、取 Token 所属用户、调 `BidService.placeBid(..., AuctionCommandService.PARTICIPANT_AGENT)`；**不另开写入路径**。幂等键与真人共用同一个 `bid_requests` 表，同一 `requestId` 无论来自哪个入口都只产生一次出价与一次资金变动。

**代价**：`BidService` 的签名多了一个参与者类型参数；运营看到两类参与者时必须理解 `AGENT` 的含义（但这也正是审计上想要的区分）。

**验证结果**：✅ `AgentApiIntegrationTest` 断言“Agent 首次出价成功后，参与者记录存在且类型为 `AGENT`、领先者与冻结金额正确”，以及“Agent 出价与真人出价对同一 `requestId` 的幂等语义一致”；变异 G11（去掉自动加入）被杀。资金不变量由既有的 `Invariants` INV-1~4 覆盖，Agent 侧没有新增写入路径，因此不需要第二套不变量断言。

---

## D-31　幂等键的粒度是 `(auctionId, userId, requestId)`，不是 `requestId`

**背景**：`requestId` 由**调用方**生成，用来让“超时重发”不产生第二笔出价。它是否应该跨用户去重，决定了键的宽度。这个选择同时决定“两个客户端撞串”时会发生什么，也决定 Agent 与真人共用一张 `bid_requests` 表时会不会互相吞掉对方的出价。

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 全局按 `requestId` 去重（全站唯一） | 实现最“强” | 两个用户撞串时，后者的出价被静默当成前者的重放，返回别人的成交价；等于把“重试去重”变成了“跨用户抢占”，是一种可被利用的干扰 |
| B. 按 `(auctionId, requestId)` 去重 | 同场内唯一 | 同场两个用户撞串仍然互吞；无法解释“为什么别人的请求号能杀死我的出价” |
| C. **按 `(auctionId, userId, requestId)` 去重**（当前实现，`bid_requests` 主键） | 每个调用方各自一个幂等域：重试去重只作用于它自己的请求 | 同一串号在两个用户下会各自产生一次出价（看起来“没去重”），需要在文档里说清楚 |

**最终选择**：**C**。`db/migration/V1__auction_schema.sql` 里 `PRIMARY KEY (auction_id,user_id,request_id)` 就是这条决策的落点；Agent 与真人共用同一张表、同一套语义，只是 `user_id` 分别是 Agent 绑定的用户与真人。

**代价**：`requestId` 的“唯一性”只在本用户内成立，跨用户不复用。评审时容易被误读成“幂等没生效”（见 `DEBUG_LOG.md` DBG-28：并发重试脚本误把两个用户混成一个幂等域，19 条重放只看到 9 条）。因此契约与文档必须显式写出“键含 `user_id`”。

**验证结果**：✅ `tools/auction_sim.py` 的两条断言。
- 同一用户并发重试 20 次 + 顺序重试 1 次：`恰好 1 条首次接受 = 1`、`其余 19 条为幂等重放 = 19`、`顺序重试 -> IDEMPOTENCY_REPLAY`，且`赢家冻结增量 = 成交价 140`（未重复冻结）。
- 另一用户复用同一 `requestId`：`视为新出价 = OK`。

完整输出见 `DEBUG_LOG.md` DBG-28（52/52 checks passed，退出码 0）。

---

## D-32　尾段“博弈时间”系统性清场 Agent（有意收紧原文规则 6）

**背景**：原文规则 6 是“最后五秒狙击并触发延时”。但“狙击”如果由 Agent 完成，尾段就不再是留给人的博弈，而是谁的机器更快谁赢。需求方给出了一条**有意偏离原文**的规则：拍卖最后一段是“博弈时间”，此时**强制拒绝一切 Agent 出价**；人不在场 Agent 也不能操作，人在场就可以和他人博弈。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 窗口长度 | 最后 5 秒（复用延时窗口） | 只罩住已有的狙击窗口 | 太短：Agent 可在前 15 秒把价格抬到位，人的博弈窗口实质不存在 |
| 窗口长度 | **最后 20 秒**（当前实现） | 从截止时间往前数 20 秒，把 5 秒延时窗口整个罩住并留出缓冲 | 与原文规则 6 的字面数字不同，必须在文档里显式记录为有意偏离 |
| 判定位置 | 只做 HTTP 过滤器拦截 | 实现简单，靠近入口 | Agent 端口与用户端口是两套过滤器；且过滤器拿不到“事务内的数据库时间”，仍存在 TOCTOU |
| 判定位置 | **`BidService` 事务内、拍卖行锁之后**（当前实现） | 用数据库时间判定，与出价是同一个一致性边界 | `BidService` 多一个配置参数；拒绝路径也要走一次事务 |
| 是否记幂等 | 不记录，每次重新判定 | 省一条记录 | 同一 `requestId` 在窗口内/外的重试会返回不同结论，重试语义不可依赖 |
| 是否记幂等 | **记入 `bid_requests`，重放返回同一拒绝**（当前实现） | 与既有 `BID_LATE`/`BID_TOO_LOW` 同构 | 无 |
| 是否只禁写 | **只禁 `POST .../bids`，读仍可用** | Agent 仍能不断读到权威状态并自行停手 | Agent 需要在客户端侧正确理解 403 并停止尝试 |
| 前端如何知道窗口 | 前端写死 20 秒 | 少一个契约字段 | 与 D-27“前端不得自己算服务端事实”相扞；环境变量改窗口后提示会漂 |
| 前端如何知道窗口 | **快照带 `finalGameWindowSeconds`（当前实现）** | HTTP 与 WS 快照同构，前端只用来渲染提示 | 契约多一个字段；`AuctionViews.Snapshot.of` 多一个入参 |

**最终选择**：窗口取**最后 20 秒**，常量 `BidService.FINAL_GAME_WINDOW_SECONDS_DEFAULT = 20`，可被环境变量 `AUCTION_FINAL_GAME_WINDOW_SECONDS` 覆盖；判定在 `BidService.doPlaceBid` 内、`BID_LATE` 之后、自动加入之前，主体信号取**本次调用通道** `autoJoinAs`（Agent 通道恒为非 null，真人通道恒为 null），而不是库里存量的参与类型。拒绝码 `HUMAN_ONLY_PERIOD`（HTTP 403），并加入可记录的拒绝集合。

**为什么“进入窗口就出不去”是自动的、不需要额外锁**：延时规则是“最后 5 秒内出价 +10 秒”。一旦剩余时间 ≤ 20 秒，任何一次真人触发的延时后剩余时间最多 15 秒，仍落在 20 秒窗口内。因此**进入博弈时间等于一直清场到结算**，不需要一个会随崩溃悬空的“锁定态”。

**代价**：与原文规则 6 的字面数字不同（原文没有“清场 Agent”这条）；这是产品要求下的有意收紧，已在本条留痕。`EventPublishingTest` 与其它直接 `new BidService` 的测试需要显式传入窗口参数。

**验证结果**：✅ `BidServiceTest` 新增 4 例（窗口内 Agent 被拒且不留痕迹、同一 `requestId` 重放同一拒绝、窗口外 Agent 可出价并记为 `AGENT`、窗口内真人可出价并记为 `HUMAN`、真人延时后 Agent 仍被挡）；`AgentApiIntegrationTest#agentBidInFinalGameWindowIsRejected` 断言端到端 403 与“同一窗口内真人仍可出价”；`HttpApiIntegrationTest`/`WsIntegrationTest` 断言 HTTP 与 WS 快照都带 `finalGameWindowSeconds`；前端 `arena.test.ts` 断言剩余 ≤ 窗口时 `inFinalGameWindow` 为真、且真人 `canBid` 仍为真（提示不等于拦截）。

---

## D-33　成交主体标识：`bids.actor_type` → `settlements.winner_type` → `ledger_entries.actor_type`

**背景**：需求是“管理员后台流水以及用户流水可以看到最后成交的是 AI 还是人；用户只能看到自己的，要保护隐私”。这要求一个**可追溯的主体标识**，且它的暴露范围要可控。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 事实来源 | `auction_participants.participant_type` | 已有列，不新增 | 该列首次加入后**不再更新**（`join` 是 `ON DUPLICATE KEY UPDATE user_id = user_id`）；人先加入、Agent 后出价会被记成 `HUMAN` |
| 事实来源 | **`bids.actor_type`，结算时快照进 `settlements.winner_type`**（当前实现） | 主体跟着**每一笔出价**走；结算快照之后不受后续数据变化影响 | 多两列与一次快照写 |
| 流水主体 | 结算时按赢家类型写全部流水 | 少一次查询 | 被超过的一方（或取消时所有人）的释放流水会记错主体 |
| 流水主体 | **每条流水写它自己来源出价的主体**（当前实现） | 释放流水也准确 | 释放时多一次“最后出价主体”查询 |
| 暴露范围 | `result` 与流水都公开 `winnerType`/`actorType` | 前端实现最简单 | 泄漏谁是 AI，等于给他人做画像 |
| 暴露范围 | **`winnerType` 只对赢家本人与管理员可见，其余为 null；个人流水只返回本人；另给管理员一个按场次的流水端点**（当前实现） | 满足“管理员可见 + 用户只可见自己” | 多一个 `/admin/auctions/{id}/ledger` 端点；控制器里多一层遮蔽 |

**最终选择**：`bids.actor_type`（`HUMAN`/`AGENT`，`V5` 迁移，存量回填 `HUMAN`）是唯一事实来源；结算把它快照进 `settlements.winner_type`（无成交为 `NULL`）；每条 `ledger_entries` 记 `actor_type`。`GET /auctions/{id}/result` 的 `winnerType` **仅当请求者是赢家本人或 ADMIN 时保留，否则置 null**；`GET /wallets/me/ledger` 只返回本人流水；新增 `GET /admin/auctions/{id}/ledger`（`CurrentUser.requireAdmin`）供运营按场次查看。枚举 `ActorType` 放在 `shared`，因为 auction 与 wallet 都要用，而 wallet 不能反向依赖 auction（`ArchitectureTest`）。

**隐私边界**：本方案不新增“按 `user_id` 查别人流水”的路由；管理员端点按 `auction_id` 过滤、且强制 ADMIN。`actorType` 属于“主体身份”，与既有的匿名标识（D-21）是两回事：前者只在有权限的读路径出现。

**代价**：`bids`/`ledger_entries` 各多一列与一条 CHECK；结算事务多两次查询；管理员的“按场次流水”是新增契约面，需要前端重新生成类型。

**验证结果**：✅ `SettlementServiceTest` 断言真人赢时 `winner_type=HUMAN` 且被超过的 Agent 释放流水为 `AGENT`、Agent 赢时 `winner_type=AGENT`、无成交为 `NULL`；`HttpApiIntegrationTest#winnerTypeVisibleOnlyToWinnerAndAdmin` 断言赢家/管理员可见 `AGENT`、落败者拿到 null；`#adminAuctionLedgerRequiresAdminAndShowsActorType` 断言普通用户 403、管理员可见两条流水的 `actorType`。

---

## D-34　“我的 AI 代理”：把 Agent 授权从运营动作变成用户自助

**背景**：P5 交付的授权链路只有 `POST /admin/agent-tokens`（管理员签发）与吊销，前端“智能体接入”页只是一张**接口清单 dump**：用户点进去既看不到自己的授权、也拿不到一枚 Token，“让我的 AI 替我出价”这条产品路径实际不可用。评审意见很直接——**“前端的 ai 部分有很多问题……不像是给人使用的”**。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 授权主体 | 用户只读管理员发的 Token | 实现最省 | 仍是运营功能，普通用户永远拿不到钥匙 |
| 授权主体 | **用户为自己的 `agentUserId` 自助签发 / 吊销**（当前实现） | 产品闭环 | 需要 `/me/agent-tokens` 三个端点 |
| 归属钉死方式 | 请求体带 `agentUserId`，服务端校验等于调用者 | 表面等价 | 校验漏写即越权，且“代表别人签发”在类型上是**可表达的** |
| 归属钉死方式 | **请求体没有 `agentUserId` 字段，`AgentTokenService.issueForSelf` 在服务层写死**（当前实现） | 越权不可表达 | 多一个请求 DTO |
| scope 限制 | 只允许 `auction:read`（凭证不能花钱） | 安全直觉 | 自相矛盾：Agent 的核心价值就是出价，钱本来就是用户自己的 |
| scope 限制 | **两个 scope 都允许，由用户自选**（当前实现） | 与 D-29 / D-30 的既有语义一致 | 用户可给自己发一枚能出价的凭证——但花的是他自己的钱，且尾段博弈时间仍被 D-32 拦下 |
| 吊销非本人 Token | 403 | 语义直白 | 403 / 404 的差异可被用来枚举 `tokenId` |
| 吊销非本人 Token | **404**（与“不存在”不可区分，当前实现） | 不泄漏存在性 | 排错时略绕（日志里记真实原因） |
| 列表含明文 | 列表返回明文，方便随时复制 | 用户能找回 | 库里只有 sha256，返回明文意味着**反存明文**，破坏 P5 的取证边界 |
| 列表含明文 | **列表永不含明文；明文只在签发响应出现一次**（当前实现） | 保持既有边界 | 用户必须当次保存 |
| 状态口径 | 前端按 `expiresAt` / `revokedAt` 自己算 `ACTIVE` | 少一个字段 | 与鉴权路径的 `AgentToken.activeAt` 可能漂移，症状是“界面说生效，调接口 401” |
| 状态口径 | **服务端下发 `status`（`REVOKED` > `EXPIRED` > `ACTIVE`）**（当前实现） | 与 `activeAt` 同口径 | 多一个派生字段 |
| 列表查询 | 每行各查一次它的场次范围 | 代码直观 | N+1 |
| 列表查询 | **一页一次查询 + 一次 `IN (...)` 批量查 `agent_token_auctions`**（当前实现） | 常数次查询 | 需要独立的 `TokenSummaryRow` 读模型 |
| 全局总览 | 普通用户也能看全局授权 | 少一个分支 | 泄漏他人用了几个 AI，与 D-33 的隐私边界冲突 |
| 全局总览 | **`GET /admin/agent-tokens` 仅 ADMIN，用户只有 `/me`**（当前实现） | 隐私一致 | 前端要按 `isAdmin` 分支渲染 |

**最终选择**：新增 `GET /me/agent-tokens`、`POST /me/agent-tokens`、`POST /me/agent-tokens/{tokenId}/revoke` 与 `GET /admin/agent-tokens`。`CreateMyAgentTokenRequest` 与管理员版本唯一的区别就是**没有** `agentUserId`；服务层 `issueForSelf` 把 owner 钉成调用者，`revokeForAgentUser` 只认自己的 Token（否则 404）。列表走专门的 `TokenSummaryRow` 读模型（`AgentToken` 没有 `createdAt`，且可空语义不同），由 `AgentTokenViews.AgentTokenSummary.of` 统一产出 `status`，前端不再自己算状态。前端把“智能体接入”整页替换为**“我的 AI 代理”**：我的授权（列表 + 新建表单 + 一次性明文卡 + 接入指引）＋（仅管理员）全局授权总览；`store` 里的 `issuedAgentToken` 在 `logout()` 时清空，因为明文在其他账号手里仍是一枚可用凭证。

**为什么不设“只能读不能出价”的档位**：这枚 Token 花的是用户自己的钱，与“用户自己点出价”在资金语义上完全等价，收紧 scope 只会做出一个用户看不懂、也用不上的功能。真正需要拦的是**时机**（D-32 的博弈时间）而非**主体**。

**验证结果**：✅ `AgentTokenServiceTest` 断言 `issueForSelf` 忽略越权归属、非本人吊销抛 404、列表按 owner 过滤且 `status` 与 `activeAt` 同口径、摘要里不存在明文字段、管理员列表覆盖全部 owner；`AgentApiIntegrationTest` 断言 `/me/**` 需要登录、签发出来的 Token 归属调用者、他人吊销返回 404、管理员总览不含明文且要求 ADMIN；前端 `store/arena.test.ts` 断言列表来自服务端且结构上无 `token` 字段、签发请求**从不包含** `agentUserId`、明文只进一次性展示位、吊销后收走明文、`logout()` 清空明文与列表。

---

## D-35　预告开拍：`auctions.starts_at` + 到点自动开拍扫描

**背景**：托管代理（D-36）要能挂在“还没开始”的拍卖上，就必须先回答“谁来开始这场拍卖”。原来的唯一入口是 `POST /admin/auctions/{id}/start`（运营手动点），意味着**运营不在线时用户与代理都只能空等**。产品侧的原话是：“未进行的拍卖……好像没有预告机制……如果有这个机制进行预告，时间到了自动开启，也更合理”。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 开拍触发 | 只保留运营手动 `start` | 不新增列与线程 | 运营不在线就没有开拍，“到点自动进场”无从谈起 |
| 开拍触发 | **拍品带可选 `starts_at`，到点由扫描器自动开拍，手动 `start` 保留**（当前实现） | 两条路径都可用；`starts_at` 为空就是原来的手动模式 | 多一列 + 一个后台线程 |
| 自动开拍的实现 | 再写一套“自动开拍”的事务与状态流转 | 与手动路径解耦 | 开拍规则被复制第二份，两条路径迟早分叉 |
| 自动开拍的实现 | **扫描器逐条调用已有的 `AuctionCommandService.start(...)`**（当前实现） | 开拍规则唯一 | 批量开拍时逐条事务（可接受：开拍本身不是热点） |
| 时间来源 | 应用进程的 `Instant.now()` | 实现最省 | 应用与数据库时钟不一致时，`starts_at` 与 `ends_at` 的基准会不同（本项目实测过约 3 分钟偏差） |
| 时间来源 | **数据库时间 `Db.now(conn)`**（当前实现） | 与结算扫描器、出价判定同源（D-5） | 每次创建/判定多一次 `SELECT NOW(6)` |
| 入参格式 | 接受不带时区的本地时间字面量 | 前端少一次转换 | 歧义：同一串字面量在不同时区代表不同时刻；契约里其余时间戳全是带时区的 |
| 入参格式 | **只接受带时区的 ISO 时刻（`Instant`/`OffsetDateTime`），无时区则 400**（当前实现） | 与契约其余字段一致 | 前端要用 `new Date(v).toISOString()` 显式转一次 |
| 扫描精度 | `starts_at <= now` 就开拍，每轮扫描一次 | 简单 | 只在扫描间隔内近似准时（默认 1 秒） |

**最终选择**：`auctions` 加 `starts_at TIMESTAMP(6) NULL` + `idx_auctions_status_starts(status, starts_at)`（`V6`）；`AuctionRepository.findDueToStartIds` 找 `status='DRAFT' AND starts_at <= ?`；`AuctionStartScheduler`（`AUCTION_START_SCAN_INTERVAL_MS` 默认 1000、`AUCTION_START_BATCH_SIZE` 默认 50，线程名 `auction-start-tick`）逐条 `try/catch` 调用 `AuctionCommandService.startDueScheduled`，后者复用**完全相同的** `start(...)`。快照下发 `startsAt`，前端大厅与运营台显示“预告 mm:ss 后开拍”（用校准过的服务端时间算，不用本机时钟）。

**代价**：`AuctionSnapshot` 多一个字段（前端需重新生成类型）；测试环境必须把扫描间隔调大，否则后台线程会与测试手动调用的 `tick()` / `startDueScheduled` 抢跑，断言变得不确定——已在 `ApiTestHarness` 里用系统属性设成 1 小时。

**验证结果**：✅ `AgentProxyIntegrationTest` 断言未预告的草稿不会被自动开拍、`starts_at` 到点（用数据库相对时间 `NOW(6) - INTERVAL 5 SECOND` 写入）后由 `startDueScheduled` 开拍、开拍后代理下一轮即出价、非法 `startsAt` 返回 400、手动 `start` 之后再扫描得到 0 条（两条路径共用同一个开拍，不会重复开始）；前端用 `formatRemaining(+store.serverNow)` 渲染预告，不靠本机时钟。

---

## D-36　托管 AI 代理：把“让 AI 替我出价”做成不需要写代码的产品功能

**背景**：D-34 把 Agent 授权自助化了，但验收时被否了一半，原话是：“ai 部分还是不适合直接让人使用……点击侧边栏 ai 代理后，进入 ai 界面，然后有创建 ai 代理的选项，可以根据正在进行和未进行的拍卖场进行创建一个 ai 代理，然后时间到自动进场。”也就是说：D-34 交付的是**给开发者的钥匙**（自己去写程序、自己跑进程、自己挂服务器），而普通用户要的是**服务端替他跑**。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 执行位置 | 用户自己跑（D-34 的 Token） | 服务端零成本 | 普通用户不会写程序、不会常开服务器，产品上等于没这个功能 |
| 执行位置 | **服务端托管（`agent_proxies` + 扫描器）**（当前实现） | 普通人可用 | 服务端多一张表、一个线程；要处理“谁的钱、多少预算、什么时候停” |
| 与 D-34 的关系 | 用托管代理取代 Token | 只留一条路 | 自建机器人的用户失去能力，且与 `AGENT_TOOL_SPEC` / `tools/agent_sim.py` 冲突 |
| 与 D-34 的关系 | **两条并列：普通人用托管，开发者用 Token**（当前实现） | 两类用户都覆盖 | 前端要讲清区别（托管是主路径、Token 收进“高级”） |
| 创建时冻结资金 | 按 `budgetLimit` 预冻结 | 结果确定性更强 | 一个还没开拍的代理会长期占住用户余额；且事前不知道真实出价金额，冻结多少都是猜 |
| 创建时冻结资金 | **不预冻结，只在真正出价时按既有的出价事务冻结**（当前实现） | 与 D-30 的资金语义一致，用户不为没发生的事付代价 | 预算上限不等于“一定买得到”（余额被别处用掉时，出价按既有规则失败） |
| 策略 | 让用户配策略（加价幅度、狙击、笔数上限） | 灵活 | 需求方明确只要一件事：“设置预算上限，在预算范围内按照最小加价加价，直到预算上限”；多余旋钮只制造误解 |
| 策略 | **单一规则：不领先就出 `currentPrice + minIncrement`，到预算上限停手**（当前实现） | 行为可预测、可逐条断言 | 无法表达复杂策略（交给 Token 路径） |
| 触顶行为 | 静默停止 | 少一次写 | 用户不知道“为什么我的 AI 不动了” |
| 触顶行为 | **状态置 `BUDGET_REACHED` + 前端提醒一次**（当前实现） | 有明确解释 | 需要状态列与一条提醒通道 |
| 提醒通道 | 为代理开一条 WebSocket 私有事件 | 即时 | 给一个低频、单用户的事件开新推送契约，收益远小于成本 |
| 提醒通道 | **前端轮询 `/me/agent-proxies`，对比前后状态后提醒一次**（当前实现） | 复用已有读路径，不动 WS 契约 | 最坏晚一个轮询周期（3 秒） |
| 博弈时间（D-32） | 给托管代理开例外 | “花了钱”的代理似乎该一直能出价 | 直接违背 D-32：尾段清场 Agent 的意义正是**不因主体是 AI 而例外** |
| 博弈时间（D-32） | **无例外：代理同样被 `HUMAN_ONLY_PERIOD` 拒**（当前实现） | 规则唯一、可解释 | 用户可能以为“AI 坏了”，必须在创建处与帮助文案里写明 |
| 一场多个代理 | 一个人在同一场挂多个 | 灵活 | 等于自己和自己抬价，白烧钱 |
| 一场多个代理 | **`uk_proxy_owner_auction(owner_user_id, auction_id)`：一人一场一个位置**（当前实现） | 语义简单 | 撤销后重建也只能复用同一个位置（`reset`） |
| 出价路径 | 代理直接写 `bids` / `ledger_entries` | 少一次复用 | 复制第二套资金与幂等规则，与 D-30 直接冲突 |
| 出价路径 | **只算下一手金额，交给 `BidService.placeBid(..., PARTICIPANT_AGENT)`**（当前实现） | 资金路径唯一，主体标识自动正确（D-33） | 代理必须正确理解被拒绝的每一种错误码 |
| 幂等键 | 每轮一个新键 | 简单 | 同一金额可能被提交两次，冻结两笔 |
| 幂等键 | **`requestId = "agp-{proxyId}-{amount}"`**（当前实现） | 金额单调变化，重试天然幂等 | 同一金额的重复只可能是同一笔 |
| 撤销后重建 | 物理删除旧行再插入 | 代码直观 | 看不到“曾经挂过、为什么停” |
| 撤销后重建 | **保留行，`reset` 回 `PENDING` 并清空计数**（当前实现） | 唯一键只有一行，历史可追 | 需要一条 `reset` 语句 |

**最终选择**：`V6` 新增 `agent_proxies`（`status ∈ PENDING/BIDDING/BUDGET_REACHED/FINISHED/REVOKED`）。`AgentProxyRepository` 的每条读 SQL 都 `JOIN auctions` 取当前价/最小加价/领先者，让“是否领先、下一手多少、还买得起吗”来自**同一次读取**（避免三次读取拼出一个不存在的状态）；`AgentProxyService.tick(batchSize)` 每轮无记忆地重算（`advance`：终态→收尾，`DRAFT`→等待，领先→只把状态推进到 `BIDDING`，买不起→置 `BUDGET_REACHED`，否则出价）；`AgentProxyScheduler` 每 `AGENT_PROXY_TICK_INTERVAL_MS`（默认 500ms）跑一轮；接口 `GET/POST /me/agent-proxies`、`POST /me/agent-proxies/{proxyId}/revoke`、`GET /admin/agent-proxies`（只读）。前端 AI 页把“创建 AI 代理”（选场次 + 填预算）做成主路径，Token 那一套收进“高级”。

**代价**：多一张表、一个后台线程与一套状态机；`agent_proxies` 必须进测试的 `TABLES_IN_WIPE_ORDER`（否则行会跨用例泄漏，症状是“上一个用例的代理在下一个用例里出价”）；并发创建两个会撞唯一键——已由 `Db.translate` 把 `ER_DUP_ENTRY` 映射成 `CONFLICT(409)` 并带上 `data.proxyId`，不需要新错误码。

**验证结果**：✅ `AgentProxyIntegrationTest` 19 例：草稿上创建（201 / `PENDING` / 下一手金额）、重复创建 409 且带 `data.proxyId`、预算大于可用余额 409 且**什么都没写库**、非法/缺失/负数预算 400、未知场次 404、已结束场次 409、按最小加价跟价（120，领先者 A，`bids.actor_type=AGENT`）、自己领先时不重复抬价也不花钱、预算触顶只记一次（`budgetReachedAt` 跨轮不变、之后不再出价）、博弈时间内 0 次动作且价格与领先者不变（真人仍可出价）、尾段过后恢复正常出价、结束时收尾（`FINISHED`/`won`/`finalPrice`）、`BUDGET_REACHED` 的代理同样能收尾、撤销后不再出价、重建复用同一行（同一 `proxyId`、`bidCount` 归零）、他人的代理撤销 404、`/admin/agent-proxies` 要求 ADMIN 且带 `ownerUserId`、预告开拍后自动进场（见 D-35）；前端 `store/arena.test.ts` 断言候选场次只含 `DRAFT/RUNNING`、创建只发 `{auctionId, budgetLimit}`（结构上没有归属字段）、失败不刷新列表、触顶提醒只出现一次、结束时提醒输赢与成交价、撤销与 `logout()` 会清缓存。

---

## D-37　单 origin 部署：前端容器 + 反向代理把三个端口收成一个

**背景**：D-36 之前，交付物只有“后端镜像 + MySQL”，`docker-compose.yml` 里**没有前端**：评审要自己撑一个静态服务器，再把 `VITE_API_BASE_URL`、`CORS_ORIGINS` 和浏览器的 18080 直连对上；“快速部署”卡在编排而不是业务代码（见本次复盘）。WebSocket 尤其麻烦：`WsTicket` 如实告知 `wsPort=18080`，而部署拓扑未必对外暴露它。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 前端托管 | 后端托管静态资源 | 少一个容器 | 后端要为静态文件与 SPA fallback 负责，Java 服务与前端发版耦合 |
| 前端托管 | **独立 Nginx 容器托管产物**（当前实现） | 职责清晰，Nginx 做静态与反代是本职工作 | 多一个容器、多一份 `nginx.conf` |
| 对外形态 | 浏览器直连 8080/8090/18080 | 不改前端 | 三个 origin：CORS、TLS 证书、WS 独立端口都要分别处理，正是“不好部署”的来源 |
| 对外形态 | **反代收成一个 origin**（当前实现） | 浏览器只访问 `WEB_PORT`，`/api` 与 `/ws` 同源 | 反代成为关键路径；要正确转发 WS 的 `Upgrade`/`Connection` |
| WS 地址 | 前端继续拼 `ticket.wsPort` | 前端不用改 | 同源拓扑下 18080 不对外，表现为“前端一直重连、后端日志正常” |
| WS 地址 | **构建期 `VITE_WS_SAME_ORIGIN=1`，WS 走同源 `/ws`**（当前实现） | 端口由页面自身 authority 决定，反代再转 18080 | 多一个构建期开关；两套拓扑都要有测试（`socket.test.ts`） |
| Agent API（8090） | 也经反代暴露（如 `/agent-api/`） | 单一入口、更“整齐” | 抹掉 D-9/D-29 用独立端口建立的爆炸半径隔离 |
| Agent API（8090） | **保持独立端口，不经反代**（当前实现） | 隔离语义不变 | 使用者仍需直连 8090（`AGENT_TOOL_SPEC.md` 已写明） |
| Nginx upstream | 直接写 `proxy_pass http://backend:8080` | 配置直观 | Nginx 在**启动时**解析主机名：后端未就绪或重建（IP 变化）会起不来 / 连旧 IP |
| Nginx upstream | **变量 + Docker 内嵌 DNS（`resolver 127.0.0.11`）延迟解析**（当前实现） | 消除启动顺序窗口，容器重建后自动跟上 | `resolver` 地址是 Docker 网络专有值（本配置本就只服务 compose） |
| 基础镜像 | 固定 `node:22-alpine` / `nginx:1.27-alpine` | 简单 | 国内/离线环境拉不到 |
| 基础镜像 | **用 `ARG` 暴露，compose 经 `FRONTEND_NODE_IMAGE`/`FRONTEND_NGINX_IMAGE` 覆盖**（当前实现） | 换镜像源不必改 Dockerfile | 配置面多两个变量（已在 `.env.example` 说明，且不配即官方镜像） |

**最终选择**：`docker-compose.yml` 新增 `frontend` 服务（多阶段构建：Node 构建 `dist` → Nginx 托管），只发布 `${WEB_PORT:-8088}:80`；`frontend/nginx.conf` 托管 SPA 并反代 `/api/` → `backend:8080`、`/ws/` → `backend:18080`；`frontend/src/realtime/socket.ts` 的 `socketUrl` 增加 `sameOrigin` 选项，store 按 `VITE_WS_SAME_ORIGIN==='1'` 传入（Dockerfile 构建期默认 `1`）；`docker-compose.yml` 的 `backend` 端口保留发布仅为本机 curl/E2E 工具直连。

**代价**：多一个容器与一份 Nginx 配置；同一份前端代码要支持“直连（端口取自票）”与“同源（端口取自页面）”两种拓扑；`nginx.conf` 的 `resolver 127.0.0.11` 只在 Docker 网络内成立（该文件本就只为 compose 服务）。

**验证结果**：✅ `docker compose config` 通过（服务 `mysql/backend/frontend`，frontend 发布 `8088:80`，构建参数正确渲染）；`frontend/nginx.conf` 经本地 Nginx 镜像 `nginx -t` 语法与配置检查通过；前端 `npm run typecheck` 通过；`npm test` **73 绿**（新增 `socket.test.ts` 4 例：直连用 `wsPort`、同源用页面 host、HTTPS 升 `wss`、`auctionId` 编码）；`VITE_WS_SAME_ORIGIN=1 npm run build` 成功，产物中 `VITE_WS_SAME_ORIGIN` 已被 Vite 内联（不再残留字面量）。⏳ 未在本机 VM 真正构建镜像：Docker daemon 在远程 VM 上且**连不上 Docker Hub**，本地镜像源也没有 node/maven/temurin，故仍未 `docker compose up`（延续 §8 的 C-6）。

## D-38　竞拍 Agent 凭据（API key）只从环境变量读：缺了就报错，不做交互输入

**背景**：原文要求评审“通过环境变量 `AUCTION_AGENT_TOKEN`”把 Token 交给竞拍 Agent，并“不得写入命令历史或仓库”。实现时先加过一级“交互粘贴”（`getpass`）兜底，考虑是“评审手上有 Token、直接跑脚本时不必先 `export`”。但这级兜底与原文初衷相抵：Token 是**长效凭据**，让它经键盘/剪贴板进 shell，反而更容易落进命令历史、录屏与终端回滚缓冲；而“没配”本来就是一分钟能改好的配置问题，用交互兜底等于把配置错误藏成一段隐式流程，还给定重定向/CI 留了“卡在等输入”的隐患。故**去掉交互，只保留变量读取**（本轮修正）。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 来源顺序 | 只信任命令行参数 | 完全显式 | 明文进 shell 历史与进程列表 |
| 来源顺序 | **显式参数 → 环境变量 `AUCTION_AGENT_TOKEN`**（当前实现） | 一句话能说清；脚本内部与 CI 走同一套 | 没配上就停下，用户必须先配好变量 |
| 兜底 | `getpass` 交互粘贴 | 手上已有 Token 时少一步 `export` | 长效凭据经终端输入更易落进历史/录屏/回滚缓冲；非终端下行为不一致，CI 可能卡在等输入 |
| 兜底 | **没有兜底：缺了就直接报错退 2**（当前实现） | 配置问题当场暴露，错误不藏在交互里 | 必须先在 shell / CI 里设好变量 |
| 无凭据时 | 抛异常 / 退 1 | 一眼看出错了 | 把“没配置”报成“检查失败”，与 CI 里的真失败混淆 |
| 无凭据时 | **返回 None，由调用方打印设法并退 2**（当前实现） | 与仓库既有退出码约定一致（2 = 前置不满足） | 调用方要各自组织提示文案 |

**最终选择**：`tools/agent_credentials.py` 只做“显式参数 → 环境变量”（`resolve_agent_token` / `resolve_auction_id` / `resolve_agent_base`），**不 import `getpass`、没有任何交互路径**；`tools/agent_sim.py` 的 `--agent-only --auction-id <id> [--bid]` 缺 Token 或缺 auctionId 时都打印设法并退 2（带 Windows PowerShell 与 Bash 两种写法）；前端「接入指引」与 `README.md` 同步到 `AUCTION_AGENT_TOKEN`，不再承诺任何“提示粘贴”。不带 `--agent-only` 的全流程模式仍自己签发，不受影响。

**代价**：评审手上已有 Token 时，必须先自己设好环境变量才能跑（设法已经印在报错里）；`--agent-only` 因而完全无状态——但这正是“凭据不进命令历史”的代价。

**验证结果**：✅ `python -m py_compile` 通过；凭据解析 **13 条断言**通过（显式 > 环境、空白/空串视为未设置、未设置返回 None、模块内已不存在 `_interactive` 与 `getpass`、`AUCTION_ID`/`AGENT_API_BASE` 的覆盖与默认值）；`--agent-only` 无 Token → 打印 PowerShell/Bash 两种设法并 **退出码 2**，有 Token 但无 auctionId → 提示后 **退出码 2**，后端未起 → 提示启动端口并 **退出码 2**（都不是裸 traceback）；`--help` 正确渲染。**真实双端口实跑**（后端起在 8080/18080 与 `:8090`，连开发库）：用一枚真实签发、范围限定单场、带 `auction:read`/`auction:bid` 的 Token 跑 `--agent-only --auction-id <id> --bid` —— 读状态 200、**出价 200 `OK`**、读结果 404（未结算）→ **1/1 checks passed，退出码 0**；同一场景缺变量 → 退 2（不是连接错误）。同一次实跑里全流程 `tools/agent_sim.py` **44/44**、`tools/auction_sim.py` **52/52**、真后端联调 `npm run test:live` **3/3** 也一并复现。

## D-39　迁移收敛成一次性步骤：一次性进程改 schema，应用只校验

**背景**：部署优化待办里的“调度器/迁移开关”。触发点不是当前的 bug——compose 里只有**一个** `backend`——而是“多实例/滚动发布”这种部署形态下必然会撞上的两件事：

1. **启动争锁**：每个实例启动都会跑 Flyway，在 `flyway_schema_history` 上加锁并逐条执行迁移，抢不到的实例直接启动失败。实例数越多，升级时越容易自己把自己卡住。
2. **schema 与代码同时变**：新版本实例一启动就改 schema，而旧版本实例还在跑旧代码；一个“先发代码、再补迁移”的顺序错误就能让两个版本的代码对同一张表有两种理解。

这两件事的根因是同一个：**“改 schema”与“跑应用”被绑在同一个进程里**。因此把迁移拆成一个只跑一次的步骤，应用侧只做校验。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 谁执行迁移 | 每个应用实例启动时自己迁移（改动前） | 单实例下够用 | 多实例争表锁；schema 变更与代码发布耦合在同一个进程里，无法“先迁移、后放流量” |
| 谁执行迁移 | **一次性进程（`MigrateMain` / compose 的 `migrate` 服务）**（当前实现） | 迁移只发生一次、可单独重跑、失败即阻断应用启动 | 多一个服务与一次容器往返；单实例部署也多了一步 |
| 应用侧默认 | 默认 `MIGRATE_ON_START=false`（强制走一次性迁移） | 一致性最强 | 本机“起个后端看看”也得先跑迁移，开发体验变差 |
| 应用侧默认 | **默认 `true`（保持历史行为），compose 里显式设 `false`**（当前实现） | 本机与 CI 的单进程路径零改动，多实例风险只由部署显式承担 | 同一份镜像两种行为，靠编排与文档区分 |
| 关掉迁移后应用做什么 | 什么都不做 | 最轻 | 库落后于代码也能起来，等到某个 SQL 报“字段不存在”才暴露，错误点离原因很远 |
| 关掉迁移后应用做什么 | **只校验（pending 检查 + `Flyway.validate()`），不一致即拒绝启动**（当前实现） | 失败点从“第一个用户请求”前移到启动 | 启动多一次校验；库“超前于代码”（回滚）也会拒绝启动——但这与改动前 `validateOnMigrate=true` 的行为一致，不是新限制 |
| 开关取值解析 | 宽松解析（`yes`/`on`/`1` 都当真） | 容忍手写差异 | `MIGRATE_ON_START=no` 会被当成真而静默迁移，正是最难查的部署事故 |
| 开关取值解析 | **只认 `true/false/1/0`，其余抛异常**（当前实现） | 拼错当场启动失败 | 手写 `.env` 拼错会直接起不来（这是想要的） |
| 迁移用镜像 | 单独构建一个迁移镜像 | 职责单一 | “迁移用的代码”与“跑应用的代码”会漂移，两者认的 schema 可能不是同一份 |
| 迁移用镜像 | **同一个镜像，只覆盖 entrypoint**（当前实现） | 迁移与应用永远来自同一次构建 | entrypoint 写在编排里而不是 Dockerfile 里 |
| 三个扫描器的开关 | 本轮一并加 `*_SCHEDULER_ENABLED` | 与迁移开关对称 | 单实例下没有第二个实例需要关；真要多实例，缺的是“哪个实例负责扫描”的职责划分（项目已否决 Redis 分布式锁），一个布尔开关反而会让人以为问题解决了。**本轮不做**，留到真有第二实例时与部署形态一起设计（**后续：D-40 把它做了**——把“本实例是否承担”与“谁自动负责”拆成两层，开关只解决前者，不假装解决后者） |

**最终选择**：`DatabaseBootstrap` 拆成 `connect()`（只建池）/ `applySchema(DataSource)`（按 `MIGRATE_ON_START` 决定迁移还是只校验）/ `migrateToLatest(DataSource)`（无条件迁移，供一次性进程用）；新增 `MigrateMain`（`java -cp app.jar:libs/* com.bidarena.bootstrap.MigrateMain`，成功退 0、失败退 1 并打印原因）；`Env` 新增严格布尔解析 `boolOr`；`docker-compose.yml` 新增一次性 `migrate` 服务（同镜像覆盖 entrypoint、`restart: "no"`、与 backend 共用一份 DB 环境变量锚点），`backend` 改成 `MIGRATE_ON_START: "false"` + `depends_on: migrate: condition: service_completed_successfully`；`.env.example` 补上新变量及其两种适用场景。

**代价**：编排里多一个服务（单实例部署也多一次容器往返）；`MIGRATE_ON_START` 让同一镜像有了两种行为，只能靠文档与 CI 断言兜住；`backend` 的启动顺序依赖 mysql 的健康检查“说真话”，所以该 healthcheck 必须走 TCP（`-h localhost` 走 unix socket，会在 3306 还没监听的初始化窗口里误报健康，见 DBG-34）；关闭自动迁移时若库里已有代码不认识的迁移（回滚场景），启动会被拒绝（与改动前一致）。

**验证结果**：✅ 后端 `mvn clean verify` **233/233** 绿（原 225 + 新增 8）。新增 `EnvTest` 4 例（缺省回退、四种写法与大小写、`yes/on/no/off/2/tru` 全部抛异常、首尾空白）；新增 `MigrationToggleTest` 3 例（**真库**）：库落后于代码时只校验会拒绝启动**且确实没有偷偷迁移**（断言库仍停在 V5）、打开开关则补齐到 V6、`migrateToLatest` 不看开关。变异验证：把 `applySchema` 的开关判断改成恒 `true`（忽略开关）后，`verifyOnlyRefusesWhenSchemaIsBehind` 变红（1 failed），还原后绿。命令行实跑（连开发库）：`MigrateMain` 退出码 **0** 并打印“数据库结构已是最新版本（当前版本 6）”；把 `DB_URL` 指向关掉的端口 → `一次性迁移失败：Failed to initialize pool: Communications link failure` 且退出码 **1**；漏配 `DB_PASSWORD` → 提示“缺少必填配置 DB_PASSWORD…”，同样退 **1**（都不是裸栈）。`docker compose config` 通过，`migrate` 服务的 entrypoint 与合并后的 `environment` 渲染正确。另新增 `ComposeEntrypointTest` 1 例：从 compose 里解析出 `- com.bidarena.*` 形式的入口类，断言它们真的在 classpath 上且带 `main`（正是它挡住了 DBG-33——首轮 CI 抓到的入口类名少一层包名）。CI 新增一步断言：`migrate` 容器退出码为 0 + 迁移日志里有结论 + backend 日志里出现 `MIGRATE_ON_START=false`（证明应用走的是只校验那条路）。该断言在 [run #7](https://github.com/Ayong-ui/bid-arena/actions/runs/34806342084)（`14270e0`）随五个 job 一起转绿——这是这套编排第一次真的被跑起来；此前三轮失败的复盘见 DBG-33 与 DBG-34。

---

## D-40　后台扫描器的归属开关：显式声明“本实例不跑”，但不假装解决了自动分工

**背景**：D-39 把“改 schema”从每个应用实例启动时摘出来之后，同一类问题在三个后台扫描器上仍然存在：**多实例/滚动发布时，每个实例都会无条件启动结算、预告开拍、托管代理三个扫描器**。它们本可以多实例同时跑（每轮都回数据库查“该做什么”，重复触发由行锁与唯一约束收敛），但“能跑”不等于“应该跑”：N 个实例各自每 500ms 扫同一张表是纯浪费；滚动发布时也常有“先起一批只对外服务、不承担后台任务”的诉求。

这里有个关键判断：D-39 当时拒绝加开关，理由是“真要多实例，缺的是‘哪个实例负责扫描’的职责划分，一个布尔开关会让人以为问题解决了”。这个理由对**自动分工**成立（选主/分片，本仓库已明确否决 Redis 锁那一类方案），但它把两件事混在了一起——**“本实例要不要跑”是部署者立刻就能回答的显式问题**，而“哪个实例自动负责”是另一个问题。开关解决前者；后者继续不做，也不假装做了。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 是否加开关 | 不加，每个实例都跑 | 单实例下零配置 | 多实例下 N 份重复扫描；滚动发布时新旧实例都在扫 |
| 开关数量 | 一个总开关（worker 模式） | 配置项少 | 三件事的代价差很远（结算是“到点必须发生”、代理是高频抖动）；一个总开关把“哪个实例负责什么”压成“不知道”，恰恰是排查时最需要的那个信息 |
| 开关数量 | **三个独立开关**（当前实现） | 每件事可单独裁剪；启动日志逐项打印本实例的分工 | 多两个配置项 |
| 缺省值 | 默认全关，要求显式打开 | 意图最明确 | 改变既有单实例部署的行为（升级后后台任务不再跑），与 D-39 的“保守缺省”口径相反 |
| 缺省值 | **默认全开**（当前实现） | 单实例与本地开发零改动；漏配不会悄悄停掉业务 | 漏配多实例时会让每个实例都跑（但不会算错，只是重复扫描） |
| 开关在哪一层生效 | 循环体里判断（线程照跑，跳过任务） | 实现简单 | 关掉的实例仍然占线程、仍每隔几百毫秒醒一次，“关了等于没关” |
| 开关在哪一层生效 | **构造期：关掉就根本不创建扫描器**（当前实现） | 关掉的实例真的没有任何后台线程；`Started.stoppers` 只含实际创建的 | 停机逻辑要跟着“哪些被创建了”走 |
| 多实例自动分工 | 选主 / 分片 / Redis 分布式锁 | 真正解决“谁负责” | 引入第二事实来源或一套协调逻辑，失效时无法自证一致性（已否决 Redis 锁，见 D-4 与「未采用方案汇总」） |
| 多实例自动分工 | **不做，只提供显式开关**（当前实现） | 把决策交给部署者，代码不引入新的失效模式 | “关掉”是静默的：没人保证还有别的实例在跑，只能靠启动 WARN 与文档提醒 |

**最终选择**：新增 `bootstrap.ScannerBootstrap`——组合根里唯一负责“本实例启动哪些扫描器”的一段；常量 `SETTLE_SCHEDULER_ENABLED` / `AUCTION_START_SCHEDULER_ENABLED` / `AGENT_PROXY_SCHEDULER_ENABLED`；复用 D-39 的 `Env.boolOr`（严格布尔，只认 `true/false/1/0`，拼错即启动失败）；开关在**构造期**生效（关掉就不 `new` 扫描器）；`start()` 返回 `Started(started, skipped, stoppers)`，把停机动作一并交出，`Application` 里原本三段几乎一样的 `addShutdownHook` 收敛成一处；启动时逐项打印本实例负责/关闭的扫描器，关闭项以 WARN 附上“必须保证还有实例在跑”的提醒；`.env.example` 与 `docker-compose.yml` 的 `backend` 补上三个变量（编排里用 `${VAR:-true}`，缺省仍全开）。

**代价**：同一份镜像有了两种行为，与 D-39 一样只能靠文档与 CI 断言兜住；缺省全开意味着**漏配不会暴露**（多实例时表现为重复扫描，而不是报错）；“关掉”是静默的，没有任何机制保证“别人在跑”，所以它只能降低浪费、不能提高可用性——这一点必须在 WARN 与 README 里说清，否则会重蹈 D-39 担心的“以为问题解决了”。

**验证结果**：✅ 后端 `mvn clean verify` **239/239** 绿（原 233 + 新增 6）。新增 `ScannerBootstrapTest` 6 例：纯策略 3 例（缺省全开、显式关掉只影响对应项、拼错取值抛异常且消息含键名）+ 文档防漂移 1 例（断言 `.env.example` 里每个开关都有 `KEY=`）+ **真库**对照 1 组（`allOffStartsNothing` 断言全关时不创建任何扫描器、`stopped` 为空；`settlementSchedulerHonoursTheSwitch` 先全关、让 500ms 级的结算扫描等 1.5s 仍停在 `RUNNING`，再只打开结算并以 200ms 间隔轮询到 `FINISHED`、成交为 `u_1|110|TIMEOUT`——**只测“关”不测“开”最容易假绿**，所以两条一起写）。变异验证：把 `ScannerBootstrap.start` 的开关判断改成恒 `true`（忽略开关）后，`allOffStartsNothing` 与 `settlementSchedulerHonoursTheSwitch` 均变红（2 failed，其余 4 条仍绿），还原后全绿。`docker compose config` 渲染正确（`backend` 的 `environment` 含三个键且缺省为 `true`）。README「未完成边界」与 `.env.example` 已同步：明说它只解决“本实例不跑”，不解决“谁自动负责”。

---

## D-41　配置的可发现性也由测试守住：扫源码断言 `.env.example` 不漏键

**背景**：最后一轮一致性校对时，把“代码里 `Env.*` 读的键”与“`.env.example` 写的键”逐一对了一遍，发现 4 个键后端会读、样例里却没有：`AGENT_SERVER_HOST`、`JWT_TTL_SECONDS`、`DB_CONNECTION_TIMEOUT_MS`、`DB_MAX_LIFETIME_MS`。它们都有默认值，所以代码能跑、全部测试绿——踩坑的只有“照着样例部署的人”：他不知道存在这些键，只能吃默认值，或者以为不可配。这正是 D-39/D-40 一直在处理的那类问题：**配置表面就是部署者对系统的全部认知**。

| 决策点 | 方案 | 说明 | 代价 |
|---|---|---|---|
| 怎么保证不漏 | 靠 review / 提交清单 | 零成本 | 已经漏了 4 个；清单会过期，而过期的清单只给虚假的安全感 |
| 怎么保证不漏 | **扫源码 + 断言（当前实现）** | 新增键时忘了写样例，`mvn clean verify` 直接变红 | 需要一条“读仓库文件”的测试（已有先例：`ComposeEntrypointTest` 读 compose、`ScannerBootstrapTest` 读 `.env.example`） |
| 扫描的粒度 | 手工维护一份“键的清单”再比对 | 断言写法简单 | 清单本身也会过期——等于把同一个问题挪了一层 |
| 扫描的粒度 | **正则扫 `Env.required/read/intOr/longOr/boolOr("KEY")` 字面量**（当前实现） | 不用维护清单，读代码即真相 | 常量传键的扫不到（目前只有三个扫描器开关），要显式登记；正则失效会静默放过 |
| 守卫怎么证明没坏 | 只写正向断言 | 简单 | 正则或根目录一旦失效，守卫恒为真，比没有守卫更危险 |
| 守卫怎么证明没坏 | **反向断言“确实抓到了一批已知键”+ 合成输入用例**（当前实现） | 守卫失效会先响 | 多两条用例 |
| 只写在注释里的键算不算 | 算（一句 `contains("KEY=")`） | 实现最短 | `.env.example` 里 `#BID_ARENA_TEST_DB_URL=...` 这种“模板”会被当成已文档 |
| 只写在注释里的键算不算 | **不算：只认非注释行上的 `KEY=`**（当前实现） | 与“照着样例的人能不能直接照抄”一致 | 实现多一点（要剥掉注释行） |

**最终选择**：新增 `EnvDocumentationTest`（3 例）：① 扫 `src/main/java` 下所有 `Env.*("KEY")` 字面量、并入以常量传键的 `ScannerBootstrap` 三个开关，断言每个键在 `.env.example` 里都有一条非注释赋值；② 反向断言扫描确实抓到一批已知键（`DB_URL`/`JWT_SECRET`/`SETTLE_SCAN_INTERVAL_MS`/开关，且总数 ≥ 15），守住卫不会变成永远为真；③ 用合成输入证明“只写在注释里”会被判为缺失。同时按用途把那 4 个键补进 `.env.example`（连同默认值与“留空＝监听所有网卡”这类语义说明）。

**代价**：多一个“读源码文本”的测试——它比行为测试脆（改目录结构或读取方式都可能影响它），所以那两条反向断言是必需的；另外它只覆盖 main 源码，测试专用的键（如 `BID_ARENA_TEST_DB_*`）不在范围内。

**验证结果**：✅ 先把 4 个键补进 `.env.example`，后端 `mvn clean verify` **242/242** 绿。变异验证：把 `.env.example` 换回改动前的版本（`git show HEAD:.env.example > .env.example`）后，`everyKeyReadByTheBackendIsDocumented` 变红并**正好点名这 4 个键**（`AGENT_SERVER_HOST`/`DB_CONNECTION_TIMEOUT_MS`/`DB_MAX_LIFETIME_MS`/`JWT_TTL_SECONDS`），换回后全绿——即这条守卫在引入它之前就会拦住这次漂移。计数口径同步：真库集成 88→90（D-40 的 2 例真库对照改归此项）、扫描器开关纯策略 4、配置键守卫 3、其余单元 136、架构守卫 9，合计 242；README/DESIGN/STATUS/TRACEABILITY/AI_USAGE/`ci.yml`（断言总用例数 ≥ 242）已同步。

---

## 未采用方案汇总

| 方案 | 未采用原因 | 如果重来会怎样 |
|---|---|---|
| 复用"校园跑腿"项目资产 | 该代码库并不存在于本仓库，`REUSE_MAP.md` 属空头承诺 | 已删除该文档，改为原创实现 |
| Docker Remote API over TLS | 证书 SAN 与当前 IP 不符；重签需重启 dockerd，影响他人容器 | 维持 SSH 方式 |
| 复用 VM 已有 mysql8（3306） | 与其它项目共用库，迁移与种子会互相污染 | 独立容器 3307 |
| `docker-entrypoint-initdb.d` 迁移 | 只在空数据卷首次执行；远程 bind mount 失效 | Flyway（D-2） |
| 用数据库触发器维护不变量 | 逻辑分散在表上，调试与演练时需要跨层跳跃；触发器内的错误码无法传给业务层；后续改规则要再发一次迁移 | 约束声明不变量的**边界**（D-10），事务过程仍写在服务层（D-4） |
| 只在应用层校验不变量 | 任何绕过服务的写入（运维 SQL、新入口）都能破坏不变量，事后无法定位 | 下沉到 CHECK / 外键 / 唯一键（D-10） |
| Maven 多模块 + 独立 worker 进程 | 编译期强制与 ArchUnit 等价；进程分离不增加正确性证据 | 单模块 + 包边界 + ArchUnit（D-6） |
| 后端托管前端静态资源 | Java 服务要为静态文件与 SPA fallback 负责，前端发版与后端发布耦合 | 独立 Nginx 容器（D-37） |
| 拆 `App.vue`（960 行 SFC → 若干子组件） | 纯代码卫生，与“方便别人在本地部署”无关；更要紧的是前端**没有组件测试基建**（`@vue/test-utils`/jsdom 均未安装，73 个单测只覆盖 store/api/realtime 等纯 TS 模块），`App.vue` 本身零自动化覆盖——拆完 `npm test` 全绿也证明不了“行为零变化” | 仍值得拆，但要先补一个渲染冒烟测试（`@vue/server-renderer` 已在依赖里）当兜底，否则宁可不动 |
| 靠 review 保证 `.env.example` 覆盖所有配置键 | 事实上已经漏了 4 个（`AGENT_SERVER_HOST`/`JWT_TTL_SECONDS`/`DB_CONNECTION_TIMEOUT_MS`/`DB_MAX_LIFETIME_MS`），而且漏了不会有任何信号——代码有默认值，测试全绿 | 扫源码 + 断言 + 反向断言（D-41）|
| 浏览器直连 8080/8090/18080 三个端口 | 三个 origin：CORS、TLS 证书、WS 端口都要分别处理 | 反代收成单一 origin（D-37） |
| Agent API 也经反代暴露 | 抹掉独立端口建立的爆炸半径隔离（D-9/D-29） | Agent 保持 :8090 直连（D-37） |
| 为离线环境把镜像源写死进 Dockerfile | 把某个环境的私有地址固化进交付物 | 用 ARG + compose 变量覆盖（D-37） |
| 7 个限界上下文 | 多数上下文在本领域不存在；`settlement` 是事务边界而非上下文 | 4 个上下文（`DESIGN.md` §2.1） |
| Redis 作为事实来源 / 分布式锁 | 引入第二个事实来源，失效时无法自证一致性 | MySQL 唯一约束 + 行锁（D-4） |
| 全内存 Mock 做并发测试 | 原文明确要求关键并发与结算测试使用真实 MySQL | Testcontainers / 真库集成测试 |
| 应用层 `synchronized` 保证唯一赢家 | 仅单进程有效，与"多实例同时结算"要求冲突 | 数据库条件更新（D-4） |
| 两阶段抢占 `SETTLING` + 超时回收 | 崩溃会留下资金悬空、扫描找不到的死状态，必须额外写一套回收逻辑 | 单事务结算（D-12） |
| 到期结算与取消共用"有记录就重放" | “取消”会拿到 `replay=true` 的成交结果，等于对调用方说谎 | 重放限定在同一种结束方式（D-13） |
| HTTP 错误只用状态码表达 | 前端要靠解析消息字符串分支；“重复提交”与真错误无法区分 | 封套内 `code` 为权威（D-14） |
| 鉴权先路由、按已登记路径决定是否校验 | 未登录者可用 401/404 差异枚举路由；新增接口默认为公开 | 默认拒绝 + 显式白名单（D-15） |
| `Access-Control-Allow-Origin: *` | 任何网站都能用访客浏览器调资金接口 | 显式 `CORS_ORIGINS` 白名单，未配置为空（D-16） |
| 让 `solon-web` 自带的 snack3 做 JSON 序列化 | 与显式声明的 Jackson 冲突且不确定谁赢，请求体解析行为随依赖树漂移 | 排除 snack3，序列化器唯一（`DEBUG_LOG.md` DBG-11） |
| 把控制器依赖写进 `@Component` 注解 | 构造函数签名被框架的注入规则钉死，测试/生产接线方式被迫一致 | 组合根显式 `wrapAndPut` 注册（`Application` / `bootstrap/Services`） |
| 测试期改临时 `.env` / `app.yml` 来换端口与库 | 污染工作区、易误提交；并行运行多个测试类会相互覆盖 | 系统属性覆盖 + 启动后断言（D-17） |
| 把 JWT 放进 WebSocket 的查询串 | 长效凭证会进访问日志、浏览器历史与反代日志；也无法限制“只能连一次” | 一次性 60 秒短票（D-18） |
| 用内存 `AtomicLong` 给事件编号 | 与事务无关：回滚也占号、重启即丢、多实例各编各的 | 用 `auctions.seq` 作为“已提交状态版本号”（D-19） |
| 在事务内发布事件（发布失败即回滚） | 把推送通道的可用性变成业务提交的前提，一次抖动就丢掉真实出价 | 提交后发布 + 失败只记日志（D-20） |
| 事件里直接广播 `user_id` | 把用户标识体系广播给全场，并会进前端缓存、日志与回放 | 确定性匿名标识（D-21） |
| 用 WebSocket 做第二条命令入口 | 需要第二套鉴权、RBAC、幂等与审计，两个入口还可能有不同结论 | WS 只做通知，客户端消息一律忽略（D-22） |
| 每个测试类各起停一个服务实例 | Solon 是进程级单例，第二次启动仍绑旧端口，症状是“服务起不来”却不是代码问题 | 一个 JVM 一个实例，停服挂在根上下文存储上（D-23） |
| 为迁就现状放宽 ArchUnit 规则 | `application` → `adapter` 与包级循环会永远留在代码里，文档里的约束变成空话 | 先搬运出 `persistence` 包，再照文档写规则（D-24） |
| 把仓储全部抽成 `domain` 端口接口 | 事务里每条 SQL 都要共用同一个 `Connection`，接口化会把事务边界拆散 | 只对真正可替换的能力抽端口（事件发布），仓储保留具体类（D-24） |
| 让查询用例自己接收 HTTP 的 `Context` / `PageQuery.parse` | 用例签名里带着 HTTP 上下文对象，换掉 HTTP 层会牵动用例 | 分页参数进 `shared`，解析留在 `api`（D-25） |
| 前端手写一套与后端对应的 interface | 契约被抄第二遍，字段漂移只在运行时以 `undefined` 暴露 | 由 `openapi.yaml` 生成类型（D-26） |
| 前端本地乐观计算余额 / 倒计时 | 与服务端不一致时没有回收路径，用户基于假事实操作 | 所有数字来自快照与事件，倒计时用服务端时间校准（D-27） |
| 客户端只按 `seq` 去重 / 缺口等待缺帧 | 同一次提交的第二个事件被误杀；缺帧不会自己来 | 按 `(auctionId, seq, type)` 去重 + 缺口拉快照（D-28） |
| Agent Token 的 `auctionIds` 省略即“全部允许” | 漏填一个字段就等于发了一枚全站可用的凭证，权限边界默认打开 | 省略 = 空集合 = 默认拒绝（D-29） |
| 为 Agent 单开一条出价路径 / 加“先加入再出价” | 资金与幂等规则被复制第二份；Agent 被迫理解一个与决策无关的状态机 | 复用同一出价事务并在事务内自动补参与记录（D-30） |
| 幂等键只按 `requestId`（全站唯一）或 `(auctionId, requestId)` | 两个调用方撞串时，后者的出价被静默当成前者的重放并返回别人的成交价，等于让别人的请求号能杀死你的出价 | 按 `(auctionId, userId, requestId)` 各自一个幂等域（D-31） |
| 只在最后 5 秒（延时窗口）清场 Agent | 5 秒太短，Agent 可在前 15 秒把价格抬到位，人的博弈窗口实质不存在 | 用最后 20 秒覆盖延时窗口（D-32） |
| 用 HTTP 过滤器（而非出价事务）判定博弈时间 | 过滤器拿不到事务内的数据库时间，仍存在检查与出价之间的竞态 | 在 `BidService` 事务内用数据库时间判定（D-32） |
| 前端把博弈窗口写死为 20 秒 | 与 D-27 冲突，改环境变量后提示会漂 | 快照下发 `AuctionSnapshot.finalGameWindowSeconds`，前端只做提示（D-32） |
| 用 `auction_participants.participant_type` 判定主体 | 该列 `join` 时 `ON DUPLICATE KEY UPDATE user_id = user_id`，人先加入、Agent 后出价会被记成 `HUMAN` | 主体跟着每一笔 `bids`/`ledger_entries` 走（D-33） |
| 在公开的 `result`/`bids` 响应里直接给出 `winnerType` | 泄漏谁是 AI，等于给他人做画像 | 仅赢家本人与管理员可见，其余为 null（D-33） |
| Agent 授权只能由管理员签发、用户只读接口清单 | “让我的 AI 替我出价”这条产品路径对普通用户不可用 | 用户自助签发自己名下的授权（D-34） |
| “代表谁签发”作为请求体字段再校验 | 越权在类型上可表达，漏写一次校验就是越权 | 请求体不含 `agentUserId`，服务层写死（D-34） |
| 吊销他人的 Token 返回 403 | 403/404 的差异可用来枚举 `tokenId` | 统一 404，不泄漏存在性（D-34） |
| 列表接口返回明文 Token 方便复制 | 库里只存 sha256，返回明文等于反存明文 | 明文只在签发响应出现一次（D-34） |
| 前端自己按时间算授权是否生效 | 与 `AgentToken.activeAt` 会漂移，出现“界面说生效但 401” | 服务端下发 `status`（D-34） |
| 为 AI 授权设“只能读不能出价”的档位 | 花的是用户自己的钱，只是让功能不可用；要拦的是时机不是主体 | 两个 scope 都允许，时机由 D-32 兜住（D-34） |
| 把“让 AI 替我出价”留给用户自己跑程序 | 普通用户不会写程序、也不会常开服务器，产品上等于没有这个功能 | 服务端托管代理 + 扫描器，普通人选场次填预算即可（D-36） |
| 托管代理再写一条写 `bids` / `ledger_entries` 的路径 | 资金与幂等规则被复制第二份，与 D-30 直接冲突 | 只算下一手金额，交给 `BidService.placeBid`（D-36） |
| 创建代理时按预算预冻结资金 | 还没开拍就长期占住余额，且事前无法知道真实出价金额 | 创建只校验 `budgetLimit ≤ 可用余额`，出价时才冻结（D-36） |
| 给托管代理开 D-32 博弈时间的例外 | 尾段清场 Agent 的意义正是“不因主体是 AI 而例外” | 无例外，把“尾段 AI 不动”写进创建处与帮助文案（D-36） |
| 为代理重开一条私有 WebSocket 提醒 | 给一个低频、单用户的事件新增推送契约与可见性规则，收益远小于成本 | 前端轮询读模型并对比前后状态，只提醒一次（D-36） |
| 让“开拍”始终由运营手动触发 | 运营不在线时用户与托管代理都只能空等，“到点自动进场”无从谈起 | 预告 `starts_at` + 到点扫描开拍（D-35） |
| 为自动开拍再写一套状态流转 | 开拍规则被复制第二份，手动与自动两条路径迟早分叉 | 扫描器逐条调用已有的 `start(...)`（D-35） |
| 脚本只读 `AUCTION_AGENT_TOKEN`、没配就没入口 | （曾以为这是缺点）实测缺变量时脚本会打印两种设法并退 2，配置问题当场暴露 | 就这样（D-38） |
| 给脚本加 `--token <明文>` 参数 | 明文会进 shell 历史与进程列表，正是原文禁止的 | 只读环境变量（D-38） |
| 没配环境变量时用 `getpass` 交互粘贴兜底 | 长效凭据经键盘/剪贴板进 shell 更容易落进命令历史与录屏，非终端下行为还不一致（CI 可能卡在等输入） | 缺了就报错退 2、并把设法印在报错里（D-38） |
| 把“没配置”当成检查失败退 1 | 与 CI 里的真失败混淆，看日志的人会先去查错代码 | 沿用退出码约定：2 = 前置不满足（D-38） |
| 每个应用实例启动时自己迁移 | 多实例会争 `flyway_schema_history` 的表锁，抢不到的实例启动失败；滚动发布时新实例一改 schema，旧实例还在跑旧代码 | 迁移收敛成一次性步骤，应用只校验（D-39） |
| 关闭自动迁移后应用什么都不做 | 库落后于代码也能起来，错误点（某条 SQL 报字段不存在）离原因很远 | 只校验，不一致就拒绝启动（D-39） |
| 宽松解析 `MIGRATE_ON_START`（`yes`/`no`/`on`） | `=no` 会被当成真而静默迁移，部署者以为关掉了而实际相反 | 只认 `true/false/1/0`，拼错即启动失败（D-39） |
| 顺手把三个扫描器也加上开关 | 单实例下无事可做；多实例真正缺的是“谁负责扫描”的职责划分，开关会造成“已经解决”的错觉 | 后来还是加了（D-40），但把它**限定**成“本实例是否承担”，并同步声明不解决自动分工——这才是它不造成错觉的前提（D-39） |

---

## 维护规则

- 新增决策：追加 `D-N`，必须写明**代价**与**验证结果**；未验证的写 ⏳，不得预填结论。
- 决策被推翻：不删除原条目，改标 ❌ 并说明被什么取代、因为什么证据。
- 这里的每一条若与 [`docs/STATUS.md`](docs/STATUS.md) §4「待决策」冲突，以本文为准。
