# 子计划 1：Datalore 项目骨架

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭起可一键启动的全链路骨架：Docker Compose 基础设施 + Spring Boot 单体（Flyway 建首个迁移）+ React/Arco 前端，前端页面能调通后端 `/api/health` 显示版本号。

**Architecture:** 单仓库 monorepo：`server/`（Spring Boot 3 / Java 17 / Maven 单模块）+ `web/`（Vite + React18 + TS + Arco）+ `deploy/docker-compose.yml`。后端 `/api/health` 返回从 application.yml 读的版本号，前端启动时请求它渲染到页面上，验证跨域与代理链路。

**Tech Stack:** Spring Boot 3.3.x、MyBatis-Plus 3.5.7、Flyway 10、Sa-Token（本计划仅引依赖不启用）、Vite 5、React 18、Arco Design 2.x、Docker Compose（postgres:16 / redis:7 / minio）。

**验证环境事实（已核实）：** Java 21.0.9、Maven 3.9.11、Node 22.20、npm 10.9.3、Docker 29.7.2（Linux 容器）均可用。Java 21 兼容目标 17（用 `--release 17` 编译）。

---

### Task 1: 仓库根与 Compose 基础设施

**Files:**
- Create: `.gitignore`
- Create: `deploy/docker-compose.yml`
- Create: `deploy/.env.example`
- Create: `README.md`

- [ ] **Step 1: 写 .gitignore**

```gitignore
# build
server/target/
web/dist/
web/node_modules/
*.class
.idea/
*.iml
.vscode/
.env
logs/
```

- [ ] **Step 2: 写 deploy/.env.example**

```env
POSTGRES_PASSWORD=Datalore_dev_2026
REDIS_PASSWORD=
MINIO_ROOT_USER=Datalore
MINIO_ROOT_PASSWORD=Datalore_dev_2026
```

- [ ] **Step 3: 写 deploy/docker-compose.yml**

```yaml
services:
  postgres:
    image: postgres:16
    container_name: Datalore-postgres
    environment:
      POSTGRES_DB: Datalore
      POSTGRES_USER: Datalore
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "15432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U Datalore"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7
    container_name: Datalore-redis
    ports:
      - "16379:6379"
    volumes:
      - redisdata:/data

  minio:
    image: minio/minio
    container_name: Datalore-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    ports:
      - "19000:9000"
      - "19001:9001"
    volumes:
      - miniodata:/data

volumes:
  pgdata:
  redisdata:
  miniodata:
```

- [ ] **Step 4: 写 README.md（简要：项目定位一句话 + 启动命令）**

```markdown
# Datalore

面向数据分析师和数据开发工程师的多租户 SaaS 数据平台。设计见 `docs/架构设计.md`，需求共识见 `docs/重构需求共识.md`。

## 本地启动

​```bash
cd deploy
cp .env.example .env
docker compose up -d      # PG 15432 / Redis 16379 / MinIO 19000,19001
​```

后端：`server/`（`mvn spring-boot:run`，端口 8080）
前端：`web/`（`npm i && npm run dev`，端口 5173，代理 /api → 8080）
```

（注意：README 中代码块内的 ``` 转义在写入时用真实反引号。）

- [ ] **Step 5: 启动并验证**

Run: `cd deploy && cp .env.example .env && docker compose up -d && docker compose ps`
Expected: postgres/redis/minio 三个容器 `running`，postgres 显示 `(healthy)`。

- [ ] **Step 6: Commit**

```bash
git add .gitignore README.md deploy
git commit -m "chore: repo skeleton with docker compose infra"
```

---

### Task 2: Spring Boot 骨架 + Flyway 首个迁移

**Files:**
- Create: `server/pom.xml`
- Create: `server/src/main/java/com/Datalore/DataloreApplication.java`
- Create: `server/src/main/java/com/Datalore/common/web/HealthController.java`
- Create: `server/src/main/resources/application.yml`
- Create: `server/src/main/resources/db/migration/V1__baseline.sql`
- Create: `server/src/test/java/com/Datalore/HealthIT.java`

- [ ] **Step 1: 写 server/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.Datalore</groupId>
  <artifactId>Datalore-server</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>17</java.version>
    <mybatis-plus.version>3.5.7</mybatis-plus.version>
    <sa-token.version>1.39.0</sa-token.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
      <version>${mybatis-plus.version}</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>cn.dev33</groupId>
      <artifactId>sa-token-spring-boot3-starter</artifactId>
      <version>${sa-token.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 写 DataloreApplication.java**

```java
package com.Datalore;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.Datalore.**.mapper")
public class DataloreApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataloreApplication.class, args);
    }
}
```

- [ ] **Step 3: 写 application.yml**

```yaml
server:
  port: 8080
  servlet:
    encoding:
      charset: UTF-8
      force: true

spring:
  application:
    name: Datalore
  datasource:
    url: jdbc:postgresql://localhost:15432/Datalore
    username: Datalore
    password: Datalore_dev_2026
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: localhost
      port: 16379

Datalore:
  version: 0.1.0

sa-token:
  token-name: Authorization
  is-contract: false
```

- [ ] **Step 4: 写 HealthController.java（先写实现，测试在下一步 TDD 校验）**

```java
package com.Datalore.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${Datalore.version}")
    private String version;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "version", version);
    }
}
```

- [ ] **Step 5: 写 V1__baseline.sql（仅建 flyway 锚点表空间约定，业务表后续迁移加）**

```sql
-- baseline: 无业务表。租户/用户等表在 V2+。
SELECT 1;
```

- [ ] **Step 6: 写 HealthIT.java（集成测试，需 PG 在跑）**

```java
package com.Datalore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIT {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthReturnsVersion() {
        var body = rest.getForEntity("/api/health", String.class).getBody();
        assertThat(body).contains("\"status\":\"ok\"").contains("0.1.0");
    }
}
```

- [ ] **Step 7: 跑集成测试**

Run: `cd server && mvn -q verify`
Expected: BUILD SUCCESS，HealthIT 通过（Flyway 执行 V1）。

- [ ] **Step 8: Commit**

```bash
git add server
git commit -m "feat(server): spring boot skeleton with flyway baseline and /api/health"
```

---

### Task 3: 前端骨架（Vite + React + Arco）

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/tsconfig.json`
- Create: `web/index.html`
- Create: `web/src/main.tsx`
- Create: `web/src/App.tsx`
- Create: `web/src/api/http.ts`

- [ ] **Step 1: 写 web/package.json**

```json
{
  "name": "Datalore-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "@arco-design/web-react": "^2.66.0",
    "axios": "^1.7.9",
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "^5.6.3",
    "vite": "^5.4.11"
  }
}
```

- [ ] **Step 2: 写 web/vite.config.ts（代理 /api → 8080，解决跨域）**

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

- [ ] **Step 3: 写 web/tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true
  },
  "include": ["src"]
}
```

- [ ] **Step 4: 写 web/index.html**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Datalore</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: 写 web/src/api/http.ts**

```ts
import axios from 'axios';

export const http = axios.create({ baseURL: '/api', timeout: 15000 });

export async function getHealth(): Promise<{ status: string; version: string }> {
  const res = await http.get('/health');
  return res.data;
}
```

- [ ] **Step 6: 写 web/src/App.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Typography, Card, Tag } from '@arco-design/web-react';
import { getHealth } from './api/http';

export default function App() {
  const [version, setVersion] = useState('...');
  useEffect(() => {
    getHealth().then((d) => setVersion(d.version)).catch(() => setVersion('unreachable'));
  }, []);
  return (
    <Card style={{ maxWidth: 480, margin: '80px auto' }}>
      <Typography.Title heading={3}>Datalore</Typography.Title>
      <Typography.Text>后端版本：</Typography.Text>
      <Tag color="arcoblue">{version}</Tag>
    </Card>
  );
}
```

- [ ] **Step 7: 写 web/src/main.tsx**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import '@arco-design/web-react/dist/css/arco.css';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
```

- [ ] **Step 8: 安装依赖并构建验证**

Run: `cd web && npm install && npm run build`
Expected: `vite build` 成功产出 dist/。

- [ ] **Step 9: 全链路手工验证**

Run: 后端 `cd server && mvn spring-boot:run`；前端 `cd web && npm run dev`；浏览器开 http://localhost:5173
Expected: 页面卡片显示「后端版本：0.1.0」。

- [ ] **Step 10: Commit**

```bash
git add web
git commit -m "feat(web): vite+react+arco skeleton calling /api/health"
```

---

## Self-Review 结论

- 规格覆盖：子计划 1 对应架构设计迭代路线 #1，范围一致（骨架 + 健康页全链路），不涉及业务功能。
- 占位符：无 TBD/TODO；所有代码完整给出。
- 类型一致性：前端 `getHealth` 返回结构与后端 `HealthController` 的 Map 键一致（status/version）。
- 已知风险：Java 21 编译 target 17 由 spring-boot-starter-parent 的 java.version=17 处理；中文路径风险记录在架构设计文档 §4。
