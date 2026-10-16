package com.daaspro.pyscript.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pyscript")
public class PyscriptEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private Long datasourceId;
    private String content;
    private String paramsJson;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
