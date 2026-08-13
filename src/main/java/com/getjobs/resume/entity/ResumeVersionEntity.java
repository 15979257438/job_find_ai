package com.getjobs.resume.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume_version")
public class ResumeVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long resumeId;
    private String type;          // MASTER / TEMPLATE / JOB_SPECIFIC
    private String templateId;
    private String targetJobId;
    private String renderedHtmlPath;
    private String renderedPdfPath;
    private String adjustmentsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}