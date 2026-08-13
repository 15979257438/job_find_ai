package com.getjobs.message.service;

import com.getjobs.delivery.entity.DeliveryTargetEntity;
import com.getjobs.delivery.mapper.DeliveryTargetMapper;
import com.getjobs.message.entity.PlatformMessageCrawlLogEntity;
import com.getjobs.message.entity.PlatformMessageEntity;
import com.getjobs.message.mapper.PlatformMessageCrawlLogMapper;
import com.getjobs.message.mapper.PlatformMessageMapper;
import com.getjobs.worker.utils.Bot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台消息聚合服务：抓取、入库去重、日志记录、按需触发。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {
    private final PlatformMessageMapper messageMapper;
    private final PlatformMessageCrawlLogMapper crawlLogMapper;
    private final DeliveryTargetMapper deliveryTargetMapper;
    private final Bot bot;

    private static final Set<String> PLATFORMS = Set.of("boss", "liepin", "51job", "zhilian");

    /** 列出所有消息（按接收时间倒序，可按平台/状态过滤） */
    public List<PlatformMessageEntity> listMessages(String platform, String status, String sortBy) {
        List<PlatformMessageEntity> all = messageMapper.findRecent(500);
        if (platform != null && !platform.isBlank()) all = all.stream().filter(m -> platform.equals(m.getPlatform())).collect(Collectors.toList());
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "UNREAD": all = all.stream().filter(m -> m.getIsRead() == null || m.getIsRead() == 0).collect(Collectors.toList()); break;
                case "READ": all = all.stream().filter(m -> m.getIsRead() != null && m.getIsRead() == 1).collect(Collectors.toList()); break;
                case "SYSTEM": all = all.stream().filter(m -> "SYSTEM".equals(m.getSenderRole())).collect(Collectors.toList()); break;
                default: break;
            }
        }
        if ("score".equalsIgnoreCase(sortBy)) {
            // 用关联 delivery_target 的 match_score 排序（简化）
            all.sort((a, b) -> {
                Integer sa = a.getDeliveryTargetId() == null ? 0 : lookupScore(a.getDeliveryTargetId());
                Integer sb = b.getDeliveryTargetId() == null ? 0 : lookupScore(b.getDeliveryTargetId());
                return Integer.compare(sb, sa);
            });
        } else {
            all.sort((a, b) -> {
                LocalDateTime ta = a.getReceivedAt() == null ? a.getCreatedAt() : a.getReceivedAt();
                LocalDateTime tb = b.getReceivedAt() == null ? b.getCreatedAt() : b.getReceivedAt();
                if (ta == null) return 1;
                if (tb == null) return -1;
                return tb.compareTo(ta);
            });
        }
        return all;
    }

    public List<PlatformMessageEntity> conversation(Long targetId) {
        return messageMapper.findByDeliveryTargetId(targetId);
    }

    /** 标记已读 */
    @Transactional
    public void markRead(Long messageId) {
        PlatformMessageEntity m = messageMapper.selectById(messageId);
        if (m == null) return;
        m.setIsRead(1);
        messageMapper.updateById(m);
    }

    /** 按平台抓取（FR-MSG-001），简化实现：若已存在该消息则跳过；否则从外部数据源注入 */
    @Transactional
    public int crawlPlatform(String platform, List<Map<String, Object>> rawMessages) {
        if (!PLATFORMS.contains(platform)) return 0;
        PlatformMessageCrawlLogEntity crawlLog = new PlatformMessageCrawlLogEntity();
        crawlLog.setPlatform(platform);
        crawlLog.setStartedAt(LocalDateTime.now());
        crawlLog.setStatus("OK");
        crawlLog.setNewMessageCount(0);
        crawlLogMapper.insert(crawlLog);

        int inserted = 0;
        try {
            if (rawMessages == null) rawMessages = List.of();
            for (Map<String, Object> raw : rawMessages) {
                String mid = String.valueOf(raw.getOrDefault("platform_message_id", ""));
                if (mid.isBlank()) continue;
                PlatformMessageEntity existing = messageMapper.findByPlatformAndMessageId(platform, mid);
                if (existing != null) continue;
                PlatformMessageEntity m = new PlatformMessageEntity();
                m.setPlatform(platform);
                m.setPlatformMessageId(mid);
                m.setConversationId(String.valueOf(raw.getOrDefault("conversation_id", "")));
                m.setDirection(String.valueOf(raw.getOrDefault("direction", "IN")));
                m.setSenderRole(String.valueOf(raw.getOrDefault("sender_role", "HR")));
                m.setSenderName(String.valueOf(raw.getOrDefault("sender_name", "")));
                m.setContentText(String.valueOf(raw.getOrDefault("content_text", "")));
                Object jobId = raw.get("job_id");
                m.setJobId(jobId == null ? null : jobId.toString());
                // 关联 delivery_target
                if (m.getJobId() != null) {
                    DeliveryTargetEntity dt = findDeliveryTargetByJobId(platform, m.getJobId());
                    if (dt != null) m.setDeliveryTargetId(dt.getId());
                }
                m.setIsRead(0);
                m.setSentAt(parseTime(raw.get("sent_at")));
                m.setReceivedAt(LocalDateTime.now());
                m.setCreatedAt(LocalDateTime.now());
                messageMapper.insert(m);
                inserted++;
                // FR-MSG-005：HR 入站消息触发 Bot 推送
                if ("IN".equals(m.getDirection()) && "HR".equals(m.getSenderRole())) {
                    pushBotNotification(platform, m);
                }
            }
            crawlLog.setNewMessageCount(inserted);
            crawlLog.setFinishedAt(LocalDateTime.now());
            crawlLogMapper.updateById(crawlLog);
        } catch (Exception e) {
            crawlLog.setStatus("FAILED");
            crawlLog.setErrorMessage(e.getMessage());
            crawlLog.setFinishedAt(LocalDateTime.now());
            crawlLogMapper.updateById(crawlLog);
            log.error("抓取平台 {} 消息失败", platform, e);
        }
        return inserted;
    }

    /** 定时轮询（FR-MSG-001） */
    @Scheduled(fixedDelay = 15L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    public void scheduledCrawl() {
        log.info("平台消息定时抓取触发");
        for (String p : PLATFORMS) {
            try {
                crawlPlatform(p, List.of());
            } catch (Exception e) {
                log.warn("定时抓取 {} 失败: {}", p, e.getMessage());
            }
        }
    }

    private DeliveryTargetEntity findDeliveryTargetByJobId(String platform, String jobId) {
        for (DeliveryTargetEntity t : deliveryTargetMapper.selectList(null)) {
            if (platform.equals(t.getPlatform()) && jobId.equals(t.getJobId())) return t;
        }
        return null;
    }

    private Integer lookupScore(Long targetId) {
        DeliveryTargetEntity t = deliveryTargetMapper.selectById(targetId);
        return t == null ? 0 : (t.getMatchScore() == null ? 0 : t.getMatchScore());
    }

    private LocalDateTime parseTime(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Number) return LocalDateTime.ofEpochSecond(((Number) o).longValue(), 0, java.time.ZoneOffset.UTC);
            return LocalDateTime.parse(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** FR-MSG-005：HR 入站消息触发 Bot（企业微信）推送摘要 + 跳转链接 */
    private void pushBotNotification(String platform, PlatformMessageEntity m) {
        try {
            String preview = m.getContentText() == null ? "" : m.getContentText();
            if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
            String text = String.format(
                    "📩 新消息 [%s] %s\n岗位: %s\n内容: %s\n打开: /messages?platform=%s&target=%d",
                    platform,
                    m.getSenderName() == null ? "" : m.getSenderName(),
                    m.getJobId() == null ? "-" : m.getJobId(),
                    preview,
                    platform,
                    m.getDeliveryTargetId() == null ? 0L : m.getDeliveryTargetId()
            );
            bot.sendMessageInstance(text);
        } catch (Throwable e) {
            log.warn("Bot 推送失败：{}", e.getMessage());
        }
    }
}