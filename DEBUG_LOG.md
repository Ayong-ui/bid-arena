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
