package com.daaspro.etl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("etl_mapping")
public class EtlMappingEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long taskId;
    private Integer sortOrder;
    private String inputType;
    private String sourceSchema;
    private String sourceTable;
    private String selectSql;
    private String parametersJson;
    private String targetSchema;
    private String targetTable;
    private String targetTableMode;
    private String writeMode;
    private String fieldMappingsJson;
    private String upsertKeysJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
