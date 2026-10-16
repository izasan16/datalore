package com.daaspro.datasource.service;

import com.daaspro.common.exception.BizException;

/** 各数据源类型的 JDBC URL 与目录查询。schema/table 参数为受控值，拼接前做单引号转义。 */
public enum JdbcUrls {

    POSTGRESQL {
        @Override
        public String url(String host, int port, String db, String schema) {
            // stringtype=unspecified：字符串参数由数据库按目标列推断类型（批量 INSERT 导入依赖此行为）
            String u = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?stringtype=unspecified";
            return schema == null ? u : u + "&currentSchema=" + schema;
        }
        @Override
        public int defaultPort() { return 5432; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT tablename FROM pg_tables WHERE schemaname = '" + esc(schema) + "' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            // 含默认值表达式、注释与类型参数（长度/精度/小数位），供字段编辑做 diff 与回显
            return "SELECT c.column_name, c.data_type, c.is_nullable,"
                    + " pg_get_expr(d.adbin, d.adrelid) AS column_default,"
                    + " col_description((quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass, c.ordinal_position) AS column_comment,"
                    + " c.character_maximum_length, c.numeric_precision, c.numeric_scale"
                    + " FROM information_schema.columns c"
                    + " LEFT JOIN pg_attrdef d ON d.adrelid = (quote_ident(c.table_schema)||'.'||quote_ident(c.table_name))::regclass"
                    + " AND d.adnum = c.ordinal_position"
                    + " WHERE c.table_schema = '" + esc(schema) + "' AND c.table_name = '" + esc(table) + "'"
                    + " ORDER BY c.ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('pg_catalog', 'information_schema') AND schema_name NOT LIKE 'pg\\_%' ORDER BY 1";
        }
    },
    MYSQL {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        }
        @Override
        public int defaultPort() { return 3306; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + esc(schema) + "' AND table_type = 'BASE TABLE' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, c.column_comment,"
                    + " c.character_maximum_length, c.numeric_precision, c.numeric_scale"
                    + " FROM information_schema.columns c"
                    + " WHERE c.table_schema = '" + esc(schema) + "' AND c.table_name = '"
                    + esc(table) + "' ORDER BY c.ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('mysql', 'sys', 'performance_schema', 'information_schema') ORDER BY 1";
        }
    },
    SQLSERVER {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db + ";encrypt=false";
        }
        @Override
        public int defaultPort() { return 1433; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + esc(schema) + "' AND table_type = 'BASE TABLE' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default,"
                    + " ep.value AS column_comment,"
                    + " c.character_maximum_length, c.numeric_precision, c.numeric_scale"
                    + " FROM information_schema.columns c"
                    + " LEFT JOIN sys.columns sc ON sc.object_id = OBJECT_ID(QUOTENAME(c.table_schema) + '.' + QUOTENAME(c.table_name))"
                    + " AND sc.name = c.column_name"
                    + " LEFT JOIN sys.extended_properties ep ON ep.major_id = sc.object_id AND ep.minor_id = sc.column_id"
                    + " AND ep.class = 1 AND ep.name = 'MS_Description'"
                    + " WHERE c.table_schema = '" + esc(schema) + "' AND c.table_name = '" + esc(table) + "'"
                    + " ORDER BY c.ordinal_position";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT IN "
                    + "('guest', 'INFORMATION_SCHEMA', 'sys', 'db_owner', 'db_accessadmin', 'db_securityadmin', "
                    + "'db_ddladmin', 'db_backupoperator', 'db_datareader', 'db_datawriter', 'db_denydatareader', "
                    + "'db_denydatawriter') ORDER BY 1";
        }
    },
    ORACLE {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + db;
        }
        @Override
        public int defaultPort() { return 1521; }
        @Override
        public String listTablesSql(String schema) {
            return "SELECT table_name FROM all_tables WHERE owner = '" + esc(schema) + "' ORDER BY 1";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "SELECT c.column_name, c.data_type, c.nullable, c.data_default, cc.comments AS column_comment,"
                    + " c.char_length, c.data_precision, c.data_scale"
                    + " FROM all_tab_columns c"
                    + " LEFT JOIN all_col_comments cc ON cc.owner = c.owner AND cc.table_name = c.table_name"
                    + " AND cc.column_name = c.column_name"
                    + " WHERE c.owner = '" + esc(schema) + "' AND c.table_name = '" + esc(table) + "'"
                    + " ORDER BY c.column_id";
        }
        @Override
        public String listSchemasSql() {
            return "SELECT DISTINCT owner FROM all_tables WHERE owner NOT IN ('SYS', 'SYSTEM', 'XDB', 'MDSYS', "
                    + "'CTXSYS', 'ORDSYS', 'OUTLN', 'DBSNMP', 'APPQOSSYS', 'OLAPSYS', 'ORDDATA', 'LBACSYS', "
                    + "'DVSYS', 'AUDSYS', 'GSMADMIN_INTERNAL') ORDER BY 1";
        }
        @Override
        public String primaryKeySql(String schema, String table) {
            return "SELECT cols.column_name FROM all_constraints cons"
                    + " JOIN all_cons_columns cols ON cons.constraint_name = cols.constraint_name"
                    + " AND cons.owner = cols.owner"
                    + " WHERE cons.constraint_type = 'P' AND cons.owner = '"
                    + esc(schema) + "' AND cons.table_name = '" + esc(table) + "' ORDER BY cols.position";
        }
    },
    HIVE {
        @Override
        public String url(String host, int port, String db, String schema) {
            return "jdbc:hive2://" + host + ":" + port + (db == null || db.isBlank() ? "/default" : "/" + db);
        }
        @Override
        public int defaultPort() { return 10000; }
        @Override
        public String listTablesSql(String schema) {
            return "SHOW TABLES IN `" + escId(schema) + "`";
        }
        @Override
        public String listColumnsSql(String schema, String table) {
            return "DESCRIBE `" + escId(schema) + "`.`" + escId(table) + "`";
        }
        @Override
        public String listSchemasSql() {
            return "SHOW DATABASES";
        }
        @Override
        public String primaryKeySql(String schema, String table) {
            // Hive 表无主键约束，调用方需按空主键处理
            return null;
        }    };

    public abstract String url(String host, int port, String db, String schema);
    public abstract int defaultPort();
    public abstract String listTablesSql(String schema);
    public abstract String listColumnsSql(String schema, String table);
    public abstract String listSchemasSql();
    /** 主键列查询；information_schema 版本适用于 PG/MySQL/SQL Server，Oracle 自行覆盖。 */
    public String primaryKeySql(String schema, String table) {
        return "SELECT kcu.column_name FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name"
                + " AND tc.table_schema = kcu.table_schema"
                + " WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = '"
                + esc(schema) + "' AND tc.table_name = '" + esc(table) + "' ORDER BY kcu.ordinal_position";
    }

    public static JdbcUrls of(String type) {
        return switch (type) {
            case "postgresql" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "sqlserver", "oracle" -> throw new BizException(400, "暂不支持的数据源类型: " + type);
                        case "hive" -> HIVE;
            case "spark", "flink" -> throw new BizException(400, "计算引擎类型不支持 JDBC 访问");
            default -> throw new BizException(400, "不支持的数据源类型: " + type);
        };
    }

    /** Hive 标识符转义：反引号成对出现。 */
    private static String escId(String s) {
        return s.replace("`", "``");
    }
    private static String esc(String s) {
        return s.replace("'", "''");
    }
}
