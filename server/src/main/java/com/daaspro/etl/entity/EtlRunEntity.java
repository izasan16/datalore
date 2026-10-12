package com.daaspro.etl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("etl_run")
public class EtlRunEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long tenantId;
    private Long taskId;
    private String triggerType;
    private String status;
    private Integer currentAttempt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long readRows;
    private Long writtenRows;
    private String mappingStatsJson;
    private String errorSummary;
    private String workerRunId;
    private String parametersJson;
    private Integer configVersion;
    private String logObjectPath;
    private LocalDateTime createdAt;
}
