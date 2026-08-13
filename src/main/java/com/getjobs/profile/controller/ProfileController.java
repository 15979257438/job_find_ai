package com.getjobs.profile.controller;

import com.getjobs.profile.entity.ProfileChangeLogEntity;
import com.getjobs.profile.entity.ProfileExtensionEntity;
import com.getjobs.profile.entity.UserProfileEntity;
import com.getjobs.profile.mapper.ProfileChangeLogMapper;
import com.getjobs.profile.mapper.ProfileExtensionMapper;
import com.getjobs.profile.mapper.UserProfileMapper;
import com.getjobs.profile.service.MergedViewService;
import com.getjobs.profile.service.ProfileService;
import com.getjobs.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/profile")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profileService;
    private final MergedViewService mergedViewService;
    private final UserProfileMapper profileMapper;
    private final ProfileExtensionMapper extensionMapper;
    private final ProfileChangeLogMapper changeLogMapper;
    private final ResumeMapper resumeMapper;

    /** 获取 Profile + Extension */
    @GetMapping
    public Map<String, Object> get() {
        UserProfileEntity p = profileService.getDefault();
        ProfileExtensionEntity ext = p == null ? null : extensionMapper.findByProfileId(p.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", p);
        data.put("extension", ext);
        return Map.of("success", true, "data", data);
    }

    /** 合并视图 */
    @GetMapping("/merged")
    public Map<String, Object> merged(@RequestParam(required = false) Long resumeId) {
        Long pid = mergedViewService.ensureDefaultProfileId();
        Map<String, Object> m = mergedViewService.merge(pid, resumeId);
        return Map.of("success", true, "data", m);
    }

    /** 手动更新 */
    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        try {
            UserProfileEntity u = new UserProfileEntity();
            if (body.get("name") != null) u.setName(String.valueOf(body.get("name")));
            if (body.get("phone") != null) u.setPhone(String.valueOf(body.get("phone")));
            if (body.get("email") != null) u.setEmail(String.valueOf(body.get("email")));
            if (body.get("city") != null) u.setCity(String.valueOf(body.get("city")));
            if (body.get("expected_city") != null) u.setExpectedCityJson(toJson(body.get("expected_city")));
            if (body.get("expected_salary_min_k") != null) u.setExpectedSalaryMinK(toInt(body.get("expected_salary_min_k")));
            if (body.get("expected_salary_max_k") != null) u.setExpectedSalaryMaxK(toInt(body.get("expected_salary_max_k")));
            if (body.get("summary") != null) u.setSummary(String.valueOf(body.get("summary")));
            if (body.get("skills") != null) u.setSkillsJson(toJson(body.get("skills")));
            if (body.get("certificates") != null) u.setCertificatesJson(toJson(body.get("certificates")));
            if (body.get("languages") != null) u.setLanguagesJson(toJson(body.get("languages")));
            if (body.get("education") != null) u.setEducationJson(toJson(body.get("education")));
            if (body.get("experiences") != null) u.setExperiencesJson(toJson(body.get("experiences")));
            if (body.get("projects") != null) u.setProjectsJson(toJson(body.get("projects")));
            UserProfileEntity updated = profileService.manualUpdate(u);
            return ResponseEntity.ok(Map.of("success", true, "data", updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 更新 ProfileExtension（7 个扩展段）
     * Body: 任意 _json 字段名（competitions_json / awards_json / ...）→ JSON 字符串
     */
    @PutMapping("/extension")
    public ResponseEntity<Map<String, Object>> updateExtension(@RequestBody Map<String, Object> body) {
        try {
            UserProfileEntity p = profileService.getDefault();
            if (p == null) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Profile 不存在"));
            ProfileExtensionEntity ext = extensionMapper.findByProfileId(p.getId());
            if (ext == null) {
                ext = new ProfileExtensionEntity();
                ext.setProfileId(p.getId());
            }
            boolean changed = false;
            if (body.containsKey("competitions_json")) { ext.setCompetitionsJson(String.valueOf(body.get("competitions_json"))); changed = true; }
            if (body.containsKey("awards_json")) { ext.setAwardsJson(String.valueOf(body.get("awards_json"))); changed = true; }
            if (body.containsKey("open_source_json")) { ext.setOpenSourceJson(String.valueOf(body.get("open_source_json"))); changed = true; }
            if (body.containsKey("side_projects_json")) { ext.setSideProjectsJson(String.valueOf(body.get("side_projects_json"))); changed = true; }
            if (body.containsKey("papers_json")) { ext.setPapersJson(String.valueOf(body.get("papers_json"))); changed = true; }
            if (body.containsKey("public_speaking_json")) { ext.setPublicSpeakingJson(String.valueOf(body.get("public_speaking_json"))); changed = true; }
            if (body.containsKey("other_facts_json")) { ext.setOtherFactsJson(String.valueOf(body.get("other_facts_json"))); changed = true; }
            if (changed) {
                if (ext.getId() == null) extensionMapper.insert(ext);
                else extensionMapper.updateById(ext);
                // 写变更日志
                ProfileChangeLogEntity log = new ProfileChangeLogEntity();
                log.setProfileId(p.getId());
                log.setSource("MANUAL");
                log.setChangeJson(toJson(body));
                changeLogMapper.insert(log);
                // 失效合并视图缓存
                mergedViewService.invalidate();
            }
            return ResponseEntity.ok(Map.of("success", true, "data", ext));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 变更日志 */
    @GetMapping("/change-log")
    public Map<String, Object> changeLog() {
        UserProfileEntity p = profileService.getDefault();
        if (p == null) return Map.of("success", true, "data", List.of());
        List<ProfileChangeLogEntity> log = changeLogMapper.findByProfileId(p.getId());
        return Map.of("success", true, "data", log);
    }

    /** FR-PROF-004：返回 Resume vs Profile 字段差异清单 + 冲突列表 */
    @GetMapping("/diff")
    public Map<String, Object> diff(@RequestParam(required = false) Long resumeId) {
        UserProfileEntity p = profileService.getDefault();
        if (p == null) return Map.of("success", true, "data", Map.of("differences", List.of(), "conflicts", List.of()));

        List<Map<String, Object>> differences = new ArrayList<>();
        // 基础字段对比
        if (resumeId != null) {
            com.getjobs.resume.entity.ResumeEntity r = resumeMapper.selectById(resumeId);
            if (r != null && r.getParsedJson() != null) {
                Map<String, Object> resumeMap = parseMap(r.getParsedJson());
                Map<String, Object> basicsR = section(resumeMap, "basics");
                if (basicsR != null) {
                    diffField(differences, "basics.name", stringVal(basicsR, "name"), p.getName());
                    diffField(differences, "basics.phone", stringVal(basicsR, "phone"), p.getPhone());
                    diffField(differences, "basics.email", stringVal(basicsR, "email"), p.getEmail());
                    diffField(differences, "basics.city", stringVal(basicsR, "city"), p.getCity());
                }
            }
        }
        // Profile 独有扩展段（仅 Profile 包含）
        ProfileExtensionEntity ext = extensionMapper.findByProfileId(p.getId());
        if (ext != null) {
            List<String> extKeys = List.of("competitions", "awards", "open_source", "side_projects", "papers", "public_speaking", "other_facts");
            for (String k : extKeys) {
                String json = switch (k) {
                    case "competitions" -> ext.getCompetitionsJson();
                    case "awards" -> ext.getAwardsJson();
                    case "open_source" -> ext.getOpenSourceJson();
                    case "side_projects" -> ext.getSideProjectsJson();
                    case "papers" -> ext.getPapersJson();
                    case "public_speaking" -> ext.getPublicSpeakingJson();
                    default -> ext.getOtherFactsJson();
                };
                if (json != null && !"[]".equals(json) && !json.isBlank()) {
                    differences.add(Map.of("field", "extension." + k, "status", "profile_only", "label", "仅 Profile 包含"));
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("differences", differences);
        data.put("conflicts", profileService.getConflicts());
        data.put("profile_id", p.getId());
        data.put("last_merged_resume_id", p.getLastMergedResumeId());
        data.put("last_merged_at", p.getLastMergedAt() == null ? null : p.getLastMergedAt().toString());
        data.put("conflict_count", profileService.getConflicts().size());
        return Map.of("success", true, "data", data);
    }

    /** FR-PROF-004 + E2E-07：用户选择"以 Profile 为准"，清冲突日志 */
    @PostMapping("/resolve-conflict")
    public ResponseEntity<Map<String, Object>> resolveConflict(@RequestBody Map<String, Object> body) {
        try {
            String field = String.valueOf(body.getOrDefault("field", ""));
            String value = String.valueOf(body.getOrDefault("value", ""));
            UserProfileEntity updated = profileService.resolveConflictByProfile(field, value);
            if (updated == null) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未知字段: " + field));
            return ResponseEntity.ok(Map.of("success", true, "data", updated));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private void diffField(List<Map<String, Object>> out, String field, String resumeVal, String profileVal) {
        if (resumeVal == null && profileVal == null) return;
        if (resumeVal == null && profileVal != null) {
            out.add(Map.of("field", field, "status", "profile_only", "resume", "", "profile", profileVal));
        } else if (resumeVal != null && profileVal == null) {
            out.add(Map.of("field", field, "status", "resume_only", "resume", resumeVal, "profile", ""));
        } else if (!resumeVal.equals(profileVal)) {
            out.add(Map.of("field", field, "status", "conflict", "resume", resumeVal, "profile", profileVal));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        Object o = parent.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private String stringVal(Map<?, ?> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    /** AI 写回确认 */
    @PostMapping("/confirm-writeback")
    public ResponseEntity<Map<String, Object>> confirmWriteback(@RequestBody Map<String, Object> body) {
        Long sessionId = body.get("session_id") == null ? null : Long.valueOf(body.get("session_id").toString());
        Long systemCardMessageId = body.get("system_card_message_id") == null ? null : Long.valueOf(body.get("system_card_message_id").toString());
        boolean confirm = Boolean.TRUE.equals(body.get("confirm"));
        try {
            // 该接口在 AiChatController 中调用更合适，此处仅做占位
            return ResponseEntity.ok(Map.of("success", true, "data", Map.of("note", "请通过 /api/ai-chat/confirm 调用")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }
}