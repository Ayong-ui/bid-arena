## 行为变化

<!-- 说清"改了什么行为"，而不是"改了哪些文件"。
     例：feat: freeze wallet balance when bid becomes leader -->

## 关联验收项

<!-- 引用 docs/TRACEABILITY.md 的编号，如 A4 / B7 / C3；没有则写"无" -->

## DoD 自检（CONTRIBUTING.md §4）

- [ ] 代码可编译，服务可启动
- [ ] 相关测试已新增或更新，并且通过
- [ ] 涉及接口改动 → `docs/openapi.yaml` **先于代码**已同步
- [ ] 按 `docs/DOCS.md` §5 改动联动表同步了受影响文档
- [ ] 修复真实 bug → 已记入 `DEBUG_LOG.md`
- [ ] 使用 AI 或更换方案 → 已记入 `AI_USAGE.md` / `DECISIONS.md`
- [ ] `README.md` 未完成边界已更新
- [ ] `docs/STATUS.md`、`docs/TRACEABILITY.md` 已更新

## 验证证据

<!-- 贴实际命令与结果，不写"应该没问题"。
     并发与结算相关必须是真实 MySQL 集成测试，不能是内存 Mock。 -->

```
mvn -q test-compile
mvn test
```

## 失败分支与回滚

<!-- 这次改动最可能怎么失败？失败时用户会看到什么？如何恢复？ -->
