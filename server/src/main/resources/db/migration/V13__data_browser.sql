-- 数据管理重定位：数据集概念收敛为"导入记录"；API 发布直接指向 数据源+schema+表

ALTER TABLE dataset RENAME TO imported_table;
ALTER TABLE imported_table ADD COLUMN schema_name VARCHAR(128);
ALTER TABLE imported_table RENAME CONSTRAINT dataset_tenant_id_name_key TO imported_table_tenant_name_key;
ALTER INDEX IF EXISTS idx_dataset_tenant RENAME TO idx_imported_table_tenant;

ALTER TABLE api_pub ADD COLUMN datasource_id BIGINT;
ALTER TABLE api_pub ADD COLUMN schema_name VARCHAR(128);
ALTER TABLE api_pub ADD COLUMN table_name VARCHAR(128);

UPDATE api_pub a
SET datasource_id = d.datasource_id,
    schema_name   = d.schema_name,
    table_name    = d.table_name
FROM imported_table d
WHERE a.dataset_id = d.id;

ALTER TABLE api_pub ALTER COLUMN datasource_id SET NOT NULL;
ALTER TABLE api_pub ALTER COLUMN table_name SET NOT NULL;
ALTER TABLE api_pub DROP COLUMN dataset_id;

