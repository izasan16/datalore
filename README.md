# daaspro

面向数据分析师和数据开发工程师的多租户 SaaS 数据平台。设计见 `docs/架构设计.md`，需求共识见 `docs/重构需求共识.md`。

## 本地启动

```bash
cd deploy
cp .env.example .env
docker compose up -d      # PG 15432 / Redis 16379 / MinIO 19000,19001
```

后端：`server/`（`mvn spring-boot:run`，端口 8080）
前端：`web/`（`npm i && npm run dev`，端口 5173，代理 /api → 8080）

## SQL 网关安全边界（MVP）

`/api/sql/run` 的防护层（实现见 `server/src/main/java/com/daaspro/sqllab/`）：

1. **单语句**：注释/字符串剥离后按分号计数判定；
2. **首关键字白名单**：内置数据源允许查询/DDL/DML；外部数据源仅查询；
3. **黑名单模式**：pg 函数、public. 与 information_schema. 前缀、提权/运维语句、其它租户 schema 引用全部拒绝（校验基于归一化文本——注释与字符串无法绕过，也不会误杀字符串内容）；
4. **连接级硬限制**：语句超时 30s、结果 10000 行截断（`daaspro.sql.*` 配置）。

已知残余风险：非常见 schema 的全限定名仍可探测；后续以「每租户独立 DB 角色 + grants」最小权限强化。
