package com.datalore.pyscript.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pyscript_run")
public class PyscriptRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scriptId;
    private Long datasourceId;
    private String status;
    private String remoteJobId;
    private String logText;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
