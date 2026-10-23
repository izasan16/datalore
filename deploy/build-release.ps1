# 本地构建全部镜像并导出 tar 包（Windows / 有网络的机器上执行）
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "=== 1/4 构建 server 镜像 ===" -ForegroundColor Cyan
docker build -t datalore/server:latest ../server

Write-Host "=== 2/4 构建 web 镜像 ===" -ForegroundColor Cyan
docker build -t datalore/web:latest ../web

Write-Host "=== 3/4 构建 etl-worker 镜像 ===" -ForegroundColor Cyan
docker build -f etl-worker/Dockerfile.release -t datalore/etl-worker:latest ./etl-worker

Write-Host "=== 4/4 拉取基础镜像 ===" -ForegroundColor Cyan
docker pull docker.m.daocloud.io/library/postgres:16; docker tag docker.m.daocloud.io/library/postgres:16 postgres:16
docker pull docker.m.daocloud.io/library/redis:7; docker tag docker.m.daocloud.io/library/redis:7 redis:7
docker pull docker.m.daocloud.io/minio/minio:latest; docker tag docker.m.daocloud.io/minio/minio:latest minio/minio:latest

Write-Host "=== 导出镜像 ===" -ForegroundColor Cyan
if (!(Test-Path release)) { New-Item -ItemType Directory -Path release }
docker save datalore/server:latest datalore/web:latest datalore/etl-worker:latest postgres:16 redis:7 minio/minio -o release/datalore-images.tar

Copy-Item docker-compose.release.yml release/
Copy-Item .env.example release/.env

Write-Host ""
Write-Host "=== 完成！ ===" -ForegroundColor Green
Write-Host "release/ 目录即为交付包，拷贝到服务器后："
Write-Host "  1. cd release"
Write-Host "  2. 修改 .env（密码、Token、加密密钥）"
Write-Host "  3. docker load -i datalore-images.tar"
Write-Host "  4. docker compose -f docker-compose.release.yml up -d"
