# SQL 分析练习集

前提：已在「数据源」注册演示库 `演示业务库`（见 README.md），并已在「数据管理」上传 `销售明细导入.csv` 生成数据集。

在「SQL 分析」新建脚本，选择数据源 `演示业务库`，逐条粘贴运行（一次只允许单条语句）。

## 1. 基础查询

```sql
SELECT * FROM sales.customers ORDER BY id LIMIT 10
```

预期：返回 10 行客户数据，表格可翻页。

```sql
SELECT city, count(*) AS 客户数 FROM sales.customers GROUP BY city ORDER BY 客户数 DESC
```

预期：按城市分组计数，中文别名正常显示。

## 2. JOIN 与聚合

```sql
SELECT o.order_no, c.name AS 客户, o.amount, o.status
FROM sales.orders o JOIN sales.customers c ON c.id = o.customer_id
ORDER BY o.created_at DESC LIMIT 20
```

```sql
SELECT status, count(*) AS 单数, sum(amount) AS 总金额, round(avg(amount), 2) AS 平均金额
FROM sales.orders GROUP BY status
```

## 3. 大表分页（2000 行）

```sql
SELECT * FROM sales.sales_daily ORDER BY id LIMIT 50 OFFSET 0
```

```sql
SELECT city, sum(gmv) AS 总GMV, sum(orders) AS 总单数
FROM sales.sales_daily GROUP BY city ORDER BY 总GMV DESC
```

## 4. DDL / DML（语句类型不限制）

```sql
CREATE TABLE sql_practice (id BIGINT, note TEXT)
```

预期：执行成功（非查询，无结果表格）。

```sql
INSERT INTO sql_practice VALUES (1, '第一条'), (2, NULL), (3, '第三条')
```

预期：affectedRows = 3。

```sql
SELECT * FROM sql_practice
```

预期：3 行，id=2 的 note 为空。

## 5. 原生报错透传

```sql
SELECT * FROM no_such_table_demo
```

预期：界面直接显示 PG 原生报错，如 `ERROR: relation "no_such_table_demo" does not exist`，没有"平台权限"类包装文案。

```sql
SELECT 1; SELECT 2
```

预期：被拒绝，提示"只允许执行单条语句"。

## 6. 大结果截断（行数上限保护）

```sql
SELECT generate_series(1, 50000) AS n
```

预期：返回前 10000 行左右并提示截断，不会卡死浏览器。

## 7. ETL 用的参数化 SELECT

在「数据集成」向导第 3 步选择输入类型"参数化 SELECT"后使用（不能直接在 SQL 分析执行 `:city` 语法）：

```sql
SELECT city, sum(gmv) AS total_gmv, sum(orders) AS order_cnt
FROM sales.sales_daily WHERE city = :city GROUP BY city
```

命名参数 `city` 会被自动识别；运行时可覆盖参数值，调度执行用默认值。
