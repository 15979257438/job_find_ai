package com.getjobs.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_message")
public class PlatformMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private String platformMessageId;
    private String conversationId;
    private String direction;
    private String senderRole;
    private String senderName;
    private String contentText;
    private String jobId;
    private Long deliveryTargetId;
    private Integer isRead;
    private LocalDateTime sentAt;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;
}