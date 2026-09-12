# 开发期间真实问题记录

> 本文件只记录**实际发生并已定位修复**的问题，每条都附当时的原始输出。
> 事后美化或推测出来的"问题"不写入本文件。

当前条目：6 条（要求 ≥ 2）。

| 编号 | 一句话 | 影响的交付面 |
|---|---|---|
| DBG-1 | `ALTER TABLE ... ADD COLUMN` 的位置子句顺序错误导致 1064，Flyway 留下失败记录与半应用 schema | 数据库迁移 |
| DBG-2 | Maven 增量资源拷贝没有刷新 `target/classes` 里的迁移脚本，改完代码报的还是旧错 | 构建可靠性 |
| DBG-3 | Windows GBK 控制台把中文 JSON 转码，GitHub API 返回 400 | 仓库管理 |
| DBG-4 | MySQL 8.4 移除了 `default_authentication_plugin` 变量 | 环境与连接 |
| DBG-5 | 开发机 VM 无法访问 Docker Hub，拉不到 `mysql:8.4` | 环境与 Compose |
| DBG-6 | `kill` 报成功但 JVM 未退出，"健康检查通过"可能是残留旧进程在应答 | 验证可信度 |

---

## DBG-1：迁移脚本 1064 语法错误，并把 schema 留在半应用状态

**现象**

首次启动应用，Solon 启动失败，应用未监听端口。

**日志**

```
Solon start failed: Failed to execute script V2__identity_wallet_ledger.sql
org.flywaydb.core.internal.exception.FlywayMigrateException: Failed to execute script V2__identity_wallet_ledger.sql
Caused by: org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException: Failed to execute script V2__identity_wallet_ledger.sql
SQL State  : 42000
Error Code : 1064
Message    : You have an error in your SQL syntax; ... near 'COMMENT '首次结果的成交价快照，用于幂等重放',
  ADD COLUMN resu' at line 10
Location   : db/migration/V2__identity_wallet_ledger.sql ... Line : 108
```

**定位**

定位过程中先怀疑了两个方向，都被排除：

1. **怀疑是脚本编码问题**。错误信息里的中文显示为乱码，看着像 Flyway 读错了字符集。
   排除依据：乱码出现在**服务端返回的报错文本**里，而报错文本经 JDBC 到 Java 再打到 GBK 控制台，显示乱码是预期现象；真正的语法错误位置与中文无关。不过仍然顺手把 Flyway 的读取编码显式固定为 UTF-8（见修复②）。
2. **怀疑是 `MODIFY COLUMN` 不能和 `ADD COLUMN` 写在同一条 ALTER 里**。查语法后确认可以，排除。

真正的错误在第 108 行这句：

```sql
ADD COLUMN result_price BIGINT NULL AFTER status COMMENT '首次结果的成交价快照，用于幂等重放',
```

MySQL 的列定义语法里，`FIRST | AFTER col_name` 是**最后一个**可选子句，必须排在 `COMMENT`、`DEFAULT` 等属性之后。写成 `AFTER status COMMENT '...'` 会让解析器在 `COMMENT` 处报错——这也解释了错误信息为什么正好停在 `COMMENT` 上。

**修复**

① 把位置子句移到所有属性之后：

```sql
ADD COLUMN result_price BIGINT NULL COMMENT '首次结果的成交价快照，用于幂等重放' AFTER status,
```

同一处 `status` / `result_seq` / `updated_at` 三个 `ADD COLUMN` 都有同样问题，一并改正，并在脚本里留了一行注释说明该语法陷阱。

② 在 Flyway 配置里显式指定脚本编码，不再依赖平台默认值：

```java
Flyway.configure().dataSource(ds).locations(MIGRATION_LOCATION)
      .encoding(StandardCharsets.UTF_8)
```

**修复过程中的第二个坑（重要）**

MySQL 的 DDL 会隐式提交，所以 V2 失败时：`users` / `wallets` / `ledger_entries` 和 `auctions` / `bids` 的变更**已经落库**，只有出错的 `bid_requests` 那条 ALTER 因 8.0+ 的原子 DDL 回滚，而 `flyway_schema_history` 里留下了一条 `success = 0` 的记录：

```
1  1  auction schema          1     <- V1 成功
2  2  identity wallet ledger  0     <- V2 失败
```

我没有采用 `flyway repair` 跳过这条记录，也没有手工补 `bid_requests` 的列。原因是这两种做法都会让"本机当前的库"与"别人从零跑一遍得到的库"产生差异，而迁移脚本的正确性恰恰是必须证明的东西。

**选择整库重建**：

```sql
DROP DATABASE IF EXISTS bid_arena;
CREATE DATABASE bid_arena CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

**验证**

重建后由应用启动时重新执行迁移，V1、V2 均成功：

```
+----------------+---------+------------------------+------+---------+
| installed_rank | version | description            | type | success |
+----------------+---------+------------------------+------+---------+
|              1 | 1       | auction schema         | SQL  |       1 |
|              2 | 2       | identity wallet ledger | SQL  |       1 |
+----------------+---------+------------------------+------+---------+
```

顺带获得了更有价值的证据：**从零建库到可用 schema 的完整路径是通的**，而不是"在我这台机器上恰好能跑"。中文种子数据也做了往返校验（`title = '演示拍品 · 复古机械键盘'` 返回 1），确认编码无损。

---

## DBG-2：改完迁移脚本，重跑报的还是同一个错误

**现象**

按 DBG-1 改好 SQL 后重新构建并启动，报出的错误**和修改前一模一样**，错误信息里的 SQL 片段仍是旧顺序。

**日志与证据**

错误信息里 `COMMENT` 后面直接跟逗号，而修改后的语句里 `COMMENT` 后面应该是 `AFTER status`：

```
Message : ... near 'COMMENT '首次结果的成交价快照，用于幂等重放',
```

对比源文件与被执行的副本：

```
-rw-r--r-- 9711  db/migration/V2__identity_wallet_ledger.sql              17:52:45   <- 已修改
-rw-r--r-- 9452  target/classes/db/migration/V2__identity_wallet_ledger.sql  17:52:06   <- 仍是旧内容
```

两者内容不一致，确认应用执行的是**旧的副本**。

**定位**

迁移脚本按 `docs/DOCS.md` 的约定放在仓库根目录 `db/migration/`（评审直接可见），通过 `pom.xml` 的 `<resources>` 映射进 classpath。`mvn process-resources` 判定目标文件为最新，跳过了拷贝。单独重跑该 goal 时又正常拷贝：

```
[INFO] --- resources:3.4.0:resources (default-resources) @ bid-arena-core ---
[INFO] Copying 2 resources from db\migration to target\classes\db\migration
[INFO] skip non existing resourceDirectory ...\src\main\resources
```

也就是说这是**增量构建的判定问题**，不是配置错误。

**修复**

开发时改完迁移脚本必须让资源重新落盘。做了两件事：

1. 本轮的排查改成显式重跑 `mvn process-resources` 并用 `diff` 确认副本与源一致，不再凭"我改过了"下结论。
2. 把这条约束写进验证纪律：**面向评审的一键测试命令使用 `mvn clean verify`**，避免评审机器上出现"脚本已改但副本是旧的"这种不可复现的假失败。

**验证**

重跑后副本与源一致（9711 字节），应用重新执行迁移成功，即 DBG-1 的验证结果。

**为什么保留根目录 `db/migration/` 而不搬到 `src/main/resources/`**

搬到标准位置可以绕开这个坑，但会牺牲"迁移脚本在仓库根目录一眼可见"。这个坑的根源是增量构建，不是目录位置——搬到标准目录同样会在源文件时间戳判定异常时踩到。因此保留位置，用 `mvn clean` 消除不确定性。

---

## DBG-3：GitHub API 返回 400 Problems parsing JSON

**现象**

创建远程仓库时，`POST https://api.github.com/user/repos` 返回：

```
HTTP 400  { "message": "Problems parsing JSON" }
```

JSON 是用 `curl -d '{"name":"bid-arena","description":"多人实时拍卖间 ..."}'` 直接写在命令行里的。

**定位**

怀疑过令牌权限不足，但令牌对 `GET /user` 验证通过（可读 `/user`），说明认证没问题——400 是请求体解析失败，不是 401/403。

真正的原因是 **Windows 控制台代码页为 936(GBK)**：命令行里的中文按 GBK 编码传给 `curl`，而 GitHub 期望 UTF-8，字节序列非法于是解析失败。用 `od -An -tx1` 看请求体确认了中文段是 GBK 字节。

**修复**

用工具把 JSON 写成 UTF-8 文件，再以字节原样提交，彻底绕开命令行编码：

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     --data-binary @/d/tmp/repo.json https://api.github.com/user/repos
```

**验证**

返回 `HTTP 201`，仓库创建成功，且中文描述在页面上显示正常：

```
"full_name": "Ayong-ui/bid-arena"
"description": "多人实时拍卖间 · 服务端唯一裁决的资金一致性系统 | Solon 3 + MySQL 8 + Vue 3 + WebSocket + Agent API"
```

**留存的经验**：本机任何"含中文的请求体"都不要走命令行内联，一律用 UTF-8 文件 + `--data-binary`。后续的 topics 设置与本文档中的 SQL 校验脚本都沿用了这个做法。

---

## DBG-4：MySQL 8.4 移除了 `default_authentication_plugin`

**现象**

新建的 MySQL 8.4.9 容器起来后，用一句"例行体检"SQL 查环境，报：

```
ERROR 1193 (HY000): Unknown system variable 'default_authentication_plugin'
```

**定位**

不是容器有问题，是 **MySQL 8.4 删除了这个系统变量**（8.4 起由 `authentication_policy` 取代，同时 `mysql_native_password` 插件被移出内置）。原来的体检语句是按 5.7/8.0 的习惯写的。

**修复与影响**

体检语句改用 `@@authentication_policy`。更重要的是这次报错牵出一个会影响运行的问题：8.4 的默认认证插件是 `caching_sha2_password`，而 JDBC 在**非 TLS** 连接下首次认证需要取服务端公钥，否则报 `Public Key Retrieval is not allowed`。因此连接串必须带：

```
allowPublicKeyRetrieval=true
```

这一项已写进 `.env.example` 并附注释说明原因，否则后续换机器部署时必然复现。

**验证**

```
v: 8.4.9   cs: utf8mb4   coll: utf8mb4_0900_ai_ci
iso: REPEATABLE-READ     auth_policy: *,,    lock_wait: 50
```

后端随后成功连库并完成迁移（见 DBG-1 验证）。

**需要记住的运行时前提**：隔离级别是 InnoDB 默认的 `REPEATABLE-READ`。本方案依赖 `SELECT ... FOR UPDATE`（当前读）而不是快照读，因此该隔离级别不影响正确性。

---

## DBG-5：开发机 VM 访问不了 Docker Hub，拉不到 `mysql:8.4`

**现象**

在开发用的 VM（Docker 守护进程所在机器）上准备数据库容器，拉镜像超时：

```
docker: Error response from daemon: Get "https://registry-1.docker.io/v2/":
net/http: request canceled while waiting for connection (Client.Timeout exceeded while awaiting headers)
```

**定位**

先确认不是镜源配置问题——`docker info` 里没有任何 registry mirror 配置，是 VM 出网受限。而镜像库里已经存在同大版本、来自内网私有仓库的 `.../01xq/mysql:8.4.9`（就是该 VM 上另一个项目在用的那个）。

在决定复用它之前，先确认它确实是官方 MySQL 镜像而不是被改过的：

```
Entrypoint:   ["docker-entrypoint.sh"]
Cmd:          ["mysqld"]
ExposedPorts: {"3306/tcp":{},"33060/tcp":{}}
VOLUME [/var/lib/mysql]
RUN microdnf install -y "mysql-shell-$MYSQL_SHELL_VERSION"
```

入口脚本、暴露端口、数据卷、包管理器都与官方镜像一致，是可信的等价镜像。

**修复**

只在本机给已有镜像加一个标签，不改任何服务、不改守护进程配置：

```bash
docker tag .../01xq/mysql:8.4.9 mysql:8.4
```

**关键点：`docker-compose.yml` 里仍然写公共镜像名 `mysql:8.4`**，没有改成那个私有仓库地址。因为 Compose 文件是交付物，必须能在评审机器上直接拉取；私有仓库地址对他人不可用，且会泄露内部仓库信息。私网镜像只用于我这台开发机。

**验证**

```
bid-arena-mysql-1 | mysql:8.4 | Up ... (healthy) | 0.0.0.0:3307->3306/tcp, [::]:3307->3306/tcp
```

容器健康，宿主侧（Windows）到 `192.168.117.128:3307` 可达（`TcpTestSucceeded = True`），后端成功连库完成迁移。

---

## DBG-6：`kill` 报成功但 JVM 没死，"健康检查通过"可能是旧进程在应答

**现象**

连续两轮启动验证：第二轮日志里明明报了端口被占，但紧接着的 `curl` 健康检查**依然返回 200**：

```
2026-09-12 17:56:24.198 ERROR [main] org.noear.solon.Solon - Solon start failed: AppContext start failed
  Caused by: java.net.BindException: Address already in use: bind

$ curl -s http://127.0.0.1:8080/api/v1/health
{"status":"UP","time":"2026-09-12T09:56:34Z","service":"bid-arena"}
```

如果只看这一行结果，结论会是"服务起来了，健康检查通过"——**完全错误**。

**定位**

`netstat -ano` 显示 8080 与 18080 仍被一个旧 PID 占用：

```
TCP 0.0.0.0:8080   LISTENING  31324
TCP 0.0.0.0:18080  LISTENING  31324

$ jps -l
31324 com.bidarena.Application
```

原因是此前几轮验证用 `kill $APP_PID` 收尾。在 Windows 的 Git Bash 下，`kill` 会打印成功、返回 0，但 JVM 并未退出（MSYS 的 PID 映射与 Windows 原生进程号不一致），于是实例一轮轮累积下来。

**为什么这个坑比前五条都危险**

前五条都会以“启动失败”或“报错”的形式当场暴露；这一条会**伪装成成功**。健康检查返回的 `serverTime` 是当次请求算出来的当前时间，新旧实例的响应长得一模一样，肉眼无法区分。如果没发现，后续所有"接口调通了""迁移生效了"的结论都可能建立在旧代码上。

**修复**

① 改用 Windows 原生方式结束进程：

```bash
NEWPID=$(jps -l | grep com.bidarena.Application | awk '{print $1}')
taskkill //F //PID $NEWPID
```

② 更重要的一步：在验证脚本里加**进程归属断言**——先取监听端口的 PID，再与本轮启动的 PID 比对，不一致则直接判失败：

```bash
NEWPID=$(jps -l | grep com.bidarena.Application | awk '{print $1}')
for P in 8080 18080; do
  OWNER=$(netstat -ano | grep ":$P " | grep LISTENING | awk '{print $5}' | head -1)
  [ "$OWNER" = "$NEWPID" ] || echo "端口 $P 属于 PID $OWNER，不是本次启动的 $NEWPID"
done
```

**验证**

加上断言后重跑，两个端口都属于本次启动的进程，此时健康检查才算是**本次代码**的证据：

```
应用 PID = 19852
  端口 8080  -> PID 19852
  端口 18080 -> PID 未监听
  端口 18081 -> PID 19852
```

**留存的经验**：对本项目而言，“服务能响应”不等于“我的代码能响应”。后续压测与结算验证脚本同样会保留这个断言，否则并发测试极易在错误的进程上跑出一个漂亮的结论。
