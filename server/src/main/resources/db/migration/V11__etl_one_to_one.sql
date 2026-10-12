-- 一个任务固定一套映射：把 etl_mapping 的业务列合并进 etl_task，然后删除 etl_mapping。
ALTER TABLE etl_task
    ADD COLUMN input_type          VARCHAR(16),
    ADD COLUMN source_schema       VARCHAR(128),
    ADD COLUMN source_table        VARCHAR(128),
    ADD COLUMN select_sql          TEXT,
    ADD COLUMN parameters_json     TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN target_schema       VARCHAR(128),
    ADD COLUMN target_table        VARCHAR(128),
    ADD COLUMN target_table_mode   VARCHAR(16) NOT NULL DEFAULT 'CREATE',
    ADD COLUMN write_mode          VARCHAR(16) NOT NULL DEFAULT 'APPEND',
    ADD COLUMN field_mappings_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN upsert_keys_json    TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN create_table_ddl    TEXT;

UPDATE etl_task t
SET input_type = m.input_type,
    source_schema = m.source_schema,
    source_table = m.source_table,
    select_sql = m.select_sql,
    parameters_json = m.parameters_json,
    target_schema = m.target_schema,
    target_table = m.target_table,
    target_table_mode = m.target_table_mode,
    write_mode = m.write_mode,
    field_mappings_json = m.field_mappings_json,
    upsert_keys_json = m.upsert_keys_json
FROM (
    SELECT DISTINCT ON (task_id) *
    FROM etl_mapping
    ORDER BY task_id, sort_order, id
) m
WHERE m.task_id = t.id;

DROP TABLE etl_mapping;
