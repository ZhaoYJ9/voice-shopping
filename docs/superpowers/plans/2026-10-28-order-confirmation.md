# 订单并发确认修复计划

> 执行方式：在当前工作区直接实施，保留第一个 bug 的未提交修复；按 TDD 验证，完成后独立复核。

**Goal:** 不超卖；同一预览单最多写入一笔订单、扣减一次库存；失败事务允许重试。

**Architecture:** PostgreSQL 商品行锁串行化库存更新。预览阶段生成固定订单号，复用现有 `order_no` 唯一约束；持锁后检查是否已经成交。提交成功后按订单号条件清理 Redis 预览，发布购买事件及回流记忆。

**Tech Stack:** Java 21、Spring Data JPA、PostgreSQL、Redis、JUnit。

**Spec:** 本会话第二个审查问题及用户“开工，第二个”的修复要求。

## 约束与边界

- 不更改真实支付、物流或其他调试入口，不修改生产数据库。
- 使用独立的临时 PostgreSQL/Redis；集成测试使用随机独立 schema 和会话 ID。
- 修复阶段仅在本地验证；后续按用户授权分三批提交并推送 GitHub/Gitee，本修复为第二批（9 个文件）。无需数据库表结构迁移。
- 旧缓存缺少固定订单号时拒绝确认，要求重新选择，不能为同一旧预览随机生成多个订单号。
- 预览不预占库存。库存只在确认事务中变更。

## Task 1: 确认事务与幂等

**Files:** `OrderService.java`、`PendingOrderStore.java`、`ProductRepository.java`、`OrderRepository.java`、对应测试和 README。

**Interfaces:** 保留 `preview` / `confirm` / `cancel`；增加预览订单号、锁定商品查询、按订单号查询和条件删除预览。

- [x] 真实数据库测试先复现最后一件超卖、同预览重复写单、回滚丢失预览/发送事件。
- [x] 固定订单号与商品行锁；重复确认返回已有订单；数量必须为正。
- [x] Redis 条件删除与通知延迟到提交后；通知失败不改变成交结果。
- [x] 覆盖新预览替换旧预览、提交后缓存清理失败重试、旧缓存、同会话新的购买。
- [x] 运行完整后端和页面测试，独立审查，更新使用说明。
- [x] 停止临时测试服务。

## 验证记录

2026-10-28：在独立 PostgreSQL 17.11 / Redis 上，旧实现的 3 项核心集成测试全部失败；
修复后 8 项集成测试通过。完整后端 163 项通过，0 跳过；页面 11 项通过。
独立审查未发现阻断问题。以上记录为发布前的本地验证结果，未部署。

测试日志：`/tmp/voice-order-concurrency.rvt3Z6/`。本机 SOCKS 代理需在测试 JVM 中设置
`socksNonProxyHosts` / `http.nonProxyHosts` 使回环地址直连；运行需要本机 socket 权限。
