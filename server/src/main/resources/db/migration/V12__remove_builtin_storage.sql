-- 移除默认存储库：清理内置源及其业务数据，删除相关列，回收租户数据 schema。
-- 破坏性清理，执行前需 pg_dump 备份 daaspro 库。

DELETE FROM api_grant WHERE api_id IN (
    SELECT p.id FROM api_pub p
    JOIN dataset d ON p.dataset_id = d.id
    JOIN datasource s ON s.id = d.datasource_id
    WHERE s.builtin);

DELETE FROM api_pub WHERE dataset_id IN (
    SELECT d.id FROM dataset d JOIN datasource s ON s.id = d.datasource_id WHERE s.builtin);

DELETE FROM dataset WHERE datasource_id IN (SELECT id FROM datasource WHERE builtin);

DELETE FROM etl_run_attempt WHERE run_id IN (
    SELECT r.id FROM etl_run r JOIN etl_task t ON r.task_id = t.id
    WHERE t.source_datasource_id IN (SELECT id FROM datasource WHERE builtin)
       OR t.target_datasource_id IN (SELECT id FROM datasource WHERE builtin));

DELETE FROM etl_run WHERE task_id IN (
    SELECT t.id FROM etl_task t
    WHERE t.source_datasource_id IN (SELECT id FROM datasource WHERE builtin)
       OR t.target_datasource_id IN (SELECT id FROM datasource WHERE builtin));

DELETE FROM etl_task
WHERE source_datasource_id IN (SELECT id FROM datasource WHERE builtin)
   OR target_datasource_id IN (SELECT id FROM datasource WHERE builtin);

DELETE FROM datasource WHERE builtin;

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT DISTINCT 'tenant_' || slug AS sname FROM tenant LOOP
        EXECUTE 'DROP SCHEMA IF EXISTS "' || r.sname || '" CASCADE';
    END LOOP;
END $$;

ALTER TABLE datasource DROP COLUMN builtin;
ALTER TABLE tenant DROP COLUMN schema_name;
