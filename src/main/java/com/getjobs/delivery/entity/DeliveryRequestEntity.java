package com.getjobs.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delivery_request")
public class DeliveryRequestEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resumeId;
    private String templateId;
    private String keywordsJson;
    private String cityCodesJson;
    private Integer salaryMinK;
    private Integer salaryMaxK;
    private String platformsJson;
    private String experience;
    private String degree;
    private String industriesJson;
    private String scalesJson;
    private String blacklistKeywordsJson;
    private String customGreeting;
    private Integer maxCount;
    private String mode;          // STANDARD / MANUAL
    private Integer matchThreshold;
    private String status;        // READY / RUNNING / DONE / FAILED
    /** 各平台独立配置 JSON：{"boss":{...},"job51":{...},"liepin":{...},"zhilian":{...}} */
    private String platformConfigsJson;
    /** 1 = 后端 validateRequiredPlatformConfigs 通过；0 = 失败 */
    private Integer configValidated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}