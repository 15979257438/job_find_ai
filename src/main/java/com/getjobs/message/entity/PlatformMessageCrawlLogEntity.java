package com.getjobs.message.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("platform_message_crawl_log")
public class PlatformMessageCrawlLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String status;
    private Integer newMessageCount;
    private String errorMessage;
}