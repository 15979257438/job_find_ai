package com.getjobs.profile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_profile")
public class UserProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String city;
    private String expectedCityJson;
    private Integer expectedSalaryMinK;
    private Integer expectedSalaryMaxK;
    private String summary;
    private String educationJson;
    private String experiencesJson;
    private String projectsJson;
    private String skillsJson;
    private String certificatesJson;
    private String languagesJson;
    private Integer isDefault;
    private String conflictLog;
    private Long lastMergedResumeId;
    private LocalDateTime lastMergedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}