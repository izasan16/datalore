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
