package com.getjobs.resume.controller;

import com.getjobs.resume.entity.ResumeEntity;
import com.getjobs.resume.entity.ResumeVersionEntity;
import com.getjobs.resume.service.ResumeService;
import com.getjobs.resume.service.TemplateService;
import com.getjobs.profile.service.MergedViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/resume")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ResumeController {
    private final ResumeService resumeService;
    private final TemplateService templateService;
    private final MergedViewService mergedViewService;

    /** 上传简历 */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            ResumeService.UploadResult r = resumeService.upload(file);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", r.resume().getId());
            data.put("name", r.resume().getName());
            data.put("original_filename", r.resume().getOriginalFilename());
            data.put("parse_status", r.resume().getParseStatus());
            data.put("reused", r.reused());
            data.put("message", r.message());
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "上传失败: " + e.getMessage()));
        }
    }

    /** 列表 */
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = resumeService.list();
        return Map.of("success", true, "data", items);
    }

    /** 详情（含版本列表 + parsed_json） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        ResumeEntity r = resumeService.get(id);
        if (r == null) return ResponseEntity.status(404).body(Map.of("success", false, "message", "not found"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", r.getId());
        data.put("name", r.getName());
        data.put("original_filename", r.getOriginalFilename());
        data.put("parse_status", r.getParseStatus());
        data.put("is_manually_edited", r.getIsManuallyEdited());
        data.put("parsed_json", r.getParsedJson());
        data.put("created_at", r.getCreatedAt());
        data.put("updated_at", r.getUpdatedAt());
        data.put("versions", resumeService.listVersions(id));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 重试解析 */
    @PostMapping("/{id}/retry-parse")
    public ResponseEntity<Map<String, Object>> retryParse(@PathVariable Long id) {
        try {
            ResumeEntity r = resumeService.retryParse(id);
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of("id", r.getId(), "parse_status", r.getParseStatus())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 手工修订 */
    @PutMapping("/{id}/parsed-json")
    public ResponseEntity<Map<String, Object>> updateParsedJson(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String json = String.valueOf(body.getOrDefault("parsed_json", ""));
        try {
            ResumeEntity r = resumeService.updateParsedJson(id, json);
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of("id", r.getId(), "is_manually_edited", r.getIsManuallyEdited())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            resumeService.delete(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 模板列表 */
    @GetMapping("/templates")
    public Map<String, Object> templates() {
        return Map.of("success", true, "data", templateService.listTemplates());
    }

    /** 渲染预览：返回 HTML（合并视图 + 模板） */
    @PostMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            String templateId = body == null ? "editorial-dark-v1" : String.valueOf(body.getOrDefault("template_id", "editorial-dark-v1"));
            Long profileId = body == null ? null : body.get("profile_id") == null ? null : Long.valueOf(body.get("profile_id").toString());
            Long pid = profileId != null ? profileId : mergedViewService.ensureDefaultProfileId();
            Map<String, Object> merged = mergedViewService.merge(pid, id);
            String html = templateService.renderHtml(id, templateId, merged, Map.of());
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of("html", html, "template_id", templateId)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 创建 TEMPLATE 版本 */
    @PostMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> createVersion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String templateId = String.valueOf(body.getOrDefault("template_id", "editorial-dark-v1"));
            ResumeVersionEntity v = resumeService.createTemplateVersion(id, templateId);
            return ResponseEntity.ok(Map.of("success", true, "data", v));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 读取已落盘版本 HTML */
    @GetMapping("/versions/{versionId}/html")
    public ResponseEntity<String> readHtml(@PathVariable Long versionId) {
        String html = templateService.readHtml(versionId);
        if (html == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").body(html);
    }
}