package com.datalore.etl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("etl_task")
public class EtlTaskEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Long sourceDatasourceId;
    private Long targetDatasourceId;
    private String status;
    private String scheduleType;
    private LocalDateTime onceAt;
    private String cronExpression;
    private LocalDateTime scheduleEndAt;
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
    private String createTableDdl;
    private Integer maxRetries;
    private Integer retryIntervalSeconds;
    private Integer configVersion;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
