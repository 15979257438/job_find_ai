package com.getjobs.aichat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.aichat.entity.AiChatMessageEntity;
import com.getjobs.aichat.entity.AiChatSessionEntity;
import com.getjobs.aichat.entity.AiToolCallEntity;
import com.getjobs.aichat.mapper.AiChatMessageMapper;
import com.getjobs.aichat.mapper.AiChatSessionMapper;
import com.getjobs.aichat.mapper.AiToolCallMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.delivery.entity.DeliveryTargetEntity;
import com.getjobs.delivery.mapper.DeliveryTargetMapper;
import com.getjobs.message.entity.PlatformMessageEntity;
import com.getjobs.message.mapper.PlatformMessageMapper;
import com.getjobs.profile.entity.ProfileExtensionEntity;
import com.getjobs.profile.entity.UserProfileEntity;
import com.getjobs.profile.mapper.ProfileExtensionMapper;
import com.getjobs.profile.mapper.UserProfileMapper;
import com.getjobs.profile.service.MergedViewService;
import com.getjobs.profile.service.ProfileService;
import com.getjobs.resume.entity.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 对话中心服务：会话 / 消息 / 工具调用 / Profile 写回确认。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {
    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiToolCallMapper toolCallMapper;
    private final UserProfileMapper profileMapper;
    private final ProfileExtensionMapper extensionMapper;
    private final ProfileService profileService;
    private final MergedViewService mergedViewService;
    private final DeliveryTargetMapper deliveryTargetMapper;
    private final PlatformMessageMapper platformMessageMapper;
    private final AiService aiService;

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Pattern WRITE_TOOL = Pattern.compile("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern CONFIRM_KW = Pattern.compile("(?i)(确认|是|好|改|可以|同意|agree|yes|ok|sure)");
    private static final Pattern CANCEL_KW = Pattern.compile("(?i)(取消|不要|不行|算了|拒绝|cancel|no|reject)");

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 内置 8 个问题模板 */
    public static final List<String> TEMPLATES = List.of(
            "我今天一共投递了多少岗位？哪些平台？",
            "最近一次投递失败的原因是什么？",
            "哪几个 HR 已经在平台上回复过我？",
            "给我列出匹配度最高的 5 个岗位。",
            "哪个平台的成功率最高？",
            "帮我把\"Java\"关键词扩展为 3 个近义词组。",
            "我的个人数据库现在还缺什么？帮我列出来。",
            "把这次 ACM 区域赛银奖的经历加到我的个人数据库里，2022 年，队员。"
    );

    /** SSE 流（前端订阅） */
    public SseEmitter subscribe() {
        SseEmitter e = new SseEmitter(0L);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        try { e.send(SseEmitter.event().name("connected").data("ok")); } catch (IOException ignore) {}
        return e;
    }

    /** 新建会话 */
    @Transactional
    public AiChatSessionEntity newSession() {
        AiChatSessionEntity s = new AiChatSessionEntity();
        s.setTitle("新会话");
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    /** 列出所有会话 */
    public List<AiChatSessionEntity> listSessions() {
        return sessionMapper.selectList(null);
    }

    public List<AiChatMessageEntity> listMessages(Long sessionId) {
        return messageMapper.findBySessionId(sessionId);
    }

    /** 删除消息 */
    @Transactional
    public void deleteMessage(Long msgId) {
        messageMapper.deleteById(msgId);
    }

    /** 删除会话（含消息/工具调用） */
    @Transactional
    public void deleteSession(Long sessionId) {
        messageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiChatMessageEntity>().eq("session_id", sessionId));
        toolCallMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiToolCallEntity>().eq("session_id", sessionId));
        sessionMapper.deleteById(sessionId);
    }

    /** 发送用户消息：流式返回（同步处理，无外部流式 AI 时退化为一次性输出） */
    public void send(Long sessionId, String userText, Runnable streamDone) {
        AiChatSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在");

        // 写 user 消息
        AiChatMessageEntity user = appendMessage(sessionId, "user", userText, null, null);
        // 自动设置标题
        if ("新会话".equals(session.getTitle()) || session.getTitle() == null) {
            String title = userText.length() > 20 ? userText.substring(0, 20) : userText;
            session.setTitle(title);
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        // FR-AI-006：检测"确认 / 是 / 改 / 好"等肯定词，若最近一条 assistant 提议过 system_card 则自动执行写回
        AiChatMessageEntity lastPendingCard = messageMapper.findLastSystemCard(sessionId);
        if (lastPendingCard != null && isConfirmKeyword(userText)) {
            AiChatMessageEntity reply = confirmWriteBack(sessionId, lastPendingCard.getId(), true);
            push("message", reply);
            push("done", Map.of("messageId", reply.getId(), "autoConfirmed", true));
            if (streamDone != null) streamDone.run();
            return;
        }
        if (lastPendingCard != null && isCancelKeyword(userText)) {
            AiChatMessageEntity reply = confirmWriteBack(sessionId, lastPendingCard.getId(), false);
            push("message", reply);
            push("done", Map.of("messageId", reply.getId(), "autoCancelled", true));
            if (streamDone != null) streamDone.run();
            return;
        }

        // 上下文：最近 10 条 + Profile 摘要 + 当日投递汇总
        List<AiChatMessageEntity> history = messageMapper.findRecentBySessionId(sessionId, 10);
        Map<String, Object> profileSummary = mergedViewService.ensureDefaultProfileId() > 0 ? mergedViewService.merge(mergedViewService.ensureDefaultProfileId(), null) : Map.of();
        String todaySummary = todayDeliverySummary();

        String prompt = buildPrompt(history, profileSummary, todaySummary, userText);

        // 调用 AI
        String aiResp;
        try {
            aiResp = aiService.sendRequest(prompt);
        } catch (Exception e) {
            aiResp = "AI 调用失败：" + e.getMessage();
        }

        // 检查 AI 是否提议 Profile 写回
        Map<String, Object> proposed = extractWriteTool(aiResp);
        if (proposed != null) {
            // 写入 system_card 提议（不算执行）
            AiChatMessageEntity proposeCard = appendMessage(sessionId, "system_card", toJson(proposed), null, null);
            // 写 assistant 文本（去除 <tool_call> 块）
            String cleaned = aiResp.replaceAll("<tool_call>.*?</tool_call>", "").trim();
            appendMessage(sessionId, "assistant", cleaned, null, null);
            push("system_card", proposeCard);
            push("done", Map.of("messageId", proposeCard.getId()));
            if (streamDone != null) streamDone.run();
            return;
        }

        AiChatMessageEntity assistant = appendMessage(sessionId, "assistant", aiResp, null, null);
        push("message", assistant);
        push("done", Map.of("messageId", assistant.getId()));
        if (streamDone != null) streamDone.run();
    }

    /** 用户确认 / 取消写回 */
    @Transactional
    public AiChatMessageEntity confirmWriteBack(Long sessionId, Long systemCardMessageId, boolean confirm) {
        AiChatMessageEntity card = messageMapper.selectById(systemCardMessageId);
        if (card == null || !"system_card".equals(card.getRole())) throw new IllegalArgumentException("提议不存在");
        Map<String, Object> payload;
        try { payload = OM.readValue(card.getContent(), new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("提议解析失败"); }

        AiChatMessageEntity userReply = appendMessage(sessionId, "user", confirm ? "确认" : "取消", null, null);

        if (!confirm) {
            return appendMessage(sessionId, "assistant", "已取消。", null, null);
        }
        String field = String.valueOf(payload.getOrDefault("field", ""));
        Object value = payload.get("value");
        Long logId = profileService.applyAiWriteBack(sessionId, userReply.getId(), field, value);
        if (logId == null) {
            return appendMessage(sessionId, "assistant", "写回失败：" + field, null, null);
        }
        AiChatMessageEntity sysCard = appendMessage(sessionId, "system_card", "已加入个人数据库", null, logId);
        sysCard.setProfileChangeLogId(logId);
        messageMapper.updateById(sysCard);
        return appendMessage(sessionId, "assistant", "已更新 " + field + "。", null, null);
    }

    /** 记录工具调用日志 */
    @Transactional
    public AiToolCallEntity recordToolCall(Long sessionId, Long messageId, String toolName, String argsJson, String resultJson, long latencyMs, boolean ok) {
        AiToolCallEntity e = new AiToolCallEntity();
        e.setSessionId(sessionId);
        e.setMessageId(messageId);
        e.setToolName(toolName);
        e.setArgumentsJson(argsJson);
        e.setResultJson(resultJson);
        e.setStatus(ok ? "OK" : "FAILED");
        e.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
        e.setCreatedAt(LocalDateTime.now());
        toolCallMapper.insert(e);
        return e;
    }

    // ---------- 工具函数：前端通过工具层触发 ----------

    public Map<String, Object> toolListRecentDeliveries(int limit, String platform, String status) {
        List<DeliveryTargetEntity> all = deliveryTargetMapper.selectList(null);
        if (platform != null && !platform.isBlank()) all.removeIf(t -> !platform.equals(t.getPlatform()));
        if (status != null && !status.isBlank()) all.removeIf(t -> !status.equals(t.getStatus()));
        all.sort((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()));
        if (all.size() > limit) all = all.subList(0, limit);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", all.size());
        r.put("items", all);
        return r;
    }

    public Map<String, Object> toolGetDeliveryFailures(int sinceHours) {
        List<DeliveryTargetEntity> list = deliveryTargetMapper.findSinceHours(sinceHours);
        List<DeliveryTargetEntity> fails = new ArrayList<>();
        for (DeliveryTargetEntity t : list) if ("FAILED".equals(t.getStatus())) fails.add(t);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", fails.size());
        r.put("items", fails);
        return r;
    }

    public Map<String, Object> toolGetHrReplies(int sinceHours) {
        List<PlatformMessageEntity> all = platformMessageMapper.findRecent(500);
        long threshold = System.currentTimeMillis() - sinceHours * 3600L * 1000L;
        List<PlatformMessageEntity> ins = new ArrayList<>();
        for (PlatformMessageEntity m : all) {
            if ("IN".equals(m.getDirection()) && m.getReceivedAt() != null && m.getReceivedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() >= threshold) {
                ins.add(m);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", ins.size());
        r.put("items", ins);
        return r;
    }

    public Map<String, Object> toolGetTopMatches(int limit) {
        List<DeliveryTargetEntity> all = deliveryTargetMapper.selectList(null);
        all.sort((a, b) -> Integer.compare(b.getMatchScore() == null ? 0 : b.getMatchScore(), a.getMatchScore() == null ? 0 : a.getMatchScore()));
        if (all.size() > limit) all = all.subList(0, limit);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", all);
        return r;
    }

    public Map<String, Object> toolGetPlatformSuccessRate(int sinceDays) {
        List<DeliveryTargetEntity> all = deliveryTargetMapper.selectList(null);
        long threshold = System.currentTimeMillis() - sinceDays * 24L * 3600L * 1000L;
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (DeliveryTargetEntity t : all) {
            if (t.getDeliveredAt() == null || t.getDeliveredAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() < threshold) continue;
            stats.computeIfAbsent(t.getPlatform(), k -> new int[2])["SUCCESS".equals(t.getStatus()) ? 1 : 0]++;
            stats.computeIfAbsent(t.getPlatform(), k -> new int[2])[0]++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Map.Entry<String, int[]> en : stats.entrySet()) {
            int total = en.getValue()[0];
            int ok = en.getValue()[1];
            arr.add(Map.of("platform", en.getKey(), "total", total, "success", ok,
                    "rate", total == 0 ? 0 : Math.round(ok * 10000.0 / total) / 100.0));
        }
        r.put("items", arr);
        return r;
    }

    public Map<String, Object> toolExpandKeywords(String keyword, int count) {
        try {
            String resp = aiService.sendRequest("请把\"" + keyword + "\"扩展为 " + count + " 个近义词/相关技能组，每行一个，不要解释：");
            List<String> list = new ArrayList<>();
            for (String s : resp.split("\n")) {
                String t = s.trim(); if (!t.isEmpty()) list.add(t);
            }
            return Map.of("keyword", keyword, "items", list);
        } catch (Exception e) {
            return Map.of("keyword", keyword, "items", List.of(), "error", e.getMessage());
        }
    }

    /** 关键：必须返回 merged_view（FR-AI-004） */
    public Map<String, Object> toolGetResumeSummary() {
        Long pid = mergedViewService.ensureDefaultProfileId();
        ResumeEntity lastResume = resumeService().lastResume();
        return mergedViewService.merge(pid, lastResume == null ? null : lastResume.getId());
    }

    public Map<String, Object> toolGetProfile() {
        UserProfileEntity p = profileMapper.findDefault();
        if (p == null) return Map.of();
        ProfileExtensionEntity ext = extensionMapper.findByProfileId(p.getId());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("profile", p);
        r.put("extension", ext);
        return r;
    }

    public Map<String, Object> toolGetProfileDiff(Long resumeId) {
        Long pid = mergedViewService.ensureDefaultProfileId();
        Map<String, Object> merged = mergedViewService.merge(pid, resumeId);
        return Map.of("merged", merged);
    }

    // ---------- 私有辅助 ----------

    /** 为避免循环依赖，通过 lazy finder 拿 resume service */
    private ResumeServiceAccessor resumeService() {
        return new ResumeServiceAccessor(deliveryTargetMapper);
    }

    private static class ResumeServiceAccessor {
        private final DeliveryTargetMapper mapper;
        ResumeServiceAccessor(DeliveryTargetMapper m) { this.mapper = m; }
        ResumeEntity lastResume() {
            // 简化：取最新一条 delivery_target.job_id 不直接给 resume；这里复用 DeliveryTargetEntity 不带 resumeId，
            // 实际场景应通过 delivery_request 查 resumeId。为安全，扫描 DeliveryTargetEntity 无 resume 信息则返回 null。
            return null;
        }
    }

    private AiChatMessageEntity appendMessage(Long sessionId, String role, String content, String toolCallsJson, Long profileChangeLogId) {
        AiChatMessageEntity m = new AiChatMessageEntity();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setToolCallsJson(toolCallsJson);
        m.setProfileChangeLogId(profileChangeLogId);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
        return m;
    }

    private String buildPrompt(List<AiChatMessageEntity> history, Map<String, Object> profileSummary, String todaySummary, String userText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 get_jobs 求职助手。基于以下上下文回答用户问题，必要时调用工具：\n");
        sb.append("上下文 - 用户 Profile 摘要：").append(toJson(profileSummary)).append("\n");
        sb.append("上下文 - 当日投递汇总：").append(todaySummary).append("\n\n");
        for (AiChatMessageEntity h : history) {
            if (h.getContent() == null) continue;
            sb.append("[").append(h.getRole()).append("] ").append(h.getContent()).append("\n");
        }
        sb.append("\n[user] ").append(userText).append("\n[assistant]\n");
        sb.append("如要写回个人数据库，必须使用 <tool_call>{\"tool\":\"<name>\",\"field\":\"<path>\",\"value\":...}</tool_call> 形式提出，等待用户确认。");
        return sb.toString();
    }

    private String todayDeliverySummary() {
        List<DeliveryTargetEntity> all = deliveryTargetMapper.selectList(null);
        int success = 0, failed = 0, filtered = 0;
        Map<String, Integer> byPlatform = new LinkedHashMap<>();
        for (DeliveryTargetEntity t : all) {
            if (t.getDeliveredAt() == null || t.getDeliveredAt().toLocalDate().equals(LocalDateTime.now().toLocalDate())) {
                // 包含今天之前所有可见（简化为全部）
            }
            if ("SUCCESS".equals(t.getStatus())) success++;
            else if ("FAILED".equals(t.getStatus())) failed++;
            else if ("FILTERED".equals(t.getStatus())) filtered++;
            byPlatform.merge(t.getPlatform(), 1, Integer::sum);
        }
        return String.format("今日/累计: success=%d failed=%d filtered=%d byPlatform=%s", success, failed, filtered, toJson(byPlatform));
    }

    private Map<String, Object> extractWriteTool(String aiResp) {
        if (aiResp == null) return null;
        Matcher m = WRITE_TOOL.matcher(aiResp);
        if (!m.find()) return null;
        String json = m.group(1);
        try {
            Map<String, Object> map = OM.readValue(json, new TypeReference<Map<String, Object>>() {});
            return map;
        } catch (Exception e) {
            log.warn("写回工具解析失败: {}", e.getMessage());
            return null;
        }
    }

    private void push(String event, Object data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(event).data(toJson(data)));
            } catch (Exception ex) {
                emitters.remove(e);
                try { e.complete(); } catch (Exception ignore) {}
            }
        }
    }

    private String toJson(Object o) {
        try { return OM.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    /** FR-AI-006 关键词识别 */
    private boolean isConfirmKeyword(String text) {
        if (text == null) return false;
        return CONFIRM_KW.matcher(text.trim()).find();
    }
    private boolean isCancelKeyword(String text) {
        if (text == null) return false;
        return CANCEL_KW.matcher(text.trim()).find();
    }
}