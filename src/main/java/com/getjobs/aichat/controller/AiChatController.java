package com.getjobs.aichat.controller;

import com.getjobs.aichat.entity.AiChatMessageEntity;
import com.getjobs.aichat.entity.AiChatSessionEntity;
import com.getjobs.aichat.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequestMapping("/api/ai-chat")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AiChatController {
    private final AiChatService aiChatService;

    /** 内置模板 */
    @GetMapping("/templates")
    public Map<String, Object> templates() {
        return Map.of("success", true, "data", AiChatService.TEMPLATES);
    }

    /** 会话列表 */
    @GetMapping("/sessions")
    public Map<String, Object> sessions() {
        return Map.of("success", true, "data", aiChatService.listSessions());
    }

    /** 新建会话 */
    @PostMapping("/sessions")
    public Map<String, Object> create() {
        return Map.of("success", true, "data", aiChatService.newSession());
    }

    /** 消息列表 */
    @GetMapping("/sessions/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable Long sessionId) {
        return Map.of("success", true, "data", aiChatService.listMessages(sessionId));
    }

    /** 删除消息 */
    @DeleteMapping("/messages/{id}")
    public Map<String, Object> deleteMessage(@PathVariable Long id) {
        aiChatService.deleteMessage(id);
        return Map.of("success", true);
    }

    /** 删除会话 */
    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable Long id) {
        aiChatService.deleteSession(id);
        return Map.of("success", true);
    }

    /** 发送消息（同步执行） */
    @PostMapping("/sessions/{sessionId}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        String text = String.valueOf(body.getOrDefault("text", ""));
        if (text.isBlank()) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "消息不能为空"));
        try {
            aiChatService.send(sessionId, text, null);
            return ResponseEntity.ok(Map.of("success", true, "data", aiChatService.listMessages(sessionId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 写回确认 */
    @PostMapping("/sessions/{sessionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long sessionId, @RequestBody Map<String, Object> body) {
        Long cardId = Long.valueOf(body.getOrDefault("system_card_message_id", "0").toString());
        boolean confirm = Boolean.TRUE.equals(body.get("confirm"));
        try {
            AiChatMessageEntity result = aiChatService.confirmWriteBack(sessionId, cardId, confirm);
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 工具调用入口（直接调用读取类工具） */
    @PostMapping("/tools/invoke")
    public ResponseEntity<Map<String, Object>> invoke(@RequestBody Map<String, Object> body) {
        String tool = String.valueOf(body.getOrDefault("tool", ""));
        Long sessionId = body.get("session_id") == null ? null : Long.valueOf(body.get("session_id").toString());
        try {
            Map<String, Object> result = switch (tool) {
                case "list_recent_deliveries" -> aiChatService.toolListRecentDeliveries(
                        intVal(body.get("limit"), 20),
                        strVal(body.get("platform")),
                        strVal(body.get("status")));
                case "get_delivery_failures" -> aiChatService.toolGetDeliveryFailures(intVal(body.get("since_hours"), 24));
                case "get_hr_replies" -> aiChatService.toolGetHrReplies(intVal(body.get("since_hours"), 24));
                case "get_top_matches" -> aiChatService.toolGetTopMatches(intVal(body.get("limit"), 5));
                case "get_platform_success_rate" -> aiChatService.toolGetPlatformSuccessRate(intVal(body.get("since_days"), 7));
                case "expand_keywords" -> aiChatService.toolExpandKeywords(strVal(body.get("keyword")), intVal(body.get("count"), 3));
                case "get_resume_summary" -> aiChatService.toolGetResumeSummary();
                case "get_profile" -> aiChatService.toolGetProfile();
                case "get_profile_diff" -> aiChatService.toolGetProfileDiff(body.get("resume_id") == null ? null : Long.valueOf(body.get("resume_id").toString()));
                default -> Map.of("error", "未知工具: " + tool);
            };
            // 记录
            if (sessionId != null) {
                long t0 = System.currentTimeMillis();
                aiChatService.recordToolCall(sessionId, null, tool, body.toString(), result.toString(), System.currentTimeMillis() - t0, !result.containsKey("error"));
            }
            return ResponseEntity.ok(Map.of("success", true, "data", result));
        } catch (Exception e) {
            if (sessionId != null) {
                aiChatService.recordToolCall(sessionId, null, tool, body.toString(), e.getMessage(), 0, false);
            }
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** SSE 订阅（推送对话流） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return aiChatService.subscribe();
    }

    private int intVal(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }
    private String strVal(Object o) { return o == null ? null : o.toString(); }
}