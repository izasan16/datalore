#!/usr/bin/env bash
# 本地构建全部镜像并导出 tar 包（在有网络的机器上执行）
set -e
cd "$(dirname "$0")"

echo "=== 1/4 构建 server 镜像 ==="
docker build -t daaspro/server:latest ../server

echo "=== 2/4 构建 web 镜像 ==="
docker build -t daaspro/web:latest ../web

echo "=== 3/4 构建 etl-worker 镜像 ==="
docker build -f etl-worker/Dockerfile.release -t daaspro/etl-worker:latest ./etl-worker

echo "=== 4/4 拉取基础镜像 ==="
docker pull postgres:16
docker pull redis:7
docker pull minio/minio

echo "=== 导出镜像 ==="
mkdir -p release
docker save daaspro/server:latest daaspro/web:latest daaspro/etl-worker:latest \
  postgres:16 redis:7 minio/minio \
  -o release/daaspro-images.tar

cp docker-compose.release.yml release/
cp .env.example release/.env

echo ""
echo "=== 完成！ ==="
echo "release/ 目录即为交付包，拷贝到服务器后："
echo "  1. cd release"
echo "  2. 修改 .env（密码、Token、加密密钥）"
echo "  3. docker load -i daaspro-images.tar"
echo "  4. docker compose -f docker-compose.release.yml up -d"
