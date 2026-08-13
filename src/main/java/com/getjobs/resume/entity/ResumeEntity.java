package com.getjobs.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume")
public class ResumeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String originalFilename;
    private String originalPath;
    private String fileSha256;
    private Long fileSizeBytes;
    private String parseStatus;
    private String parsedJson;
    private Integer isManuallyEdited;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}