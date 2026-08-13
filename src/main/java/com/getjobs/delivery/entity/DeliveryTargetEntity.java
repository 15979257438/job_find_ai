package com.getjobs.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("delivery_target")
public class DeliveryTargetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deliveryRequestId;
    private String platform;
    private String jobId;
    private String companyName;
    private String jobName;
    private String salary;
    private String location;
    private String jdText;
    private Integer matchScore;
    private String scoreBreakdownJson;
    private Long resumeVersionId;
    private String status;            // PENDING/SEARCHING/MATCHING/RENDERING/DELIVERING/SUCCESS/FAILED/FILTERED/SKIPPED
    private String filterReason;
    private LocalDateTime deliveredAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}