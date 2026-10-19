package com.datalore.imported.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

@Data
@TableName("imported_table")
public class ImportedTableEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String tableName;
    private String schemaName;
    private Long datasourceId;
    /** JSONB 列：MyBatis 需要 JdbcType.OTHER 才能写入 PG jsonb */
    @TableField(jdbcType = JdbcType.OTHER)
    private String columnsDef;
    private Long rowCount;
    private String fileName;
    private LocalDateTime createdAt;
}
