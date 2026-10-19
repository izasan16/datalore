# Datalore

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

`/api/sql/run` 的防护层（实现见 `server/src/main/java/com/Datalore/sqllab/`）：

1. **单语句**：注释/字符串剥离后按分号计数判定；
2. **首关键字白名单**：内置数据源允许查询/DDL/DML；外部数据源仅查询；
3. **黑名单模式**：pg 函数、public. 与 information_schema. 前缀、提权/运维语句、其它租户 schema 引用全部拒绝（校验基于归一化文本——注释与字符串无法绕过，也不会误杀字符串内容）；
4. **连接级硬限制**：语句超时 30s、结果 10000 行截断（`Datalore.sql.*` 配置）。

已知残余风险：非常见 schema 的全限定名仍可探测；后续以「每租户独立 DB 角色 + grants」最小权限强化。

## API 发布安全边界（MVP）

API 发布只允许将平台数据集以只读 REST API 公开，不支持发布任意 SQL。外部调用使用请求头 `X-Api-Key` 与 `X-Api-Secret`，API 与应用之间必须配置授权关系；未授权应用、已停用 API 或不存在的路径均不会返回数据。

当前实现的安全约束：

- AppSecret 使用 AES-GCM 加密存储，创建应用时明文只返回一次；管理页仅在明确操作后查看。
- API 路径全局唯一；查询字段、排序字段和过滤字段只能来自发布配置。
- 过滤值使用 JDBC 参数绑定，表名和列名使用受控配置并进行标识符转义。
- 单次查询受 API 自身的最大行数限制，SQL 网关仍受 30 秒超时和 10000 行上限保护。
- `/api/open/**` 是免会话端点，由 API 发布服务自行校验应用凭证和授权关系。

MVP 的已知边界：请求头中的密钥依赖 HTTPS 保护；暂未提供 HMAC 签名、调用限流、调用审计、订阅计量、字段级脱敏和基于 SQL 的 API。上述能力属于后续迭代。
