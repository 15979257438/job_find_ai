package com.getjobs.profile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("profile_change_log")
public class ProfileChangeLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long profileId;
    private String source;       // RESUME_PARSE / AI_CHAT / MANUAL
    private Long sessionId;
    private Long messageId;
    private String changeJson;
    private LocalDateTime createdAt;
}