# 数据开发平台部署指南

## 前提

- 服务器已安装 Docker 24+ 和 Docker Compose v2
- 服务器能访问 Docker Hub（或已配置镜像加速）
- 代码已同步到服务器（git clone / scp）

## 步骤

### 1. 准备环境变量

```bash
cd deploy
cp .env.example .env
vi .env
```

必填项：
- `POSTGRES_PASSWORD`：PostgreSQL 密码
- `MINIO_ROOT_PASSWORD`：MinIO 密码
- `DAASPRO_ETL_WORKER_TOKEN`：Worker 通信 Token（随机字符串）
- `DAASPRO_CRYPTO_KEY`：数据源密码加密密钥，用以下命令生成：
  ```bash
  openssl rand -base64 32
  ```

### 2. 构建并启动

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

首次构建约 5-10 分钟（拉取基础镜像 + 编译后端/前端）。

### 3. 验证

```bash
# 查看容器状态
docker compose -f docker-compose.prod.yml ps

# 健康检查
curl http://localhost/api/health
```

浏览器打开 `http://<服务器IP>` 即可访问（默认 80 端口，可用 `.env` 中 `WEB_PORT` 修改）。

默认管理员账号：etluxadmin / etlux123456（首次登录后请修改）。

### 4. 常用运维命令

```bash
# 查看日志
docker compose -f docker-compose.prod.yml logs -f server

# 重启单个服务
docker compose -f docker-compose.prod.yml restart server

# 停止
docker compose -f docker-compose.prod.yml down

# 停止并删除数据卷（危险！会清空所有数据）
docker compose -f docker-compose.prod.yml down -v
```

### 5. 可选：启用 Hive / Spark / Flink

```bash
docker compose -f docker-compose.prod.yml --profile hive up -d
```

## 架构

```
[web (nginx:80)]
  → /api/* → [server (Spring Boot:8080)]
  → 静态文件

[server]
  → [postgres:5432] 元数据
  → [redis:6379] 会话
  → [minio:9000] 文件暂存
  → [etl-worker:8090] ETL 执行
```
