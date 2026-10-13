# 手动测试演示环境

本目录提供一套独立的演示业务库和测试资料，照着《手测用例.md》即可走完平台全部功能。

## 演示库登记信息

| 项 | 值 |
| --- | --- |
| 名称（随意） | 演示业务库 |
| 类型 | PostgreSQL |
| 主机 | localhost |
| 端口 | 15432 |
| 数据库 | business_demo |
| Schema | sales（源数据）/ ods（ETL 目标，初始为空） |
| 用户名 | daaspro |
| 密码 | 与平台库相同（daaspro_dev_2026） |

表清单：`sales.customers`（30 行）、`sales.products`（20 行）、`sales.orders`（200 行）、`sales.order_items`（400 行）、`sales.sales_daily`（2000 行）。

## 文件清单

| 文件 | 用途 |
| --- | --- |
| `手测用例.md` | 按模块分步的测试用例（从这里开始操作） |
| `sql-practices.md` | SQL 分析可复制的练习语句 + 预期结果 |
| `init-demo-db.sql` | 演示库初始化/重置脚本 |
| `csv/销售明细导入.csv` | 上传建表用（含空单元格，验证 NULL 语义） |
| `csv/客户名单导入.csv` | 上传建表用（验证 bigint 类型推断） |
| `csv/销售明细追加.csv` | "追加到已有表"用 |

## 初始化 / 重置演示库

首次初始化或想恢复初始数据时，在仓库根目录执行：

```powershell
docker exec daaspro-postgres createdb -U daaspro business_demo
Get-Content docs/testdata/init-demo-db.sql -Raw -Encoding UTF8 | docker exec -i daaspro-postgres psql -U daaspro -d business_demo
```

库已存在时省略第一行（脚本会重置 sales/ods 两个 schema）。
