package com.getjobs.profile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("profile_extension")
public class ProfileExtensionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long profileId;
    private String competitionsJson;
    private String awardsJson;
    private String openSourceJson;
    private String sideProjectsJson;
    private String papersJson;
    private String publicSpeakingJson;
    private String otherFactsJson;
    private LocalDateTime updatedAt;
}