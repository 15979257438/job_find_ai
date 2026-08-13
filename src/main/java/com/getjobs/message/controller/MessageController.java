package com.getjobs.message.controller;

import com.getjobs.message.entity.PlatformMessageEntity;
import com.getjobs.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/messages")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;

    /** 列表（按平台/状态/排序） */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy
    ) {
        return Map.of("success", true, "data", messageService.listMessages(platform, status, sortBy));
    }

    /** 标记已读 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        messageService.markRead(id);
        return Map.of("success", true);
    }

    /** 会话详情 */
    @GetMapping("/conversation/{targetId}")
    public Map<String, Object> conversation(@PathVariable Long targetId) {
        return Map.of("success", true, "data", messageService.conversation(targetId));
    }

    /** 触发抓取（FR-MSG-001） */
    @PostMapping("/crawl/{platform}")
    public ResponseEntity<Map<String, Object>> crawl(@PathVariable String platform, @RequestBody(required = false) Map<String, Object> body) {
        try {
            List<Map<String, Object>> raw = body == null ? List.of() : (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
            int n = messageService.crawlPlatform(platform, raw);
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of("inserted", n)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}