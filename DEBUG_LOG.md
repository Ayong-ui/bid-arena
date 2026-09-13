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
