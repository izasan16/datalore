-- ============================================================
-- Datalore 手动测试演示库初始化脚本
-- 目标库: business_demo（在 Datalore-postgres 容器内）
-- 重复执行会重置 sales/ods 两个 schema 下的全部演示数据
-- 用法见同目录 README.md
-- ============================================================

DROP SCHEMA IF EXISTS sales CASCADE;
DROP SCHEMA IF EXISTS ods CASCADE;
CREATE SCHEMA sales;
CREATE SCHEMA ods;

-- ---------- 客户表（30 行，含 NULL 电话） ----------
CREATE TABLE sales.customers (
    id            BIGINT PRIMARY KEY,
    name          TEXT NOT NULL,
    city          TEXT,
    phone         TEXT,
    registered_at DATE
);
INSERT INTO sales.customers (id, name, city, phone, registered_at) VALUES
 (1,'张伟','北京','13801010001','2025-01-12'),
 (2,'李娜','上海','13801010002','2025-01-20'),
 (3,'王强','广州','13801010003','2025-02-03'),
 (4,'刘洋','深圳',NULL,'2025-02-14'),
 (5,'陈静','杭州','13801010005','2025-03-01'),
 (6,'杨帆','成都','13801010006','2025-03-18'),
 (7,'赵磊','北京',NULL,'2025-04-02'),
 (8,'孙丽','武汉','13801010008','2025-04-21'),
 (9,'周杰','西安','13801010009','2025-05-06'),
 (10,'吴敏','南京','13801010010','2025-05-25'),
 (11,'郑涛','重庆','13801010011','2025-06-08'),
 (12,'冯雪','上海',NULL,'2025-06-30'),
 (13,'陈晨','北京','13801010013','2025-07-15'),
 (14,'许诺','广州','13801010014','2025-08-02'),
 (15,'韩梅','深圳','13801010015','2025-08-19'),
 (16,'曹阳','杭州',NULL,'2025-09-07'),
 (17,'谢婷','成都','13801010017','2025-09-23'),
 (18,'邓超','武汉','13801010018','2025-10-11'),
 (19,'高雅','西安',NULL,'2025-11-01'),
 (20,'林峰','南京','13801010020','2025-11-20'),
 (21,'黄磊','重庆','13801010021','2025-12-05'),
 (22,'徐静','北京','13801010022','2025-12-24'),
 (23,'马腾','上海',NULL,'2026-01-09'),
 (24,'朱琳','广州','13801010024','2026-01-27'),
 (25,'胡军','深圳','13801010025','2026-02-13'),
 (26,'郭涛','杭州',NULL,'2026-03-04'),
 (27,'何雨','成都','13801010027','2026-03-22'),
 (28,'高峰','武汉','13801010028','2026-04-10'),
 (29,'罗兰','西安','13801010029','2026-05-02'),
 (30,'宋江','南京',NULL,'2026-05-21');

-- ---------- 产品表（20 行） ----------
CREATE TABLE sales.products (
    id       BIGINT PRIMARY KEY,
    name     TEXT NOT NULL,
    category TEXT,
    price    NUMERIC(10,2),
    stock    INT
);
INSERT INTO sales.products (id, name, category, price, stock) VALUES
 (1,'无线鼠标','外设',129.00,340),
 (2,'机械键盘','外设',399.00,120),
 (3,'USB-C 扩展坞','外设',249.00,85),
 (4,'27寸显示器','显示设备',1499.00,60),
 (5,'4K 显示器','显示设备',2399.00,35),
 (6,'笔记本支架','办公',99.00,200),
 (7,'人体工学椅','办公',899.00,45),
 (8,'升降桌','办公',1599.00,25),
 (9,'降噪耳机','音频',1099.00,150),
 (10,'蓝牙音箱','音频',329.00,180),
 (11,'会议麦克风','音频',599.00,70),
 (12,'摄像头','视频设备',259.00,110),
 (13,'补光灯','视频设备',159.00,220),
 (14,'绿幕','视频设备',199.00,90),
 (15,'固态硬盘 1T','存储',549.00,160),
 (16,'机械硬盘 4T','存储',699.00,75),
 (17,'NAS 主机','存储',2199.00,18),
 (18,'U盘 128G','存储',79.00,500),
 (19,'网线 5米','网络',29.00,600),
 (20,'路由器 WiFi6','网络',459.00,95);

-- ---------- 订单表（200 行，程序生成） ----------
CREATE TABLE sales.orders (
    id          BIGINT PRIMARY KEY,
    order_no    TEXT UNIQUE NOT NULL,
    customer_id BIGINT REFERENCES sales.customers(id),
    amount      NUMERIC(12,2),
    status      TEXT,
    created_at  TIMESTAMP
);
INSERT INTO sales.orders (id, order_no, customer_id, amount, status, created_at)
SELECT g,
       'SO' || lpad(g::text, 6, '0'),
       (g % 30) + 1,
       round((random() * 1980 + 20)::numeric, 2),
       (ARRAY['已支付','待支付','已退款'])[1 + (g % 3)],
       timestamp '2026-08-01 09:00:00' + ((g * 7) || ' hours')::interval
FROM generate_series(1, 200) g;

-- ---------- 订单明细（400 行，程序生成） ----------
CREATE TABLE sales.order_items (
    id         BIGINT PRIMARY KEY,
    order_id   BIGINT REFERENCES sales.orders(id),
    product_id BIGINT REFERENCES sales.products(id),
    quantity   INT,
    unit_price NUMERIC(10,2)
);
INSERT INTO sales.order_items (id, order_id, product_id, quantity, unit_price)
SELECT g,
       (g % 200) + 1,
       (g % 20) + 1,
       1 + (g % 5),
       round((random() * 900 + 25)::numeric, 2)
FROM generate_series(1, 400) g;

-- ---------- 销售日汇总（2000 行，分页/聚合体验用大表） ----------
CREATE TABLE sales.sales_daily (
    id        BIGINT PRIMARY KEY,
    stat_date DATE,
    city      TEXT,
    channel   TEXT,
    gmv       NUMERIC(14,2),
    orders    INT
);
INSERT INTO sales.sales_daily (id, stat_date, city, channel, gmv, orders)
SELECT g,
       DATE '2026-07-01' + (((g - 1) / 20))::int,
       (ARRAY['北京','上海','广州','深圳','杭州','成都','武汉','西安','南京','重庆'])[1 + (g - 1) % 10],
       (ARRAY['线上','线下','小程序'])[1 + (g - 1) % 3],
       round((random() * 48000 + 2000)::numeric, 2),
       20 + (g % 180)
FROM generate_series(1, 2000) g;

-- ---------- 抽查 ----------
SELECT 'customers' AS tbl, count(*) FROM sales.customers
UNION ALL SELECT 'products', count(*) FROM sales.products
UNION ALL SELECT 'orders', count(*) FROM sales.orders
UNION ALL SELECT 'order_items', count(*) FROM sales.order_items
UNION ALL SELECT 'sales_daily', count(*) FROM sales.sales_daily;
