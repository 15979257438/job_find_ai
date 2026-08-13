package com.getjobs.aichat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_chat_message")
public class AiChatMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;          // user / assistant / tool / system_card
    private String content;
    private String toolCallId;
    private String toolCallsJson;
    private Long profileChangeLogId;
    private LocalDateTime createdAt;
}