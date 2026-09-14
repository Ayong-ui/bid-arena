# 开发期间真实问题记录

> 收录标准：**必须影响交付物的行为或结论**。纯本机环境琐事（终端编码、某个网络拉不到镜像等）不收录——
> 它们换个环境就消失，对判断工程质量没有价值。
> 每条都附当时的原始输出，不接受事后编造。

| 编号 | 问题 | 一句话结论 |
|---|---|---|
| DBG-1 | 迁移中途失败会留下"半应用 schema + 失败记录" | 失败后禁止手工修补 schema，必须能整库从零重跑 |
| DBG-2 | Maven 增量拷贝让应用跑的是旧迁移脚本 | 一键测试与验证统一 `mvn clean verify` |
| DBG-3 | MySQL 8.4 默认认证插件导致 JDBC 连不上 | 连接串是可部署性的一部分，必须进 `.env.example` |
| DBG-4 | 验证命中了残留旧进程，健康检查"假通过" | 压测/结算验证前必须断言服务进程归属 |
| DBG-5 | 测试的结论可能错在断言本身，而不在被测代码 | 断言不得依赖调度；新增测试必须用变异测试确认会红 |
| DBG-6 | “取消”拿到了成交结果，还被标成幂等重放 | 重放只能用在**确实生效过**的操作上，否则是在对调用方说谎 |
| DBG-7 | 赢家冻结为 0 时结算静默放行了一场"没付钱"的成交 | 资金路径上宁可报错停事务，也不要跳过——跳过后的账是平的 |
| DBG-8 | 单类测试绿，全量构建却报"fork 启动失败" | 测试里"优雅停机"的 API 可能 `System.exit`，会杀掉被托管的测试 JVM；提示信息未必是原因 |
| DBG-9 | CORS 预检被测试报成 405，服务端看起来是坏的 | JDK 的 `HttpURLConnection` 默认丢弃 `Origin` / `Access-Control-Request-Method` 等受限头，不等于浏览器行为 |
| DBG-10 | 设了 `SERVER_PORT` 系统属性，服务却没换端口 | `${VAR:default}` 只读环境变量；要覆盖 yml 得用 yml 里真实存在的键（`server.port`），并加断言 |
| DBG-11 | 显式声明了 Jackson，请求体却被 snack3 解析成 `Format error` | 插件式框架里"声明了"不等于"生效了"，要让它成为 classpath 上唯一的候选 |
| DBG-12 | 库口令出现在 `target/surefire-reports/*.xml` 里 | 系统属性会进测试报告，且 fork 复用会污染同一 JVM 之后的测试类；用完必须清掉 |
| DBG-13 | 同一 JVM 里第二次启动 Solon，服务绑在旧端口上 | 进程级单例的框架里“重启”并不成立，测试基座宁可用更简单的一次生命周期 |
| DBG-14 | `Map.copyOf` 不接受 null，“无人出价”把结算变成 NPE | 收窄值域的 API 把“忘判空”升级成运行中崩溃，要让“缺席”成为默认而非特例 |
| DBG-15 | `Future` 上没有 `whenComplete`，异步发送失败成观测盲区 | 必须可观测的事实要同步异常与异步失败各留一条路径 |
| DBG-16 | JUnit 不给 `@BeforeAll` 注入 `ExtensionContext` | 需要上下文的钩子用扩展回调，注解方法只负责不依赖上下文的事 |
| DBG-17 | `seq` 无缺口断言用 `-1` 当初值，第一次比较就失败 | “看起来对的数据被判违规”几乎总是断言自己写错了 |
| DBG-18 | 设计文档写的依赖规则与代码里的包结构对不上 | 把规则写成测试之前先照实现状检查一遍 |
| DBG-19 | `noClasses().should(自定义条件)` 被 ArchUnit 反转，规则永远不会红 | 否定式断言必须先用一个人造违规证明它会红 |
| DBG-20 | 全量跑时偶发一次 WS 握手超时 | 共享基座的时间预算按最坏负载给，用截止时间重试而不是一刀切放大 |
| DBG-21 | 变异 F14 存活——测试其实没有验证幂等键复用 | 观察点只在成功路径上，就观察不到“失败与成功用的是同一个键” |
| DBG-22 | `Solon.start` 是进程级单例，第二次不会开出第二个监听器 | “调用一次启动”不等于“起了一个监听器”，要数端口这类外部事实 |
| DBG-23 | 提前拒绝带请求体的请求会污染 keep-alive 上的下一个请求 | 早拒绝要配一个动作：把请求体读尽，或显式关闭连接 |
| DBG-24 | JWT 的 base64url 末位字符含填充位，改它等于没改 | 构造负例要改**参与解码的位**，不是任意一个字符 |
| DBG-25 | 源码改了、测试却在跑旧字节码 | 先比 `src` 与 `target` 的 mtime 再怀疑逻辑；验证工具本身也要被验证 |
| DBG-26 | E2E 脚本自己的断言用了一枚早就被吊销的 Token | “测试红了”可能是测试错了；顺着数据往下看一层 |
| DBG-27 | E2E 断言把不变量想得比契约更强（邻价并发、狙击上限） | 写并发断言前先问：这条不变量的事务边界在哪、锁住了什么 |
| DBG-28 | 幂等键含 `user_id`——换个用户复用同一 `requestId` 不是重放 | 幂等是给**同一调用方**的重试去重，不是全局去重 |
| DBG-29 | 界面还在说 Agent API“尚未实现”，实际 P5 已经交付 | 界面文案也是关于系统的断言，交付里程碑时要 `grep` 一遍负向断言 |
| DBG-30 | 预告开拍“到点没动”——数据库容器的时钟比开发机慢 3 分钟 | 三个候选时钟里，业务判定一律以数据库时间为准（D-5） |
| DBG-31 | E2E 连跑几次后连环 `INSUFFICIENT_BALANCE`，最后以无关的 WS 超时收场 | 脚本真的会花钱：缺数据要在阶段 0 停下并给出恢复命令 |

---

## DBG-1：迁移中途失败留下半应用 schema，怎么恢复才不会破坏可复现性

**现象**

首次启动应用，Solon 启动失败。事后检查数据库发现：V2 里**排在出错语句之前的变更已经落库**，而出错的那条没有，同时 `flyway_schema_history` 多出一条失败记录。

```
SQL State  : 42000
Error Code : 1064
Message    : You have an error in your SQL syntax; ... near 'COMMENT '首次结果的成交价快照，用于幂等重放', ADD COLUMN resu'
Location   : db/migration/V2__identity_wallet_ledger.sql, Line : 108
```

```
installed_rank | version | description            | success
1              | 1       | auction schema         | 1
2              | 2       | identity wallet ledger | 0        <- 失败记录
```

**定位**

应用层原因是那条 `ALTER TABLE` 的语法错误（`FIRST / AFTER col` 在 MySQL 里必须排在 `COMMENT` 等属性之后，我写反了顺序）。

但真正需要决策的不是语法，而是**失败后的恢复方式**。MySQL 的 DDL 会隐式提交，所以迁移**不是原子的**——`V2` 处于"一半生效"的状态。此时有三条路：

| 方案 | 问题 |
|---|---|
| `flyway repair` 抹掉失败记录再重跑 | 已经生效的那半部分不会回滚，重跑时 `CREATE TABLE users` 会撞名；就算手工绕过，本机 schema 已经和"从零跑一遍"的结果不同 |
| 手工补上缺的列 | 同上，而且此后没人知道这个库到底跑过哪些语句 |
| **整库重建，让迁移从零重跑** | 需要丢弃数据（此时是空库，代价为零） |

**修复**

选择整库重建，并把错误语法一并修正：

```sql
DROP DATABASE IF EXISTS bid_arena;
CREATE DATABASE bid_arena CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

同时给 Flyway 显式指定脚本编码（`StandardCharsets.UTF_8`），不再依赖平台默认值。

**验证**

重建后由应用启动时重新执行，V1、V2 均成功：

```
| 1 | 1 | auction schema         | SQL | 1 |
| 2 | 2 | identity wallet ledger | SQL | 1 |
```

顺带拿到了更有价值的证据：**从零建库到可用 schema 的路径是通的**，而不是"在我这台机器上恰好能跑"。中文种子数据也做了往返校验（`title = '演示拍品 · 复古机械键盘'` 返回 1）。

**工程结论**

迁移脚本的正确性标准是"能从一个空库重跑到最新"，因此：失败后**不允许手工修补 schema**，也不允许用 `repair` 掩盖过去；要么修正脚本后重建，要么写一个真正的补偿迁移。后续加 `V3+` 时同理——只在已有数据的库上验证是不够的，空库路径必须同样被验证。

---

## DBG-2：改完迁移脚本，应用跑的还是旧脚本

**现象**

修好 DBG-1 的语法后重新构建启动，报出的错误**和修改前一模一样**，错误信息里的 SQL 片段仍是旧顺序。

**定位**

对比源文件与被执行的副本，内容不一致：

```
9711 字节  17:52:45  db/migration/V2__identity_wallet_ledger.sql                 <- 已修改
9452 字节  17:52:06  target/classes/db/migration/V2__identity_wallet_ledger.sql  <- 实际执行的是这份
```

迁移脚本按约定放在仓库根目录 `db/migration/`（评审直接可见），通过 `pom.xml` 的 `<resources>` 映射进 classpath。`mvn process-resources` 判定目标为最新而跳过了拷贝；单独重跑该 goal 时才正常拷贝：

```
[INFO] --- resources:3.4.0:resources (default-resources) @ bid-arena-core ---
[INFO] Copying 2 resources from db\migration to target\classes\db\migration
```

这是增量构建的判定问题，不是配置错误。

**修复**

- 开发循环里改完迁移必须让资源重新落盘；本轮之后改成用 `diff` 确认副本与源一致，不再凭"我改过了"下结论。
- 把"迁移脚本副本是旧的"这一风险从流程上消除：**面向评审的一键测试命令使用 `mvn clean verify`**。

**验证**

重跑后副本与源一致（9711 字节），迁移执行成功。

**工程结论**

只要资源来自 `src/main/resources` 之外的目录，就必须假定增量拷贝可能失效。这不是本机偶发问题：评审机器上同样可能出现"脚本已改但副本是旧的"，从而产生**不可复现的假失败**。用 `clean` 换确定性是划算的。

---

## DBG-3：MySQL 8.4 的默认认证插件让 JDBC 首次连接失败

**现象**

建库容器起来后执行体检语句报错：

```
ERROR 1193 (HY000): Unknown system variable 'default_authentication_plugin'
```

**定位**

两条独立的事实：

1. MySQL 8.4 **移除了** `default_authentication_plugin`（由 `authentication_policy` 取代，`mysql_native_password` 插件也不再内置）。体检语句是按 5.7/8.0 的习惯写的。
2. 更关键的是它对**运行**的影响：8.4 默认认证插件是 `caching_sha2_password`。在**非 TLS** 连接下，驱动首次认证需要向服务端索取公钥，否则直接失败。

**修复**

连接串必须带 `allowPublicKeyRetrieval=true`；同时把会话时区固定为 UTC，避免 `TIMESTAMP` 的读写与 `serverTime` 序列化结果随宿主机时区漂移：

```
jdbc:mysql://host:3307/bid_arena
  ?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
  &allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8
```

两项都写进 `.env.example` 并注明原因，否则换一台机器部署必然复现。

**验证**

```
v: 8.4.9   cs: utf8mb4   coll: utf8mb4_0900_ai_ci
iso: REPEATABLE-READ   auth_policy: *,,   lock_wait: 50
```

后端成功连库并完成迁移；健康检查返回的时间带 `Z`，确认会话时区已生效。

**工程结论**

"能连上数据库"不是环境运气，而是配置的一部分。连接串里每一项非常规参数都要有原因说明，否则它会在下一次部署时才暴露。另外此处的 `REPEATABLE-READ` 需要确认不影响正确性：本方案的并发控制依赖 `SELECT ... FOR UPDATE`（当前读）而非快照读，因此该隔离级别无影响——这一点在写并发代码前就要确认，不能等到出问题再怀疑。

---

## DBG-4：健康检查"假通过"——响应来自残留的旧进程

**现象**

连续两轮启动验证：第二轮日志里明确报了端口被占，但紧接着的健康检查**依然返回 200**：

```
ERROR [main] org.noear.solon.Solon - Solon start failed: AppContext start failed
  Caused by: java.net.BindException: Address already in use: bind

$ curl -s http://127.0.0.1:8080/api/v1/health
{"status":"UP","time":"2026-09-12T09:56:34Z","service":"bid-arena"}
```

只看这一行，结论会是"服务正常运行"——完全错误。

**定位**

```
TCP 0.0.0.0:8080   LISTENING  31324
TCP 0.0.0.0:18080  LISTENING  31324
$ jps -l
31324 com.bidarena.Application
```

此前几轮验证用 `kill $APP_PID` 收尾。在 Windows 的 Git Bash 下 `kill` 会打印成功并返回 0，但 JVM 并未退出（MSYS 的 PID 映射与 Windows 原生进程号不是一回事），实例就一轮轮累积下来，旧进程继续占用端口。

**为什么这条比前三条危险**

前三条都会以"启动失败"或"报错"的形式当场暴露；这一条**伪装成成功**。健康检查返回的 `serverTime` 是当次请求算出来的当前时间，新旧实例的响应长得一模一样，肉眼无法区分。若不发现，后续所有"接口调通了""迁移生效了"的结论都可能建立在旧代码上——对一个以并发正确性为核心的项目，这是最致命的验证缺陷。

**修复**

① 收尾改用 Windows 原生方式结束进程：

```bash
NEWPID=$(jps -l | grep com.bidarena.Application | awk '{print $1}')
taskkill //F //PID $NEWPID
```

② 在验证脚本里加**进程归属断言**，把"服务能响应"升级为"我的代码能响应"：

```bash
for P in 8080 18080; do
  OWNER=$(netstat -ano | grep ":$P " | grep LISTENING | awk '{print $5}' | head -1)
  [ "$OWNER" = "$NEWPID" ] || echo "端口 $P 属于 PID $OWNER，不是本次启动的 $NEWPID"
done
```

**验证**

加上断言后重跑，两个端口都属于本次启动的进程：

```
应用 PID = 19852
  端口 8080  -> PID 19852
  端口 18081 -> PID 19852
```

**工程结论**

后续并发出价与结算验证必须沿用这个断言。并发测试最容易出现的假象是"20 个请求都成功了"，而如果服务根本不是本次构建的版本，这个漂亮结论毫无意义。

---

## DBG-5：测试给出了结论，但结论来自错误的断言

**现象**

真实 MySQL 集成测试首轮运行：16 个用例中 2 个失败。两个失败**都是断言写错**，而不是被测代码有问题。

```
BidServiceTest.rebidBySameUserFreezesOnlyTheDelta
  可用余额应只减少差额 20 ==> expected: <890> but was: <870>

BidConcurrencyTest.increasingLadderEndsAtHighestBid
  未成功的出价必须全部是低于当前价 ==> expected: <20> but was: <5>
```

**定位（两个独立原因）**

**① 断言了一个依赖线程调度的量。** 测试让 20 人并发出递增价格（110…300），并断言 20 次全部被接受。推理是“每次出价的金额总高于自己出价时的当前价”——但这个推理默认了“按金额从小到大被处理”。实际上谁能先拿到拍卖行锁由调度决定：若 300 先执行，后面的 119、129 就都该被拒。

接受条数因此**不是确定量**。可确定的是另一组量：没有任何出价能超过 300，所以 300 无论何时执行都必然被接受，且此后无人能再抬价——**最终价、领先者、本场冻结额**都与调度无关。改断言这四项后测试稳定成立。

**② 把“冻结总额”和“本次新增冻结”混为一谈。** 用户余额 1000，先出价 110 再出价 130，可用余额应为 1000−130=870。写成 890 是因为在同一句断言里把“冻结额跟上新出价”和“本次只动差额 20”搅在了一起。恰恰就是原文那条余额口径的边界，自己先在测试里弄错了一次。

**还遇到一个变种：断言在两侧都为空时恒真。**

```bash
OWNER=$(netstat -ano | grep ":8080 " | ... )
[ "$OWNER" = "$NEWPID" ] && echo "✓ 端口归属断言通过"
```

应用因构建产物缺失而根本没有启动，两个变量都是空字符串，断言“通过”了。这是 DBG-4 那个断言的退化形态：**取不到值时的相等比较会伪装成验证成功**。修复是给断言加非空前置条件，取值失败时直接报错而不是进入比较。

**修复**

- 并发断言改为只针对不依赖调度的量，并在注释里写明为什么不断言条数。
- 端口归属断言加非空前置条件，取不到 PID 时直接失败。
- 把“绿了就算数”换成**变异测试**：故意拆掉一项保障，看测试会不会红。

**验证（变异测试）**

| 变异 | 实测结果 |
|---|---|
| 拿掉 `lockAuction` 的 `FOR UPDATE` | 20 次同额出价中 14 次既非成功也非规则拒绝，而是内部错误；两用例失败（断言 19 实际 6、断言 20 实际 5） |
| 拿掉幂等重放短路 | 出价记录数与成功幂等记录数不再相等；两用例失败 |
| 还原后复跑 | 16 个用例全绿 |

**工程结论**

“测试全绿”只能证明“测试没发现错误”，不能证明“测试能发现错误”。一个永不失败的断言和一个永远失败的断言同样没用。因此：

- 并发场景下，只能断言**与调度无关的量**（最终状态、不变式），不能断言“几条成功”“谁先成功”。
- 新增或修改关键测试后，必须拆掉一项它声称保护的保障并确认它会红；这是 [`CONTRIBUTING.md` §4](CONTRIBUTING.md) 的完成定义之一。
- 断言本身也是代码，同样会写错。失败时先问“被测代码错还是断言错”，不要为了变绿而改代码。

---

## DBG-6：取消返回了成交结果，而且还被标成"幂等重放"

**现象**

写并发结算测试时，让两个线程同时对同一场已到期的拍卖分别发起结算与取消。看到日志里出现：

```
INFO SettlementService - 拍卖已按 TIMEOUT 结束，返回已有结果 auction=auc_c4 winner=u_1
```

而这一行来自**取消请求**。更麻烦的是返回值：

```
SettlementResult(auctionId=auc_c4, winnerId=u_1, finalPrice=120, reason=TIMEOUT, replay=true)
```

调用方（管理员发起的取消）看到 `replay=true`，字面上被告知“你的取消已生效”，而实际上发生的是**成交**，钱已经扣了。

**定位**

结算与取消共用一条终局路径，我写的幂等短路是“只要 `settlements` 里已有记录就返回它”：

```java
SettlementRow existing = settlements.lock(conn, auctionId);
if (existing != null) {
    return new SettlementResult(..., existing.reason(), true, now);   // ← 没区分入口
}
```

对到期结算来说这是对的：重复触发本就该返回同一结论。但 `replay = true` 不是“没有变化”的描述，而是**一句断言**——“这个操作已经生效过”。用在取消上就不成立了。

顺带发现同一处的另一半：`SettlementServiceTest.cannotCancelAfterSettlement` 期望 `INVALID_STATE`，而当时的实现根本走不到那个分支，它会先被重放短路返回。**测试预期与实际行为不一致，只是刚好还没跑到。**

**修复**

重放只在“同一种结束方式已经发生过”时生效：

```java
boolean sameKind = cancelRequested == (existing.reason() == SettlementReason.CANCELLED);
if (sameKind) { return 已有结果; }
throw new BizException(ErrorCode.INVALID_STATE, ...);
```

即：取消命中 `CANCELLED` 才重放；取消命中 `TIMEOUT/NO_BIDS` 报状态错。反向同理（已取消的拍卖不能再被结算）。

**验证**

- `SettlementServiceTest.cannotCancelAfterSettlement`、`cannotSettleCancelledAuction` 转为绿。
- `SettlementConcurrencyTest.simultaneousSettlementAndCancellationOnlyOneWins`：不断言“谁赢”（那是调度决定的），而是断言两种可能的结局都自洽——`FINISHED` 则扣款且成交价等于当前价，`CANCELLED` 则分文不扣；并断言败者只会得到 `INVALID_STATE`，不会得到“静默成功”。

**工程结论**

幂等返回值的语义必须跟**被请求的操作**对齐，不能只跟“数据有没有变”对齐。“没有变化”可以同时对应“已经做过”和“因为别的原因早就结束了”，而客户端会依赖这两者的区别。

---

## DBG-7：赢家冻结为 0 时，结算静默放行了一场“没付钱”的成交

**现象**

写“单场结算失败不阻塞整批”的用例时，需要人为制造一场结算失败。我的做法是把领先者的按场冻结清零（模拟历史缺陷或人工改库），预期结算会因为“冻结额与成交价不一致”而报错。

但第一版实现根本没走到那个检查——初版 `moveMoney` 是“跳过冻结为 0 的参与者”：

```java
for (entry : freezes) {
    long frozen = entry.getValue();
    if (frozen == 0) { continue; }          // ← 赢家也被跳过了
    if (userId.equals(winnerId)) { ...扣款... }
}
```

结果：拍卖被置为 `FINISHED`，成交记录写的是 `winner=u_1, final_price=120`，而 `u_1` 的总额一分没少——**东西拿走了，钱没付**。

**为什么这个缺陷特别危险**

事后看账，所有不变量都是平的：

- 成交记录唯一 ✓（`settle_rows = 1`）
- SETTLE 流水金额等于成交价 ✓
- 结算后本场冻结为 0 ✓
- 钱包可用额非负 ✓

因为扣款路径与流水写入在同一分支里，跳过它就两边一起跳过了，**账面上没有留下任何异常**。它只会表现为“平台的账少了 120”，而没有任何一条查询会报警。

**修复**

把“赢家必须扣款”变成一个显式前置条件，而不是循环里的一个分支：

```java
long winnerFrozen = ...从 freezes 里查 winnerId...  // 没这条参与记录则为 -1
if (winnerId != null && winnerFrozen != finalPrice) {
    throw new BizException(ErrorCode.INTERNAL_ERROR,
            "赢家冻结额与成交价不一致，拒绝结算以免扣错金额或漏扣", ...);
}
```

注意是 `!=` 而不是 `<`：多冻结也要报错。冻结多了说明前面某一步已经错了，此时按成交价扣款会把差额静默吞掉。

**验证（变异测试）**

按 [`CONTRIBUTING.md` §4](CONTRIBUTING.md) 的完成定义，对结算的四项保障各拆一次，确认测试会红：

| 变异 | 预期会红的保障 | 实测结果 |
|---|---|---|
| 去掉结算事务内的幂等短路 | 重复触发只结算一次 | ❌ 4 个用例失败（并发 3 + 功能 1） |
| 把赢家当普通出价者处理（只释放不扣款） | 赢家必须被扣款 | ❌ 11 个用例失败 |
| `settleWinner` 结算时钱包冻结不减 | 两层冻结必须一致 | ❌ 11 个用例失败（含 4 个内部错误） |
| 把一致性检查从 `!=` 放宽成 `>`（少扣也放行） | 少扣必须被拒绝 | ❌ 1 个用例失败，正是 `oneFailingAuctionDoesNotBlockTheRestOfTheBatch` |
| 全部还原后复跑 | — | ✅ 32 个用例全绿 |

第四个变异只被一个用例杀死，这是合理的：它保护的是一条很窄的守卫，而那个用例正是为它写的；能杀掉它，说明这条守卫不是“写了但没人测”。

**工程结论**

资金路径上，“跳过”比“报错”危险得多。跳过留下的是一笔**在对账上完全平掉的错账**，报错留下的是一个能被扫描器重试的待办事项。因此结算里每一处“不满足条件就跳过”都要反问一句：跳过之后，这个用户还欠钱吗？
## DBG-8：测试 fork 被 `Solon.stop()` 里的 `System.exit` 杀掉，还被一个假的 Class-Path 报错带偏

**现象**

P2 的 HTTP 集成测试单独跑是绿的：

```
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- in com.bidarena.api.HttpApiIntegrationTest
```

但按 `README.md` 的一键命令 `mvn clean verify` 跑全量时构建失败，而且失败点看起来跟测试内容毫无关系：

```
[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
[ERROR] Command was cmd.exe /X /C "java -Dfile.encoding=UTF-8 -jar ...\surefirebooter-20260913151155301_3.jar ..."
[ERROR] Error occurred in starting fork, check output in log
[ERROR] Process Exit Code: 1
[ERROR] Crashed tests: com.bidarena.auction.application.BidConcurrencyTest
```

`HttpApiIntegrationTest` 的 19 个用例**全部通过**，然后整个 fork 没了，而 surefire 把下一个待测类（`BidConcurrencyTest`）标成"crashed"。

**定位**

`target/surefire-reports/*.dumpstream` 里有这样三行：

```
Boot Manifest-JAR contains absolute paths in classpath 'D:\maven_repository\...\surefire-booter-3.5.2.jar'
Hint: <argLine>-Djdk.net.URLClassPath.disableClassPathURLCheck=true</argLine>
'other' has different root
```

这是一个**非常像**"Windows 盘符/临时目录"的环境问题（`java.io.tmpdir` 在 `C:`，依赖仓库在 `D:`），我照着提示把 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` 加进 surefire 的 `argLine`，重跑——**一模一样的报错**。提示是假的线索。

真正的原因在测试的清理代码里：

```java
@AfterAll
static void stopServer() {
    Solon.stop();     // ← 这里
}
```

`Solon.stop()` 的反编译结果（`javap -c org.noear.solon.Solon`）：

```java
public static void stop()      { ... stop(cfg.stopDelay()); }          // 转发
public static void stop(int)   { new Thread(runnable).start(); }       // ← 异步线程
private static void stop0(boolean block, int delay, int code) {
    ...  // pre-stop / delay / stop 三阶段
    if (block) { System.exit(code); }                                  // ← 退出 JVM
}
```

`stop()` 走的是 `stop0(true, delay, 0)`：停止流程被丢到一个新线程里，**最后调用 `System.exit(0)`**。在被 surefire 托管的测试 JVM 里，这等于在测试跑完后自杀：JVM 直接退出，来不及跟 surefire 握手，于是"terminated without properly saying goodbye"；surefire 以为这个 fork 崩了，就重启一个 fork 去跑剩下的类，而重启过程中才吐出那个盘符相关的报错——它是**后果**，不是原因。

单独跑一个测试类时问题被掩盖了：那个类就是最后一个，JVM 退出后再没有类要跑，surefire 没有"重启 fork"的动作，构建就绿了。**只有全量运行才会暴露。**

**修复**

用同一个停止路径，但不阻塞、不退出 JVM：

```java
@AfterAll
static void stopServer() {
    // stopBlock(block=false) → stop0(false, 0, 1)：不 System.exit，其余流程不变
    Solon.stopBlock(false, 0);
}
```

**验证**

- `mvn -o clean verify`（`README.md` 一键命令）→ `Tests run: 63, Failures: 0, Errors: 0` + `BUILD SUCCESS`，不再出现 fork 报错。
- 顺手确认了这确实与 `argLine` 无关：把那个 JDK 开关从 `pom.xml` 里撤掉，构建仍然全绿，因此不留这条无依据的配置。

**工程结论**

"测试都过了但构建失败"是一个信号：问题出在测试**周围**，不在测试里。库代码提供的"优雅停机"通常面向进程生命周期（它有权结束进程），把这种 API 直接用在测试清理里，就是把进程控制权交了出去。凡是测试里要"关掉什么东西"，先确认它会不会 `System.exit`。

---

## DBG-9：CORS 预检在测试里报 405——JDK 的 `HttpURLConnection` 默认丢弃 `Origin` 这类请求头

**现象**

`corsFollowsAllowList` 断言浏览器预检（`OPTIONS` + `Access-Control-Request-Method: POST` + `Origin`）返回 204：

```
java.lang.AssertionError: ... expected: <204> but was: <405>
{"code":"METHOD_NOT_ALLOWED","data":{"method":"OPTIONS","path":"/api/v1/admin/auctions"}, ...}
```

405 的封套里 `"method":"OPTIONS"`，说明请求**确实**是 OPTIONS 到达了服务器；而 `CorsFilter` 的预检判定是：

```java
boolean preflight = "OPTIONS".equals(ctx.method())
        && ctx.header("Access-Control-Request-Method") != null;
```

也就是说，服务端没看到那个请求头。

**定位**

先怀疑大小写：`ctx.header()` 底层是 `MultiMap`，用 `javap` 看它的构造：

```java
public class MultiMap<T> {
  protected final IgnoreCaseMap<KeyValues<T>> innerMap;   // ← 大小写不敏感
```

排除。再回到客户端侧：测试用的是 Solon 的 `HttpUtils`，它的默认实现是 JDK 的 `HttpURLConnection`。而 `sun.net.www.protocol.http.HttpURLConnection` 有一个**受限头集合**，默认会**静默丢弃**下列请求头（不报错、不警告）：

```
Access-Control-Request-Headers, Access-Control-Request-Method,
Connection, Content-Length, Content-Transfer-Encoding,
Host, Keep-Alive, Origin, Trailer, Transfer-Encoding, Upgrade, Via
```

`Origin` 和 `Access-Control-Request-Method` 恰好都在里面——**CORS 预检的标识性请求头被客户端自己吃掉了**。所以请求变成了"既没有 Origin、也没有 Request-Method 的普通 OPTIONS"，被路由当普通请求拒成 405。

**修复**

在测试类初始化之前打开这个开关（必须在 `HttpURLConnection` 类初始化之前设置，所以放在 `static` 块而不是 `@BeforeAll`）：

```java
static {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
}
```

**验证**

- `corsFollowsAllowList`：白名单内 → 预检 204 且回显 `Access-Control-Allow-Origin: http://localhost:5173`；白名单外 → 不回显 `Allow-Origin`。两条断言都通过。
- 这是**测试侧的坑**，服务端 `CorsFilter` 一行都没改：真实浏览器不会丢弃这些头（否则 CORS 根本不会存在），所以不要为了迁就这个测试去改生产代码。

**工程结论**

用 JDK 自带客户端"模拟浏览器"时，它并不是浏览器：受限头、重定向策略、Cookie 策略都不同。写这类测试前先确认客户端会发出什么，否则会把"测试工具的限制"误判成"被测代码的缺陷"——这次差点就跑去改 `CorsFilter` 了。

---

## DBG-10：`SERVER_PORT` 系统属性改了没用——`${...}` 占位符只认环境变量

**现象**

HTTP 集成测试需要让服务监听一个随机空闲端口（避免与本机 8080 上已跑着的实例冲突）。于是照常设系统属性：

```java
System.setProperty("SERVER_PORT", String.valueOf(port));
Application.main(new String[0]);
assertEquals(port, Solon.cfg().serverPort());
```

结果启动失败，端口也不是期望值；再后来换了个更靠上的随机端口，又撞到 `IllegalArgumentException: port out of range:75364`。

**定位**

两步。

第一步，**先搞清端口是从哪条路进来的**。`src/main/resources/app.yml` 里只有一行配置：

```yaml
server:
  port: ${SERVER_PORT:8080}
```

`javap` 看 `SolonProps` 的加载过程：

- `loadInit(URL, Properties)`：把 JVM 系统属性快照逐条覆盖到**已加载的 yml 中同名键**上。yml 里的键是 `server.port`，而系统属性叫 `SERVER_PORT`——**不是同一个键**，所以覆盖不到。
- `${...}` 占位符由 `Props.getByTmpl` 解析，它只查 props 与本进程的**环境变量**（`System.getenv`），**不查系统属性**。

两条路都到不了系统属性。同一份代码在测试里能通过的唯一原因是测试是用环境变量传的。

第二步，"空闲端口"的探测方式也错了：`new ServerSocket(0)` 拿到的是 Windows 的临时端口段（49152–65535），而 Solon 的 WebSocket 插件默认监听 **`server.port + 10000`**，于是 `75364` 直接越界。

**修复**

- 端口用**系统属性 `server.port`** 覆盖（它确实是 yml 里存在的键）：`System.setProperty("server.port", ...)`；
- 空闲端口改成在 `40000–49000` 区间随机探测，探测成功再交给服务，保证 `+10000` 不越界；
- 启动后加一条断言，把"覆盖是否真的生效"变成一个可执行的事实，而不是假设：

```java
assertEquals(port, Solon.cfg().serverPort(), "服务没有按测试指定的端口启动，后续请求会打到别处");
```

**验证**

- `assertEquals(port, Solon.cfg().serverPort())` 通过，随后 19 个用例全部打到这个端口上。
- 反证：把端口恢复成走 `SERVER_PORT` 系统属性，断言立刻失败——说明这条断言抓得住"配置没生效"，不是摆设。

**工程结论**

`${VAR:default}` 这种写法读的是**环境变量**，而"设个同名系统属性"读的是**另一条通路**。配置项有多个来源时，必须为"优先级"写一条断言；否则测试会莫名其妙地连到另一个实例上，而所有用例还是绿的（那才是最坏的情况）。这条与 `DEBUG_LOG.md` DBG-4（残留旧进程导致健康检查假通过）是同一类风险的两个入口。

---

## DBG-11：Jackson 声明了却没生效——请求体全被 snack3 解析成 `Format error`

**现象**

HTTP 集成测试第一次跑起来，19 个用例里 16 个失败，而失败长这样：

```
org.noear.snack.exception.SnackException: Format error!
    at org.noear.snack.core.utils.IOUtil ...
    at org.noear.snack.core.Serializer ... SnackStringSerializer.deserializeFromBody
```

每个带 JSON 请求体的接口（登录、建拍卖、出价）都返回 500。项目里显式声明的是 Jackson（`solon-serialization-jackson`），代码里没有任何一处用到 snack3。

**定位**

`mvn dependency:tree` 看到两个序列化插件同时在场：

```
+- org.noear:solon-web:3.0.1
|  \- org.noear:solon-serialization-snack3:3.0.1      ← 传递进来
+- org.noear:solon-serialization-jackson:3.0.1        ← 显式声明
```

Solon 的序列化插件是按 classpath 扫描注册的，两个都注册时**谁生效由加载顺序决定**。"显式声明了 Jackson"只能说明它在 classpath 上，**不能说明它被选中了**。

**修复**

在 `pom.xml` 里把 `solon-web` 传递进来的 snack3 排掉，让 classpath 上只剩一个序列化器：

```xml
<exclusions>
  <exclusion>
    <groupId>org.noear</groupId>
    <artifactId>solon-serialization-snack3</artifactId>
  </exclusion>
</exclusions>
```

**验证**

排除后重跑，错误从 `SnackException` 变成了 Jackson 的语法错误：

```
com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'Admin123456': ...
```

这条新错误说明"解析器确实换人了"——它是被测测试自己拼错了 JSON（口令值没加引号），与序列化器无关，修掉测试助手后 19/19 全绿，全量 63/63 全绿。

**工程结论**

"我用的是 X" 与 "X 生效了" 是两件事，在插件式框架里尤其如此。可验证的收敛方式是**让 classpath 上只留一个候选**，而不是靠文档声称用了哪个；否则依赖树一变，序列化格式会静默漂移，而且往往只在解析报错时才暴露。
## DBG-12：库口令被 surefire 写进了构建产物——系统属性会进测试报告

**现象**

提交前按 `CONTRIBUTING.md` §5 扫一遍"有没有提交口令"，仓库里干净，但顺手扫 `target/` 时发现：

```
$ grep -rl "$DB_PASSWORD" target/
target/surefire-reports/TEST-com.bidarena.api.HttpApiIntegrationTest.xml
target/surefire-reports/TEST-com.bidarena.auction.application.BidConcurrencyTest.xml
target/surefire-reports/TEST-com.bidarena.auction.application.BidServiceTest.xml
target/surefire-reports/TEST-com.bidarena.auction.application.SettlementConcurrencyTest.xml
target/surefire-reports/TEST-com.bidarena.auction.application.SettlementServiceTest.xml

$ grep -A2 'name="DB_PASSWORD"' target/surefire-reports/TEST-com.bidarena.auction.application.BidServiceTest.xml
<property name="DB_PASSWORD" value="<真实开发/测试库口令>"/>
```

`BidServiceTest` 自己从不设这个属性，它是**被牵连**的。

**定位**

两个事实叠加：

1. HTTP 集成测试为了让服务读到测试库，用系统属性传配置（D-17）：`System.setProperty("DB_PASSWORD", require("BID_ARENA_TEST_DB_PASSWORD"))`；
2. surefire 会把 fork 的系统属性快照写进每个测试类的 XML 报告，而 surefire 默认**复用同一个 fork**——属性一旦被某个测试类设上，同一个 JVM 之后所有类的报告都会带上它。`HttpApiIntegrationTest` 按字母序先跑，于是污染了后面所有报告。

`target/` 在 `.gitignore` 里，所以它不会进仓库；但"没进仓库"不等于没问题：报告是**给别人看证据**用的文件，会出现在本机、CI 日志归档、以及录屏画面上。

**修复**

在 HTTP 测试的 `@AfterAll` 里把带凭证的系统属性清掉：

```java
System.clearProperty("DB_URL");
System.clearProperty("DB_USER");
System.clearProperty("DB_PASSWORD");
System.clearProperty("JWT_SECRET");
```

**验证**

- 修复后重跑 `mvn -o clean verify`：全量 63/63 绿（清理不影响其它测试类——它们读的是环境变量 `BID_ARENA_TEST_DB_*`，不是系统属性）。
- 再扫一遍：`grep -rl "<口令>" target/` 与 `grep -rl "<JWT_SECRET>" target/` 都无匹配。

**工程结论**

"这个文件不会提交"是一句降低标准的自我安慰：只要口令出现过，它就会出现在备份、截图、CI 归档里。把凭证交给进程时，也要想清楚谁会把它**记下来**——这次记下来的是测试框架，而测试框架的职责恰好就是"留下证据"。

## DBG-13：同一 JVM 里第二次启动 Solon，服务绑在了旧端口上

**现象**

P3 给测试加了一组 WebSocket 用例（`WsIntegrationTest`），和已有的 HTTP 用例共用一个"起服务"的基座。单独跑任一个类都绿，全量跑时第二个类直接报：

```
java.lang.IllegalStateException: 服务在 10 秒内没有在端口 40244 上就绪
	at com.bidarena.support.ApiTestHarness.awaitHealthy(ApiTestHarness.java:345)
	at com.bidarena.support.ApiTestHarness.startServerOnce(ApiTestHarness.java:137)
	at com.bidarena.support.ApiTestHarness.bootForClass(ApiTestHarness.java:86)
```

**定位**

第一版基座用"引用计数"决定何时起停：第一个用它的测试类启动、最后一个用完时停掉。于是全量跑的顺序是"起 → 停 → 再起"。看第二次启动的日志：

```
Running com.bidarena.api.WsIntegrationTest
App: Start loading
Started ServerConnector@{HTTP/1.1}{http://localhost:46267}     ← 上一轮的 HTTP 端口
Connector:main: websocket: Started ServerConnector{...}{ws://localhost:40541}   ← 上一轮的 WS 端口
App: End loading elapsed=133ms
```

而这一次 `freePort()` 给测试记录的新 HTTP 端口是 **40244**。也就是说：**服务起来了，但绑在上一轮的端口上**，`awaitHealthy()` 轮询新端口自然一直连不上。

原因是 Solon 的配置是进程级单例：第一次 `Solon.start` 时 `Solon.cfg()` 已经把 yml 与环境读进内存，第二次 `System.setProperty("server.port", ...)` 就不再起作用（这正是 D-17 那条"系统属性覆盖 yml 真实键"的另一面：覆盖只在**首次加载**时发生）。

还有一个次要教训：基座里本来就有 `assertEquals(port, Solon.cfg().serverPort())` 这条"配置生效"断言，但它排在 `awaitHealthy()` **之后**，于是先等到超时，断言没机会说话。把断言放得离启动越近越好。

**修复**

不再"停掉再起"，改成**一个测试 JVM 只起一个实例**，停服挂在 JUnit 的根上下文存储上（`ExtensionContext.Store.CloseableResource`，整轮测试结束时关闭）：

```java
@RegisterExtension
public static final TestServerExtension SERVER_EXTENSION = new TestServerExtension();

public static final class TestServerExtension implements BeforeAllCallback {
    @Override public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(NAMESPACE)
               .getOrComputeIfAbsent(SERVER_KEY, key -> new ServerResource(), ServerResource.class);
    }
}
```

`ServerResource.close()` 里只做 `Solon.stopBlock(false, 0)`（不能用 `Solon.stop()`：它最后会 `System.exit`，见 DBG-8）。

**验证**

- 全量 116/116 绿；启动日志里 `App: Start loading` 只出现 **1 次**，结尾有 `App: End stop`，fork 正常退出（没有因为服务线程活着而卡住）。
- 顺带修好的：HTTP 与 WS 用例现在跑在**同一个实例**上，"WS 与 HTTP 读写同一张对象图"从此被测试真正覆盖，而不是靠两套各自正确的假象。
- 每个测试类结束后仍然清掉带凭证的系统属性（DBG-12），扫描 `target/surefire-reports/*.xml` 无 `DB_PASSWORD`。

**工程结论**

"重启一个已停掉的框架实例"这类动作，在进程级单例的框架里并不成立；而它的失败现象（连不上新端口）指向的是"服务没起来"，很容易被误判成端口占用或启动太慢。测试基础设施的每一次"起停"都在隐式依赖框架的生命周期语义，因此宁可让生命周期更简单（一次），也不要让它看起来更"干净"（每类一次）。

## DBG-14：`Map.copyOf` 不接受 null——"无人出价"把一次结算变成了 NPE

**现象**

P3 的事件代码（`AuctionEvent` + `AuctionEvents` 工厂）写完后，单类测试都通过，全量一跑却是 11 处红：

```
[ERROR] HttpApiIntegrationTest.bidFlowWithIdempotentReplay:117  ...
[ERROR] SettlementConcurrencyTest  Tests run: 5, Errors: 3
[ERROR] SettlementServiceTest      Tests run: 11, Errors: 3
```

报错是 NPE，但栈顶离业务很远：

```
java.lang.NullPointerException
	at java.util.Objects.requireNonNull
	at java.util.ImmutableCollections$MapN.<init>
	at java.util.Map.ofEntries
	at java.util.Map.copyOf
	at com.bidarena.auction.domain.AuctionEvent.<init>(AuctionEvent.java:34)
	at com.bidarena.auction.application.AuctionEvents.auctionFinished(AuctionEvents.java:119)
	at com.bidarena.auction.application.SettlementService.publishFinished(SettlementService.java:207)
	at com.bidarena.auction.application.SettlementService.settleIfDue(SettlementService.java:98)
```

**定位**

`AuctionEvent` 的紧凑构造器用 `Map.copyOf(payload)` 固化不可变 payload，而 `Map.copyOf` **拒绝 null 值**（也拒绝 null 键）。payload 里有两处会自然地产生 null：

1. `AnonymousId.of(null)` 返回 `null`——"到期但无人出价"的终局事件没有赢家；
2. `endsAt` 在草稿拍卖上本来就是 `null`。

于是"字段缺席"这个很正常的业务情形，在发布那一刻变成了异常。**更危险的是它发生的位置**：事件是在事务**提交之后**发布的（D-20），所以库里该结束的已经结束了，调用方却拿到一个 500——正是 A8 想要避免的那种"库变了、接口报错"的形态。幸运的是这次异常发生在测试里而不是演示时。

**修复**

不改变"null = 字段缺席"的契约（这与 HTTP 封套的约定一致），而是在工厂里统一用会跳过 null 的 `put`：

```java
private static void put(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
        payload.put(key, value);
    }
}
```

**验证**

- 修复后 `SettlementServiceTest`、`SettlementConcurrencyTest`、`HttpApiIntegrationTest` 全绿，全量 116/116。
- 语义被固定下来：`EventPublishingTest.finishedWithoutBidsOmitsWinner` 与 `WsIntegrationTest.cancelBroadcastsFinish` 断言"该字段**不存在**"，而不是 `field: null`。
- 变异反向确认：把 `put` 的判空去掉（放行 null），6 个用例立刻失败。

**工程结论**

`Map.copyOf` / `List.copyOf` 这类"收窄值域"的 API 会把"忘了判空"从"少一个字段"升级成"运行中崩溃"，而且崩溃点离根因很远（`NullPointerException` 出现在 `AuctionEvent.<init>`，根因却在"无人出价"这个业务分支）。用它们的时候，最好同时提供一个只会写入非空值的入口，让"缺席"成为默认行为而不是每次都要记得的特例。

## DBG-15：`Future` 上没有 `whenComplete`——异步发送失败差点变成观测盲区

**现象**

给广播器加"异步发送失败也要计数"时编译不过：

```
[ERROR] WsEventBroadcaster.java:[125,31] 找不到符号
  符号: 方法 whenComplete(...)
  位置: 接口 java.util.concurrent.Future<java.lang.Void>
```

**定位**

`WebSocket.send(String)` 的返回类型是 `java.util.concurrent.Future<Void>`，而 `whenComplete` 定义在 `CompletableFuture` 上。用 `javap` 看 Solon 的实现（`org.noira.solon.net.websocket.WebSocketImpl` 一系）确认：它内部 `try/catch` 后 `completeExceptionally`，返回的**实际对象**是已完成的 `CompletableFuture`。

**修复**

保留异步感知能力，但不假设接口的所有实现都是 `CompletableFuture`：

```java
if (future instanceof CompletableFuture<?> completable) {
    completable.whenComplete((ignored, error) -> { if (error != null) failedCount.incrementAndGet(); });
}
```

**验证**

`WsEventBroadcasterTest.asyncFailureIsCounted`（假连接让 `send` 返回异常完成的 Future）断言 `failed() == 1`；另一条 `sendFailureIsIsolated` 覆盖同步抛异常的情形，并断言失败连接被摘除、同场其它连接不受影响。

**工程结论**

接口类型比实现类型窄时，能用但不能依赖。对"发送是否成功"这种**必须可观测**的事实，同步异常与异步失败要各有一条路径，否则线上只会看到"广播看起来发了、客户端没收到"。

## DBG-16：JUnit 不给 `@BeforeAll` 注入 `ExtensionContext`

**现象**

为了实现 D-23（停服挂在根上下文存储上），我在 `@BeforeAll` 方法上直接要了上下文：

```java
@BeforeAll static void bootForClass(ExtensionContext context) { ... }
```

结果每个用到基座的测试类都报：

```
org.junit.jupiter.api.extension.ParameterResolutionException:
No ParameterResolver registered for parameter
[org.junit.jupiter.api.extension.ExtensionContext arg0] in method
[static void com.bidarena.support.ApiTestHarness.bootForClass(org.junit.jupiter.api.extension.ExtensionContext)].
```

**定位**

JUnit 5 的 `@BeforeAll`/`@BeforeEach` 方法参数支持 `TestInfo`、`TestReporter` 以及注册过的自定义 `ParameterResolver`，但**不**直接注入 `ExtensionContext`（它只在扩展回调里被传进来）。

**修复**

改成"扩展"这个天然拿得到上下文的入口，并用 `@RegisterExtension` 静态字段注册（静态字段会被子类继承，因此两个测试类共享同一个实例）：

```java
@RegisterExtension
public static final TestServerExtension SERVER_EXTENSION = new TestServerExtension();

public static final class TestServerExtension implements BeforeAllCallback {
    @Override public void beforeAll(ExtensionContext context) { /* 存进根上下文 */ }
}
```

`BeforeAllCallback` 先于 `@BeforeAll` 方法执行，因此原来的"启动后断言端口"仍然能放在 `@BeforeAll` 里。

**验证**

全量 116/116 绿（见 DBG-13 的验证一节）。

**工程结论**

"生命周期钩子需要上下文"时，扩展回调是标准入口，而注解方法只是给"不需要上下文"的清理/准备用的。这不算框架限制，而是分工：注解方法属于测试类，扩展属于测试运行时。

## DBG-17：`seq` 无缺口断言自己写错了——用 `-1` 当初值，第一次比较就失败

**现象**

`WsIntegrationTest.seqIsGaplessForSubscriber` 的断言失败，但打印出来的版本号序列看起来完全正常：

```
版本号出现缺口（客户端会因此触发重同步）：[2, 2, 2, 3, 4, 5] ==> expected: <true> but was: <false>
```

`[2, 2, 2, 3, 4, 5]` 里"2"重复是因为开拍(1)、加入(2)之后连接，快照是 2，随后加入第二人(3)、出价(4)、取消(5)：既没倒退也没缺口。

**定位**

```
long previous = -1;
for (long seq : observed) {
    assertTrue(seq >= previous, ...);
    assertTrue(seq <= previous + 1, ...);   // 第一次比较：2 <= 0 → 失败
    previous = seq;
}
```

哨兵初值 `-1` 让"相邻两帧最多 +1"这条约束作用在**第一个元素与一个假想的 0 号版本**之间。断言方向是对的，上下文是错的。

**修复**

用真实首元素做初值，并从第二个元素开始比较：

```java
long previous = observed.get(0);
for (int i = 1; i < observed.size(); i++) { ... }
```

**验证**

修复后该用例通过，全量 116/116 绿；把"重复 join 也推 seq"的变异注回去，它仍然会红（说明这条断言不是因为改松了才通过）。

**工程结论**

"看起来对的数据被判违规"几乎总是断言自己有问题，而不是被测代码。给循环写哨兵初值时，要么用真实数据的第一项，要么显式区分"首元素"这一次迭代——否则边界约束会悄悄作用在一个不存在的元素上。

---

## DBG-18：设计文档写的依赖规则，与代码里的包结构对不上

**现象**

准备把 `DESIGN.md` §2.4 的四条依赖规则写成 `ArchUnit` 测试时，先按规则本身跑了一遍现状，结果不是"全部通过"，而是**两条根本不成立**：

- `application` 直接 import 了 `auction.adapter.AuctionRepository` / `wallet.adapter.WalletRepository` 等具体仓储，而 §2.2 的表格写着"`application` 不得依赖 `adapter`"。
- 每个上下文内部都有包级循环：`adapter`（控制器）→ `application`（用例）→ `adapter`（仓储）→ …，而 §2.4 写着"任意两个包之间不得存在循环依赖"。
- 另外 `auction/domain/AuctionEvent` import 了 HTTP 侧的 `api.ApiTime`，违反"`domain` 仅 JDK"。

**定位**

四条规则并没有写错，错的是**分类**：`adapter` 这一个包同时装了两类东西——入站适配器（HTTP 控制器、WS 监听器）与出站适配器（JDBC 仓储）。只要它们在同一个包里，"入站 → 应用 → 出站"就会被看成"adapter → application → adapter"，循环是**命名带来的假象**，但也是真实存在的包级环（`ArchUnit` 只看包，不看你的意图）。

`api.ApiTime` 的情况相反：一个纯 `Instant.toString()` 的工具被放在了 HTTP 包，于是领域事件为了格式化时间就"必须"依赖 HTTP。

**修复**

- 出站 JDBC 仓储搬到 `<ctx>.persistence`；`adapter` 从此只表示入站。
- 查询视图 DTO（`AuctionViews` / `WalletViews` / `UserView`）搬到 `<ctx>.application`——它们是用例的返回形状，不是 HTTP 契约。
- `ApiTime`、`PageQuery` 搬到 `shared`，HTTP 查询串解析单独留在 `api.PageParams`（D-24、D-25）。
- 规则照文档原文写成 `ArchitectureTest` 九条，全部通过。

**验证**

搬运后全量 116 个用例绿（行为不变，改的只是包与 import）；九条规则全绿；`tools/arch_mutation_check.py` 注入九种真实违规 → **9/9 KILLED**。

**工程结论**

"文档里的架构规则"与"能跑的架构规则"之间隔着一句"先照实检查一遍"。把规则写成测试之前，先用它检查现状：不成立的地方往往不是规则太严，而是**包名承担了两种含义**。这次如果没有先跑，就会得到两个都不想要的结果——要么删掉规则，要么把规则改写成迁就现状的样子，而文档仍写着原来的话。

---

## DBG-19：`noClasses().should(自定义条件)` 被 ArchUnit 反转，规则永远不会变红

**现象**

九条架构规则全绿，但"全绿"有两种可能：架构真的干净，或者**规则写错了**。于是给每条规则做一个注入真实违规的变异体。其中两条规则（跨上下文 `domain` 引用、跨上下文 `adapter` 引用）注入了违规之后**依然全绿**：

```
KILLED   A1 ... A5
SURVIVED A6      rc=0  跨上下文 domain 引用
```

**定位**

先把依赖本身打印出来，确认 `ArchUnit` 确实看到了那条边：

```
DEP com.bidarena.identity.domain.Principal || Method <...AuctionEvent.archLeak()> references class object <...Principal>
```

依赖在，规则却没红——问题在规则写法。两条有问题的规则写成了：

```java
noClasses().that().resideInAPackage("..domain..").should(new ArchCondition<JavaClass>(...) { ... });
```

`noClasses().should(X)` 的语义是 `classes().should(never(X))`：`never` 反转的是"条件是否被满足"，而"条件是否被满足"由条件产生的**非违规事件**（allowed events）判定。自定义条件里只在发现违规时 `events.add(violated(...))`，从不产生 allowed 事件，于是 `never(...)` 永远认为"没有东西被满足"——**规则永远不可能失败**。

**修复**

换成肯定式断言（`classes().should(condition)`），条件的语义就是"发现跨上下文依赖即报违规"：

```java
classes().that().resideInAPackage("..domain..").should(new ArchCondition<JavaClass>("只依赖本上下文的领域模型") { ... });
```

改后 A6/A7 都被杀掉，规则也仍然全绿。

**验证**

- 修好写法后重跑变异：A6、A7 KILLED；最终 9/9 KILLED（A8 用于"上下文成环"、A9 用于"层与层成环"）。
- 全量 125 个用例绿。

**工程结论**

`noClasses()` 这种否定式 DSL 配内置 `dependOnClassesThat()` 很好用，但**配自定义条件时要先确认反转到的是哪一层语义**。更一般地：一条测试断言"没有任何违规"时，必须先用一个人造违规证明它会红——否则你不知道它是守卫，还是一行永远为真的注释。这次的顺序恰好说明了这一点：先怀疑规则，而不是先怀疑代码。

---

## DBG-20：全量跑时偶发一次 WS 握手超时——共享服务下的时间预算

**现象**

某一次 `mvn -o clean test` 全量跑（125 个用例）报出一个失败，且失败点在**建立连接**这一步，而不是在断言业务行为：

```
WebSocket 连接没有在 5000ms 内建立：ws://localhost:46924/ws/auctions/auc_9328c7272df7
  at WsIntegrationTest.connect(WsIntegrationTest.java:143)
```

**定位**

- 单独跑该用例两次都通过（`Tests run: 1, Failures: 0`），紧接着的全量跑也全绿——服务端行为没有变化。
- 同一个测试类里另外 13 个用例在同一轮里全部通过，说明 WS 服务本身是活的、端口是对的。
- 全量跑时机器负载明显更高（125 个用例 + 共享一个 JVM 里的服务实例），而 `connect()` 只给**一次** 5 秒预算：单次握手慢一点就直接判失败，与"服务是否有问题"无关。

**修复（只改测试基座）**

`connect()` 改成**按截止时间重试**，每次尝试仍用 5 秒，总预算 20 秒；失败过一次的客户端不复用（Java-WebSocket 底层套接字已废），重试用新连接：

```java
private static final long CONNECT_DEADLINE_MILLIS = 20_000;
while (!client.connectBlocking(AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
    if (System.currentTimeMillis() >= deadline) { throw new AssertionError(...); }
    client = new Client(url);
}
```

**验证**

`WsIntegrationTest` 14/14 绿；修完后连续两次全量 `clean test` 均 125/125 绿。

**工程结论**

共享服务实例的测试基座里，"连接/启动"这类环境动作的预算要按**最坏负载**给，而不是按空闲时的实测值给；但也不能只用"把 5 秒改成 60 秒"这种粗暴做法——那会让真正的连接故障也拖到 60 秒才报错。按截止时间重试同时保住了"快速失败"和"抗抖动"。注意这次修的是**测试基座的时间预算**，不是产品的连接行为（生产端没有任何改动）。

---

## DBG-21：变异 F14 存活——一条"验证幂等键复用"的测试其实没有验证

**现象**

P4 给前端补变异验证（`frontend/tools/mutation_check.py` 的 F11~F16），把"出价重试不复用幂等键"这条真实缺陷注入 `arena.ts`：

```python
("F14", "出价重试不复用幂等键（网络抖动会变成两次出价）",
 "src/store/arena.ts",
 "    if (pendingBid && pendingBid.auctionId === auctionId && pendingBid.amount === amount) return pendingBid.requestId",
 "    if (false) return pendingBid!.requestId",
 "src/store/arena.test.ts")
```

结果：`SURVIVED  F14  rc=0`——测试全绿。

**定位**

对应的用例标题就是「网络失败重试复用同一个幂等键」，看起来正是为这条规则写的。但它的假 API 只在**成功**分支里记录调用：

```ts
world.api.placeBid = async (_auctionId, body) => {
  if (failing) throw new ApiError({ code: 'NETWORK', serverMessage: '请求超时' })
  world.bids.push({ requestId: body.requestId, amount: body.amount })  // 只有第二次会走到这
  return ok(BID_RESULT)
}
...
expect(world.bids).toHaveLength(1)
```

第一次（失败）那笔的 `requestId` **从未被写下来**，断言只看"成功的出价条数 = 1"。无论第二次用的是不是同一个键，条数都是 1，所以这条断言和被测规则无关。它验证的是"失败不会留下记录"，恰恰不是幂等键复用。

**修复**

让假 API 记录**每一次尝试**（验证失败那次的键也要留痕），再断言两次的键相同、且等于最终落库的键：

```ts
const attempts: string[] = []
world.api.placeBid = async (_auctionId, body) => {
  attempts.push(body.requestId)
  if (failing) throw new ApiError({ code: 'NETWORK', serverMessage: '请求超时' })
  world.bids.push({ requestId: body.requestId, amount: body.amount })
  return ok(BID_RESULT)
}
...
expect(attempts).toHaveLength(2)
expect(attempts[1]).toBe(attempts[0])
expect(attempts[1]).toBe(world.bids[0].requestId)
```

**验证**

重跑变异：`F14 KILLED`；前端 16 个变异 **16/16 KILLED**；全量单测 56 绿、类型检查与 `vite build` 通过；真实后端联调 `src/api/live.test.ts` + `src/realtime/live.test.ts` 3/3 绿。

**工程结论**

"测试标题写了什么"和"断言真的检查了什么"是两件事。这次存活不是因为规则写错，而是因为**观察点选错了**——只在成功路径留痕，就不可能观察到"失败与成功用的是同一个键"。凡是断言"两次操作等价/一致"的用例，必须保证两次操作各自的痕迹都在场，否则它只能证明"只有一次成功"，证明不了"是同一次"。

---

## DBG-22：`Solon.start` 是进程级单例，第二次调用不会开出第二个监听器

**现象**

P5 要给 Agent API 开一个独立端口（默认 8090）。最直觉的写法是在组合根里再调一次
`Solon.start(Application.class, args)`，期望得到"第二个应用 + 第二个监听器"。结果：

- 进程照常启动、8080 可用，但 **8090 上没有任何东西在监听**（`netstat` 里找不到，curl 直接连接被拒）；
- 日志里也没有第二个 "App: Start loading"，第二次调用仿佛什么都没发生，**不报错**。

**定位**

不靠猜。直接反编译 `solon-3.0.1.jar` 的 `Solon.start(Class, NvMap, ConsumerEx)`：

```
 0: getstatic     #12   // Field appMain:Lorg/noear/solon/SolonApp;
 3: ifnull        16
 6: getstatic     #12   // Field appMain:Lorg/noear/solon/SolonApp;
 9: putstatic     #3    // Field app:Lorg/noear/solon/SolonApp;
12: getstatic     #12   // Field appMain:Lorg/noear/solon/SolonApp;
15: areturn
```

方法开头就是 `if (appMain != null) { app = appMain; return appMain; }`——第二次调用只是把
已有实例返回，`SolonApp` 根本不会新建，更不会再次绑定端口。"再启动一个应用"这条路是封死的。

**修复**

不重启应用，复用框架自己给主监听器用的 `SmHttpServerComb`（同一个类），只换 Handler 与端口，
并包成一个 `Plugin`，让 `Solon.stopBlock()` 在停服时统一 `stop()`（否则测试 JVM 里会留一个
悬空监听，下一轮启动端口被占）。Handler 里做端口隔离：只有 `/api/v1/agent/**` 进入主 pipeline，
其余路径直接回 404 封套（不是 403——这个端口上**没有**那些资源，而不是"有但不给看"）。

**验证**

`AgentApiIntegrationTest.agentPortExposesOnlyAgentApi` 同时断言：

- 8090 上 `/api/v1/agent/...` 可用（可用性）；
- 8090 上 `/api/v1/health` 返回 **404 封套**而不是 200（隔离性；见 DBG-25，这条一开始其实在跑旧字节码）。

**工程结论**

"调用一次启动函数"不等于"起了一个监听器"。插件式框架里，看起来像构造函数的方法很可能是
幂等访问器；要证明"确实多了一个监听器"，得去数端口与连接这类**外部可观察事实**，
而不是数代码里的调用次数。顺带：一个进程里想监听多个端口时，别假设框架支持——
先确认，再决定是复用底层 server 还是额外进程。

---

## DBG-23：提前拒绝一个带请求体的请求，会污染同一条 keep-alive 连接上的下一个请求

**现象**

对着管理员接口发一个**带 JSON 请求体**、令牌伪造的请求，服务端如预期回 401；紧接着在
**同一条 TCP 连接**上发一个完全正常的登录请求，却收到：

```json
{"code":"UNAUTHENTICATED","message":"缺少 Bearer 令牌", ...}
```

登录接口根本不需要令牌，这句提示明显对不上。服务端其实把它解析成了别的东西——
请求行被上一段没读完的请求体污染成了 `method={"title":"…"}POST`、`path=/api/v1/auth/login`。

**定位**

鉴权过滤器（默认拒绝，D-15）在**读请求体之前**就回写了 401。HTTP/1.1 的 keep-alive 下，
请求体还留在内核缓冲区里；底层的 smartboot 只在 `request.getInputStream().available() <= 0`
时才判定连接可复用。而"响应已回写"与"客户端把体发完"之间存在竞态：回写那一刻字节可能还在路上，
`available()` 返回 0 → 框架认为连接干净 → 随后到达的字节污染了下一条请求。

问题不在"提前拒绝"（越早拒绝越好），而在"拒绝时没有把没读的请求体读掉"。

**修复**

在唯一一处"不经过控制器就回写响应"的地方（`ApiWriter.failure`）加 `drainRequestBody(ctx)`：
把请求体读干净再回写。设了上限（4KB），超过上限则退化为回 `Connection: close`
让客户端换一条连接——未认证的请求不值得为它读完一个巨大的体，那会变成放大攻击面。

**验证**

`RejectedRequestConnectionTest` 两个用例（`RawHttp` 手工复用同一条 TCP 连接，避开连接池的随机性）：

- 一次"带体 401"之后，同连接上的登录仍返回 200；
- 连续三次"带体 401"之后，连接依然可用（不是只对第一条生效）。

**工程结论**

"拒绝得越早越好"这条直觉，在 keep-alive 上需要配一个动作：把没读的请求体读尽（或显式关闭连接）。
否则你修好了一个请求的安全性，却用同一个改动污染了它之后的每一个请求——而且症状会出现在
**完全无关的下一个请求**上，看起来像另一个模块的 bug。

---

## DBG-24：JWT 签名的 base64url 末位字符含填充位，改它等于没改

**现象**

P2 的 `tamperedTokensAreRejected` 用例想验证"签名被改过的令牌不通过"。做法是把签名末尾一个字符
换成别的。它**每 16 次会红 1 次**，像一个随机失败。其余时候全绿，很容易被当成偶发抖动忽略。

**定位**

HMAC-SHA256 的签名是 32 字节。base64url 编码 32 字节得到 43 个字符：前 42 个字符承载完整的
252 bit，最后一个字符只承载剩下的 4 bit，另外 2 bit 是**填充位**，解码时被丢弃。
把末位字符换成另一个"仅填充位不同"的字符（例如只在这种位上差 1），解码出来的 32 字节
**完全一样**，签名自然依然有效——所谓的"篡改过的令牌"其实一个字都没变。

**修复**

改为篡改签名段的**第一个**字符：它参与解码的 6 个 bit 全部有效，任何替换都会改变签名字节。
两者都不改头部与载荷，唯一差别就是签名本身。

```java
// 不能改末位字符（DEBUG_LOG DBG-24）：签名字节数 32，base64url 编出来 43 个字符，
// 末位字符里有两个 bit 是填充位、解码时被丢掉……
int signatureStart = token.lastIndexOf('.') + 1;
```

**验证**

该用例从"每 16 次红 1 次"变为稳定通过；`IdentityServiceTest` 10/10 绿。

**工程结论**

用"改一个 base64 字符"构造负例时，要改的是**参与解码的位**，而不是任意一个字符。
编码长度不是 4 的整数倍时，末位字符可能带填充。稳妥做法是改首位（或改长度不为整字节的段的首字符），
而不是凭"末位看起来最无害"去改。概率性失败的测试比失败的测试更危险——它会先教会人们忽略红灯。

---

## DBG-25：源码改了、测试却在跑旧字节码——Maven 增量编译跳过了重编

**现象**

P5 给 `AgentApiPlugin` 加上了端口隔离判断（只有 `/api/v1/agent/**` 进主 pipeline，其余 404），
源码里 `onlyAgentApi` 明明写着：

```java
if (ctx.path().startsWith(ApiPaths.AGENT_PREFIX)) { app.tryHandle(ctx); return; }
```

但 `mvn test` 里 `AgentApiIntegrationTest.agentPortExposesOnlyAgentApi` 依旧失败：
预期 8090 上 `/api/v1/health` 返回 404，实际返回 **200**（Agent 端口把主应用的完整路由也暴露了）。
`mvn` 不报任何编译错误或警告。

**定位**

比较时间戳：

```
src/main/java/com/bidarena/bootstrap/AgentApiPlugin.java   18:03:56
target/classes/com/bidarena/bootstrap/AgentApiPlugin.class 18:04:01   <- class 反而更新
```

Maven 的增量编译按"源文件是否比 class 新"判断要不要重编。源码时间戳早于 class，
于是判定"未变更"、跳过重编——**classpath 上是没有隔离判断的旧字节码**。

用 `javap -c` 反编译 `target/classes` 里的那个 class 可以确认它没有 `startsWith`/`NOT_FOUND` 分支；
`mvn -o clean compile` 强制重编后，同样的 `javap` 就能看到隔离逻辑。此时再跑测试，用例通过。

**顺带发现的同类隐患（变异脚本）**

`tools/agent_mutation_check.py` / `tools/arch_mutation_check.py` 的做法是"备份 → 改源码 → 跑测试 → 还原"。
还原用 `shutil.move` 会把文件 mtime 退回**备份时刻**，而此刻 `target/classes` 里那个"变异后的 class"
反而更新。于是上一条变异的字节码会留在 classpath 上，毒害后续变异——`KILLED` 的结论会变得不可信。
同类问题的另一种表现是：`mvn clean` 偶尔被 Windows 文件锁挡住而失败（退出码非 0、但 surefire 报告
根本没生成），脚本会把这种"废轮"误读成 `SURVIVED`。

**修复**

- 人工侧：验证统一 `mvn clean verify`（这也是 README 一键命令的由来）。
- 脚本侧：还原源码后 `os.utime(path, None)` 把 mtime 拨到现在；并且当"退出码非 0、但预期测试类
  没有有效报告"时，判定这一轮无效并**重试一次**，而不是当成存活。

**验证**

`mvn -o clean compile` 后该用例通过；重跑变异脚本，G13 从误报的 `SURVIVED` 变为 `KILLED`
（手工复现也确认：去掉权限判断后 `readOnlyTokenCannotBid` 期望 403、实际 200），最终 **14/14 KILLED**。

**工程结论**

增量编译的正确性依赖文件时间戳，而"改源码、还原备份、`git checkout`、解压覆盖"都可能让时间戳倒退。
凡是遇到"我明明改了却没生效"，先比较 `src` 与 `target` 的 mtime，再怀疑逻辑——
否则会去修一个根本不存在的产品缺陷。反过来，**验证工具本身也必须被验证**：
一个会把环境噪声读成 `SURVIVED` 的变异脚本，产出的"全部被杀"结论和没有一样。

---

## DBG-26：E2E 脚本自己的断言用了一枚“早就被吊销”的 Token

**现象**

`tools/agent_sim.py` 首次对真实服务实跑，**43/44 通过**，唯一一条红是收尾那条：

```
[OK]   未知 tokenId 吊销 404
[OK]   Agent 端口上没有 /health（404 封套）
[OK]   管理员取消拍卖（收尾）
[FAIL] 取消后 Agent 读到 CANCELLED   expected=CANCELLED actual=None
[OK]   取消的拍卖没有赢家             True
---- 43/44 checks passed ----  EXIT=1
```

问题看起来像“取消后结果接口没返回状态”——一个真实的产品缺陷。**但不是。**

**定位**

`actual=None` 说明脚本没取出 `data.status`。往前翻两条，边界检查 6.6 刚刚做过：

```
[OK]   吊销返回 OK
[OK]   吊销后 401
```

被吊销的正是收尾那一步要复用的那枚 `full_token`。于是收尾的 `GET /agent/.../result`
拿到的是一个 401 封套（`data` 为空），`data_of(envelope).get("status")` 自然是 `None`。
断言本身没错，是**它手里的凭证已经无效**——错在用同一枚 Token 既演“吊销”又演“吊销之后还要能读”。

**修复**

抽出 `issue_read_only(...)`，收尾读结果时**新签一枚只读 Token**，而不是复用 `full_token`：

```python
agent.token = issue_read_only(admin, auction_id, bidder_a["id"], "agent-sim 收尾读结果")
status, envelope = agent.get("/agent/auctions/%s/result" % auction_id)
```

重跑：**44/44 通过，退出码 0**。

**工程结论**

“测试红了”有两个方向可以查：产品错了，或者**测试本身错了**。这次是后者，
而且它伪装得很好——失败信息（“读不到 CANCELLED”）完全指向产品。
分辨的方法不是重跑碰运气，而是**顺着数据往下看一层**：`None` 是从哪个字段来的、
这个字段为什么不在、上一个动作对这个凭证做过什么。
又一次印证了 DBG-25 的教训：验证工具必须先被验证，但验证的方式是**真的把它跑一遍并读完每一条输出**，
而不是“它编译过了”。

---

## DBG-27：E2E 断言把不变量想得比契约更强——邻价并发可以成交两笔、狙击到上限后不再延时

**现象**

`tools/auction_sim.py` 第一次跑真实服务，34 条里红了 3 条，其中两条都在“邻价并发”这一段：

```
== 3. 20 条邻价（120/130）并发出价：最高价胜出 ==
  [FAIL] 恰好 1 条被接受                                    expected=1 actual=2
         {"OK": 2, "BID_TOO_LOW": 18}
  [FAIL] 被接受的是最高价 130                                 expected=130 actual=120
         {"OK": 2, "BID_TOO_LOW": 18}
  [OK]   最终价 = 130                                    130
```

“被接受的是 120”这条尤其像缺陷：最高报价明明是 130。另一条在狙击段：

```
  [OK]   第 4 次狙击被接受                                   OK
  [OK]   延时次数 = 3                                     3
  [FAIL] 截止时间被推后                                      expected=True actual=False
         {"before": "2026-09-13T10:41:25.889589Z", "after": "2026-09-13T10:41:25.889589Z"}
```

**定位**

两条都是**断言错了，不是被测对象错了**。

- 邻价并发：`BidService` 的规则是“出价必须 ≥ 当前价 + 最小加价”。120 先成交时价 120，随后
  130 是完全合法的抬价（130 ≥ 120+10），所以**允许成交两笔**。契约保证的是
  “同一价位至多成交一笔”与“最终价 = 最高报价”，从不保证“全局只接受一笔”。
  `[OK] 最终价 = 130` 正说明系统是对的：那笔 120 只是历史中标，领先者已被 130 取代。
- 狙击上限：`MAX_EXTENSIONS = 3`。第 4 次仍在最后五秒窗口内，但 `extensionCount` 已达上限，
  `BidService` 保持 `newEndsAt = auction.endsAt()` 不动——出价照常接受，截止时间**不该**再推后。
  我的断言把“被接受”和“被延时”画了等号。

**修复**

把断言改成契约真正承诺的那几条：

```python
report.check("被接受笔数在 1~2 之间", 1 <= len(accepted) <= 2, True, codes)
report.check("恰好 1 条 130 被接受（同价位不重复成交）", accepted.count(130), 1, codes)
...
if attempt <= MAX_EXTENSIONS:
    report.check("截止时间被推后 +10 秒", delta, 10, ...)
else:
    report.check("达到上限后截止时间不再推后", delta == 0, True, ...)
```

**验证**

```
  [OK]   被接受笔数在 1~2 之间                                True
  [OK]   被接受的最高价 = 130                                130
  [OK]   恰好 1 条 130 被接受（同价位不重复成交）                     1
  [OK]   达到上限后截止时间不再推后                                True
---- 52/52 checks passed ----
```

**工程结论**

E2E 脚本里的每一条断言都是一句“我理解的契约”。断言写强了，红的是脚本，但看起来像产品缺陷——
这会浪费时间去修一个不存在的问题，比断言写松了更危险（写松了只是漏检，写强了会指向错误的修复方向）。
写并发断言前先问一句：**这条不变量的事务边界在哪、锁住了什么？** “只接受一笔”只对**同一出价**成立
（同价、同用户、同 `requestId`），跨价位没有这个性质。

---

## DBG-28：幂等键含 `user_id`——换个用户复用同一 `requestId` 不是重放

**现象**

`tools/auction_sim.py` 的“同一 `requestId` 并发重试 20 次”一段，预期 1 次写入 + 19 次重放：

```
== 4. 同一 requestId 并发重试 20 次：只写入一次、只冻结一次 ==
  [OK]   恰好 1 条首次接受                                   1
  [FAIL] 其余 19 条为幂等重放                                 expected=19 actual=9
         {"BID_TOO_LOW": 10, "OK": 1, "IDEMPOTENCY_REPLAY": 9}
```

恰好一半重放、一半 `BID_TOO_LOW`，“一半”这个比例很可疑。

**定位**

脚本把 20 条请求按 `i % 2` **轮流**发给两个账号：

```python
results = run_concurrent([bid_job(a if i % 2 == 0 else b, auction_id, 140, duplicate) for i in range(20)])
```

而幂等键不是 `requestId` 本身。看 schema（`db/migration/V1__auction_schema.sql`）：

```sql
CREATE TABLE bid_requests (... PRIMARY KEY (auction_id,user_id,request_id));
```

即键是 **(auction_id, user_id, request_id)**。所以这 20 条其实是两个互不相关的幂等域：
竞拍者 A 的 10 条 → 1 次真实写入 + 9 次重放；竞拍者 B 的 10 条 → 此时价已被 A 抬到 140，
B 的 140 低于“140 + 最小加价 10”，全部 `BID_TOO_LOW`。
`9 + 10 = 19`，数字完全对得上——不是漏了重放，是我把两个用户混成了一个幂等域。

**修复**

并发重试必须固定同一个用户，另外补一条**顺序**重放（无并发，结论必须确定），
并把“跨用户不复用”作为一条显式断言写下来：

```python
results = run_concurrent([bid_job(a, auction_id, 140, duplicate) for _ in range(20)])
...
_, envelope = a.post("/auctions/%s/bids" % auction_id, {"requestId": duplicate, "amount": 140})
report.check("顺序重试 -> 幂等重放", code_of(envelope), "IDEMPOTENCY_REPLAY", envelope)
# 幂等键含 user_id：换个用户复用同一串不算重放
_, envelope = b.post("/auctions/%s/bids" % auction_id, {"requestId": duplicate, "amount": 150})
report.check("另一用户复用同一 requestId 视为新出价（键含 user_id）", code_of(envelope), "OK", envelope)
```

**验证**

```
  [OK]   恰好 1 条首次接受                                   1
  [OK]   其余 19 条为幂等重放（不再产生写入）                         19
  [OK]   顺序重试 -> 幂等重放                                 IDEMPOTENCY_REPLAY
  [OK]   重放返回首次成交价 140                                140
  [OK]   赢家冻结增量 = 成交价（未被重复冻结）                         140
  [OK]   另一用户复用同一 requestId 视为新出价（键含 user_id）         OK
```

“赢家冻结增量 = 140”同时证明 20 条重复请求只冻结了一次。
`wallet_a0` 在**整段并发之前**取样、`wallet_a1` 在之后取样，用增量而非绝对值，
因此不受库里既有冻结额影响。

**工程结论**

`requestId` 是**调用方**生成的，两个客户端完全可能撞串（同一个模板、同一个 UUID 生成器状态）。
撞串时“各算各的”比“互相吞掉”正确得多：幂等是为了**同一调用方的重试**去重，
而不是全局去重。这条语义必须写进文档和断言，否则前端会以为“重试安全”等于“全站唯一”。
另：断言里的“恰好一半”这种比例异常，往往就是“把两个域当成了一个域”的信号。

---

## DBG-29：界面还在说 Agent API“尚未实现”，实际 P5 已经交付

**现象**

准备手工测试时点开前端「智能体接入」页，看到的是这段：

> 本页面的数据**不来自** Agent 接口：Agent API（`:8090`、Agent Token、限流）属于后续里程碑，
> 当前尚未实现，因此这里不会显示任何伪造的调用记录。
>
> **契约中已定义、但尚未实现的接口**
> - `GET /agent/auctions` —— 列出可参与的拍卖（agent:read）　*当前返回 404：路由尚未挂载*
> - `GET /agent/auctions/{auctionId}/bids` —— 读取出价记录（agent:read）　*当前返回 404：路由尚未挂载*
> - `GET /agent/wallet` —— 智能体钱包（agent:read）　*当前返回 404：路由尚未挂载*

而 P5 已经交付并验证过 Agent API；这条“尚未实现”是**交付完之后没回头改的文案**。

**定位**

1. 文案写死在前端模板里（`frontend/src/App.vue` 的 `view === 'agent'` 分支），不随实现推进更新；
   P5 只改了 README/DESIGN/STATUS/TRACEABILITY 这些 Markdown，没有回归检查这条**代码里的陈述**。
2. 列出的三个端点**连契约里都不存在**：

```
$ grep 'agent' docs/openapi.yaml | ...
/agent/auctions/{auctionId}
/agent/auctions/{auctionId}/bids
/agent/auctions/{auctionId}/result
```

即“依据契约列出来”也是假的——`GET /agent/auctions`、`/bids`（GET）、`/wallet` 早在 P2 修订契约时就去掉了。
3. 实际路由是：

```
HttpAgentController:      @Mapping("/api/v1/agent")
  GET  /auctions/{auctionId}
  POST /auctions/{auctionId}/bids
  GET  /auctions/{auctionId}/result
HttpAgentTokenController: @Mapping("/api/v1")
  POST /admin/agent-tokens
  POST /admin/agent-tokens/{tokenId}/revoke
```

**修复**

把该页改成如实陈述：`/api/v1/agent/**` 已在 `:8090` 实现、凭据与五项约束、不伪造调用记录，
并列出**五个已实现**的端点（含各自的端口与所需权限），同时保留一句“`:8090` 只挂载
`/api/v1/agent/**`，其余路径 404”。

**修复时又踩了一次同一个坑**：改写文案时凭印象把权限项写成 `agent:read` / `agent:bid`，
而实际是 `auction:read` / `auction:bid`。是真实调用把它顶回来的：

```
POST /admin/agent-tokens {"scopes":["agent:read"], ...}
-> {"code":"VALIDATION_FAILED", "data":{"allowed":"auction:read,auction:bid","scope":"agent:read"},
    "message":"未知的权限项"}
```

错误响应里直接给出了允许值（`data.allowed`），照它改即可。随后五个端点逐个实测：

```
POST :8080 /admin/agent-tokens                              -> OK（明文只回一次）
GET  :8090 /agent/auctions/auc_demo_0001   (auction:read)   -> OK（返回快照）
GET  :8090 /agent/auctions/auc_whatever    (未授权场)       -> FORBIDDEN「Agent Token 未被授权访问该拍卖」
POST :8090 /agent/auctions/auc_demo_0001/bids（只读 Token） -> FORBIDDEN「Agent Token 不具备该操作的权限」
```

**验证**

```
$ grep -rn "尚未实现\|路由尚未挂载\|后续里程碑" frontend/src/     # 无输出
$ npm run typecheck                                            # 通过
 Test Files  5 passed | 2 skipped (7)
      Tests  56 passed | 3 skipped (59)
$ curl -s http://localhost:5173/src/App.vue | grep -c "已实现的接口"   → 1
$ curl -s http://localhost:5173/src/App.vue | grep -c "路由尚未挂载"   → 0
```

**工程结论**

文档漂移不只在 Markdown 里。**界面文案也是关于系统的断言**，而且是最容易被评审看到的那一份：
一句“当前尚未实现”会让已经验收过的能力看起来没做，比缺一段文档更伤。
交付一个里程碑时，除了改 Markdown，还要 `grep` 一遍“尚未实现 / 尚未挂载 / 待补 / TODO”这类**负向断言**，
它们和正向文档一样需要随版本更新。

补一句：修正的方向对了不代表细节对——**权限项、端口、路径这些字符串必须从实现/契约里取，不能凭印象写**。
这次是 `auction:read` 而不是 `agent:read`，靠一次真实调用才发现的；
幸好系统把“允许什么”作为机器可读的字段（`data.allowed`）返回了，没有让我去猜。

---

## DBG-30：预告开拍“到点没动”——数据库容器的时钟比开发机慢 3 分钟

**现象**

手工核验 D-35/D-36 时，脚本按**本机时间** +8 秒写入 `startsAt`，然后等自动开拍：

```
  PASS 快照下发 startsAt  <- 2026-09-13T14:32:51Z
  PASS 创建后是 DRAFT（未手动开拍）  <- DRAFT
... 等自动开拍（最多 30s，期间不调用任何 start 接口）
  FAIL 预告时间到自动开拍（无需管理员在线）  <- DRAFT
  FAIL 代理到点自动进场并出价
```

调度器日志一切正常（`预告开拍调度已启动，间隔 1000ms，每轮上限 50 场`），没有 ERROR，没有异常。

**定位**

一边是 JVM 报的“现在”：

```
$ curl -s http://localhost:8080/api/v1/health
{"data":{"time":"2026-09-13T14:32:39.559067500Z", ...}}
```

另一边是数据库报的“现在”：

```
$ docker exec bid-arena-mysql-1 mysql -ubid_arena -p*** -N \
    -e "SELECT NOW(6), UTC_TIMESTAMP(6); SELECT id,status,starts_at FROM bid_arena.auctions ..."
2026-09-13 14:30:56.944101   2026-09-13 14:30:56.944101
auc_1e9709a30c1d  DRAFT  2026-09-13 14:32:51.000000
```

容器时钟比开发机**慢约 3 分钟**。而本项目所有“现在几点”的业务判定都按 D-5 使用数据库时间
（`findDueToStartIds` 的条件是 `status='DRAFT' AND starts_at <= ?`，参数来自 `Db.now(conn)`），
所以服务端的结论其实是“还没到点，继续等”——实现是对的，**错的是核验脚本**：它用本机时钟
去制造了一个“数据库视角的未来时刻”。

**修复**

脚本不再碰本机时间，改为从快照里取**权威的服务端时间**（`serverTime` 就是数据库时间，前端
`store.serverNow` 用的是同一个字段）：

```python
st, r = call("GET", "/auctions?page=1&size=1", admin)
server_now = parse(r["data"]["items"][0]["serverTime"])          # 数据库时间
starts_at  = (server_now + timedelta(seconds=8)).strftime("%Y-%m-%dT%H:%M:%SZ")
```

等待循环也从“睡够 30 秒”改成按快照里 `endsAt - serverTime` 判断剩余时间，顺带避免把
“本机睡眠时长”当成“服务端经过了多久”。

**验证**

```
数据库时间=2026-09-13T14:31:22.621182+00:00，预告开拍=2026-09-13T14:31:30Z
  PASS 预告时间到自动开拍（无需管理员在线）  <- RUNNING
  PASS 代理到点自动进场并出价  <- ('BIDDING', 110)
  PASS 被超过后代理按最小加价夺回  <- 2
  PASS 管理员按场次流水出现 AGENT 主体  <- ['AGENT', 'HUMAN', ...]
    剩余 19.7s（窗口 20s）
  PASS 尾段内代理不再出价  <- (2, 2)
  PASS 尾段真人仍可出价（提示 != 拦截）  <- (200, 'OK')
  PASS 代理收尾为 FINISHED / 收尾带输赢与成交价  <- (False, 140)
== 27/27 checks passed ==
```

**工程结论**

跨机器测试时“现在几点”有**三个**候选时钟：本机、应用进程、数据库。谁都没错，但必须显式指定用哪一个，
而业务判定一律以**数据库时间**为准（D-5）。这条规则早已写进代码与集成测试（测试里只允许用
`NOW(6) - INTERVAL ...` 这样的数据库相对时间，或固定的字面量），但**手工核验脚本**是新写的一份
“客户端”，于是又把同一个坑踩了一遍——说明这条纪律要覆盖到工具层，而不只是测试层。

顺带一个可复用的结论：**`serverTime` 已经是一个可读的权威来源**（HTTP 与 WS 快照都带）。
需要“未来某个时刻”时，用它做基准即可；凡是代码里出现 `Date.now()` / `Instant.now()` 参与
业务时刻的地方，都值得再看一眼。

---

## DBG-31：E2E 连跑几次后连环 `INSUFFICIENT_BALANCE`——脚本真的会花钱

**现象**

同一台后端、同一套代码，`tools/auction_sim.py` 前一天还是 `52/52`，这次变成大面积失败，
而且失败点散落在好几个阶段（有的像并发规则坏了，有的像幂等键坏了），最后以一段
WebSocket 读取超时收场：

```
== 3. 20 条邻价（120/130）并发出价：最终价必为最高价 ==
  [FAIL] 被接受的最高价 = 130       expected=130 actual=120
         {"BID_TOO_LOW": 9, "INSUFFICIENT_BALANCE": 10, "OK": 1}
  [FAIL] 其余全部 BID_TOO_LOW       expected=19  actual=9
== 4. 同一用户同一 requestId 并发重试 20 次：只写入一次、只冻结一次 ==
  [FAIL] 另一用户复用同一 requestId 视为新出价（键含 user_id） expected=OK actual=INSUFFICIENT_BALANCE
         data={"totalBalance":170,"frozenAmount":150,"availableBalance":20,"requiredDelta":150}
...
  File "tools/auction_sim.py", line 221, in _read_exact
    chunk = self.sock.recv(65536)
TimeoutError: timed out
```

这一片失败里最有迷惑性的地方在于：**每一条 FAIL 都像在指控产品**（并发规则、幂等键、实时通道），
而它们彼此之间没有共同点——除了响应体里那句 `INSUFFICIENT_BALANCE`。

**定位**

直接查库，答案只有一个：演示账号被**真的花掉了**。

```
$ docker exec bid-arena-mysql-1 mysql -ubid_arena -p*** -N \
    -e "SELECT user_id,total_balance,frozen_amount FROM bid_arena.wallets ORDER BY user_id;"
usr_admin     1000  0
usr_bidder_a   540  140      # 可用 400
usr_bidder_b    20    0      # 可用 20  ← 已经花完
```

种子给每个账号 1000。`auction_sim.py`（20 条并发出价 + 狙击阶段）、`agent_sim.py`、
`stress_test.py` 都跑在**开发库**上，每一轮都真实冻结资金、真实成交，不会自己回滚。
连跑几轮之后余额见底：后面的出价全被拒，于是**所有依赖“出价成功”的断言同时失效**，
WebSocket 阶段也因为等不到 `BID_ACCEPTED` 而超时。

所以缺陷有两处，都不在产品里：

1. 脚本**没有前置条件**。它默认“种子余额还在”，但这件事只对第一轮成立；
   而且它把“数据不够”表达成了一堆与根因无关的断言失败——失败点离根因太远。
2. **没有恢复手段**。跑完一轮之后，想再跑一轮只能自己想办法把余额弄回去；
   如果没有一条明确的路径，评审很容易把“没数据了”读成“实现是坏的”。

**修复**

1. `tools/preconditions.py`：所有会花钱的脚本共用一条前置检查，可用余额低于 400 就在**阶段 0** 停下，
   打印根因与恢复命令，并以**退出码 2** 结束（与“有检查失败”的 1 区分开）：

```
== 0. 前置检查：演示账号可用余额 ==
  [前置] 可用余额 bidder_a         400
  [前置] 可用余额 bidder_b         20

!! 前置条件不满足：bidder_b(20) 的可用余额低于 400，脚本跑不完一轮。
   这些脚本会在真实库里真的花钱：连跑几次就会把种子的 1000 花完，
   之后的出价全是 INSUFFICIENT_BALANCE —— 那不是缺陷，而是数据用完了。
   恢复种子状态（仅开发库；不修 schema、不动 Flyway 历史）：
     docker exec -i bid-arena-mysql-1 mysql --default-character-set=utf8mb4 \
       -ubid_arena -p"$DB_PASSWORD" bid_arena < db/reset_demo_data.sql
```

2. `db/reset_demo_data.sql`：一条命令把演示数据恢复到种子状态——按 `TestDatabase.TABLES_IN_WIPE_ORDER`
   的顺序清空竞拍相关表（`users` 保留），钱包写回 1000/0，并重建那场 `DRAFT` 演示拍品；
   只动数据，不改 schema、不碰 `flyway_schema_history`。最后回显钱包与拍品供人一眼确认。

3. `auction_sim.py` 的每个阶段现在都带上名字再抛异常（只补上下文，不吞异常、不改堆栈）：
   真出问题时先看到“阶段「实时通道」异常中断：TimeoutError”，而不是一段裸 traceback。

**验证**

```
$ python tools/auction_sim.py --quick          # 余额不足时
== 0. 前置检查：演示账号可用余额 ==
!! 前置条件不满足：bidder_b(20) 的可用余额低于 400，脚本跑不完一轮。
exit=2

$ docker exec -i bid-arena-mysql-1 mysql --default-character-set=utf8mb4 \
    -ubid_arena -p*** bid_arena < db/reset_demo_data.sql
kind    name           total  frozen
wallet  usr_admin      1000   0
wallet  usr_bidder_a   1000   0
wallet  usr_bidder_b   1000   0
auction auc_demo_0001  DRAFT

$ python tools/auction_sim.py                  # 恢复后
---- 52/52 checks passed ----          exit=0
$ python tools/stress_test.py --mode game-window -c 100
---- 11/11 checks passed ----
$ python tools/agent_sim.py
---- 44/44 checks passed ----
```

**工程结论**

“验证工具也是被测对象”这条（DBG-25/DBG-26）在这里换了张脸：这次工具没有算错，它只是**依赖于一个
会用完的前提**。凡是会消耗真实资源（余额、配额、额度）的脚本，都该在开头把前提写成断言，
把“前提不成立”和“行为不符合预期”分成两种退出码——否则前者的表现会被读成后者，
而前者是可以一条命令修好的，后者才需要改代码。

补一条顺手发现的不一致：`db/reset_demo_data.sql` 里原本用中文做 `SELECT` 的列别名，
在客户端默认字符集为 `latin1` 时直接 `ERROR 1064`——同一份 UTF-8 文件里，**注释**里的中文没事，
**标识符**里的中文就会炸。所以文件里刻意只用 ASCII 别名，并在用法中显式带上
`--default-character-set=utf8mb4`。

## DBG-32：变异脚本报 `0/9 KILLED`——不是规则失守，是 `mvn clean` 被文件锁挡住了

**现象**

交付验证复跑时，架构变异脚本从“9/9 KILLED”变成九条全部存活：

```
$ python tools/arch_mutation_check.py
SURVIVED A1      rc=1 hit=NONE fired=NONE
SURVIVED A2      rc=1 hit=NONE fired=NONE
...
SURVIVED A9      rc=1 hit=NONE fired=NONE
---- total: 0/9 KILLED
```

这个结论很吓人——“架构守卫形同虚设”——但它与 A1~A9 各自的内容无关：九条全灭、连一条都不红，
更像是**根本没跑到测试**。

**定位**

脚本判“存活”的依据是“退出码非 0 且命中了预期的规则字段”。这里 `rc=1` 却 `hit=NONE fired=NONE`：
`fired` 为空说明 surefire 报告里**一条用例都没有**，报告文件压根没生成。手动执行脚本里那行命令：

```
$ mvn -o clean test -Dtest=ArchitectureTest -DfailIfNoSpecifiedTests=false
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean
        (default-clean) on project bid-arena-core: Failed to clean project:
        Failed to delete ...\target\libs\solon-web-staticfiles-3.0.1.jar -> [Help 1]
```

根因和架构规则无关：验证 E2E 时需要本机起一个后端，我用的是
`java -cp target/bid-arena-core-0.1.0-SNAPSHOT.jar;target/libs/* com.bidarena.Application`。
Windows 不允许删除被进程打开的文件，于是 `mvn clean` 删 `target/libs/*.jar` 失败，
Maven **在编译之前**就退出；没有编译、没有 surefire 报告，脚本的 `hit` 自然是空的——
它把这个“空”读成了“变异存活”。报错信息里的关键词是 `Failed to delete`，与 `-o`（离线）无关
（离线只影响依赖解析，而失败发生在 clean 阶段）。

**修复**

1. 停掉本地后端（释放 `target/libs/*.jar` 的文件锁）后重跑，九条全部恢复：

```
$ python tools/arch_mutation_check.py
KILLED   A1      rc=1 hit=domainDependsOnlyOnItselfAndTheSharedKernel fired=domainDependsOnlyOnItselfAndTheSharedKernel
...
---- total: 9/9 KILLED
```

2. `tools/arch_mutation_check.py` 补一条“没跑起来”的判定：surefire 报告不存在时不再打 `SURVIVED`，
   改打 `NO-RUN`、附上 Maven 输出的最后一行，并直接指出“先确认没有进程占用 `target/libs/*.jar`”。
   `tools/agent_mutation_check.py` 早有同类处理（报告缺失时重试一轮并打印 Maven 尾部输出），
   这次只是把架构脚本对齐到同一条口径上。

**验证**

```
$ python tools/arch_mutation_check.py      # 停掉本地后端之后
---- total: 9/9 KILLED
$ python tools/agent_mutation_check.py
---- total: 14/14 KILLED
$ cd frontend && python tools/mutation_check.py
全部 16 个变异都被杀死
```

**工程结论**

“变异存活”与“这一轮没跑起来”是两种完全相反的结论，但在脚本眼里长得一模一样：一个非零退出码。
凡是靠“退出码 + 报告文件”下判断的工具，都必须把**报告文件不存在**当成独立情形单独报出来，
而不是让它落进默认分支（DBG-31 是同一道理的另一种形态：把“前提不成立”和“行为不符合预期”
分成两种退出码）。

这条坑还有个不太友好的巧合：**能锁住 `target/libs` 的，正是跑 E2E 脚本所必需的那个后端**——
也就是说“先跑 E2E、再跑变异验证”这个最自然的顺序，恰好会踩中它。跑变异脚本前先停后端，
或者干脆把运行时依赖复制到 `target/libs` 之外的目录再启动。
