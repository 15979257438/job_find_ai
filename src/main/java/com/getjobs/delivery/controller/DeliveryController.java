package com.getjobs.delivery.controller;

import com.getjobs.delivery.entity.DeliveryRequestEntity;
import com.getjobs.delivery.entity.DeliveryTargetEntity;
import com.getjobs.delivery.service.DeliveryService;
import com.getjobs.worker.dto.JobProgressMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/delivery")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class DeliveryController {
    private final DeliveryService deliveryService;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 提交投递需求（含 4 平台独立配置） */
    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        try {
            DeliveryRequestEntity r = new DeliveryRequestEntity();
            r.setResumeId(Long.valueOf(body.getOrDefault("resume_id", "0").toString()));
            r.setTemplateId((String) body.getOrDefault("template_id", "editorial-dark-v1"));
            r.setKeywordsJson(toJson(body.get("keywords")));
            r.setCityCodesJson(toJson(body.get("city_codes")));
            r.setSalaryMinK(toInt(body.get("salary_min_k")));
            r.setSalaryMaxK(toInt(body.get("salary_max_k")));
            r.setPlatformsJson(toJson(body.get("platforms")));
            r.setExperience((String) body.getOrDefault("experience", null));
            r.setDegree((String) body.getOrDefault("degree", null));
            r.setIndustriesJson(toJson(body.get("industries")));
            r.setScalesJson(toJson(body.get("scales")));
            r.setBlacklistKeywordsJson(toJson(body.get("blacklist_keywords")));
            r.setCustomGreeting((String) body.getOrDefault("custom_greeting", null));
            r.setMaxCount(toInt(body.getOrDefault("max_count", 20)));
            r.setMode((String) body.getOrDefault("mode", "STANDARD"));
            r.setMatchThreshold(toInt(body.getOrDefault("match_threshold", 60)));

            // 平台独立配置：归一化 + 必填校验
            Map<String, Object> pcRaw = castPlatformConfigs(body.get("platform_configs"));
            Map<String, Object> pcNorm = deliveryService.normalizePlatformConfigs(pcRaw);
            r.setPlatformConfigsJson(toJson(pcNorm));

            DeliveryRequestEntity saved = deliveryService.submit(r);
            // 校验每个被勾选平台的必填项
            deliveryService.validateRequiredPlatformConfigs(saved);
            saved.setConfigValidated(1);
            saved.setUpdatedAt(java.time.LocalDateTime.now());
            // 把校验结果回写（不阻塞主流程）
            deliveryService.updatePlatformConfigValidated(saved);
            return ResponseEntity.ok(Map.of("success", true, "data", saved));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 启动爬虫投递（按 platforms 派发到对应 worker，传入 platform_configs 覆盖） */
    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> runDelivery(@PathVariable Long id) {
        try {
            DeliveryRequestEntity req = deliveryService.getRequest(id);
            if (req == null) return ResponseEntity.status(404).body(Map.of("success", false, "message", "投递需求不存在"));
            String dispatched = deliveryService.executeByPlatforms(req);
            return ResponseEntity.ok(Map.of("success", true, "message", "已派发 " + dispatched));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPlatformConfigs(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return Map.of();
    }

    /** 投递需求详情 */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        DeliveryRequestEntity r = deliveryService.getRequest(id);
        if (r == null) return ResponseEntity.status(404).body(Map.of("success", false, "message", "not found"));
        List<DeliveryTargetEntity> targets = deliveryService.listTargets(id);
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("request", r, "targets", targets)));
    }

    /** 拉取并匹配岗位（注入岗位种子） */
    @PostMapping("/{id}/pull-and-match")
    public ResponseEntity<Map<String, Object>> pullAndMatch(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            List<Map<String, Object>> seed = body == null ? List.of() : (List<Map<String, Object>>) body.getOrDefault("jobs", List.of());
            List<DeliveryTargetEntity> targets = deliveryService.pullJobsAndMatch(id, seed);
            return ResponseEntity.ok(Map.of("success", true, "data", targets));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 选择前 N 个目标并生成岗位定制版本 */
    @PostMapping("/{id}/generate-job-specific")
    public ResponseEntity<Map<String, Object>> generateJobSpecific(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            int n = body == null || body.get("limit") == null ? 3 : Integer.parseInt(body.get("limit").toString());
            List<DeliveryTargetEntity> top = deliveryService.pickTopN(id, n);
            List<Map<String, Object>> versions = new ArrayList<>();
            for (DeliveryTargetEntity t : top) {
                try {
                    var v = deliveryService.generateJobSpecific(id, t.getId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("target_id", t.getId());
                    item.put("resume_version_id", v.getId());
                    item.put("html_path", v.getRenderedHtmlPath());
                    versions.add(item);
                } catch (Exception e) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("target_id", t.getId());
                    item.put("error", e.getMessage());
                    versions.add(item);
                }
            }
            return ResponseEntity.ok(Map.of("success", true, "data", versions));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 重试 */
    @PostMapping("/targets/{targetId}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable Long targetId) {
        try {
            deliveryService.retryFailed(targetId);
            return ResponseEntity.ok(Map.of("success", true, "message", "已重置为 PENDING"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 手动标记结果（用于 E2E 测试） */
    @PostMapping("/targets/{targetId}/mark")
    public ResponseEntity<Map<String, Object>> markResult(@PathVariable Long targetId, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "SUCCESS"));
        String error = String.valueOf(body.getOrDefault("error_message", ""));
        deliveryService.markResult(targetId, status, error);
        return ResponseEntity.ok(Map.of("success", true, "message", "ok"));
    }

    /** SSE 进度（沿用既有通道） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter e = new SseEmitter(0L);
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        try { e.send(SseEmitter.event().name("connected").data("ok")); } catch (IOException ignore) {}
        return e;
    }

    /** 内部：投递进度回调 */
    public void push(JobProgressMessage msg) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("progress").data(msg));
            } catch (Exception ex) {
                emitters.remove(e);
                try { e.complete(); } catch (Exception ignore) {}
            }
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }
}