package com.getjobs.profile.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.profile.entity.ProfileChangeLogEntity;
import com.getjobs.profile.entity.ProfileExtensionEntity;
import com.getjobs.profile.entity.UserProfileEntity;
import com.getjobs.profile.mapper.ProfileChangeLogMapper;
import com.getjobs.profile.mapper.ProfileExtensionMapper;
import com.getjobs.profile.mapper.UserProfileMapper;
import com.getjobs.resume.entity.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 个人数据库（Profile）服务：初始化、从简历回填、AI 写回。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileService {
    private final UserProfileMapper profileMapper;
    private final ProfileExtensionMapper extensionMapper;
    private final ProfileChangeLogMapper changeLogMapper;
    private final MergedViewService mergedViewService;
    private final AiService aiService;

    private static final ObjectMapper OM = new ObjectMapper();

    /** 懒初始化：项目启动时若 user_profile 为空则自动创建 1 条 */
    @Transactional
    public void initIfEmpty() {
        UserProfileEntity existing = profileMapper.findDefault();
        if (existing != null) return;
        UserProfileEntity entity = new UserProfileEntity();
        entity.setIsDefault(1);
        entity.setExpectedCityJson("[]");
        entity.setSkillsJson("[]");
        entity.setCertificatesJson("[]");
        entity.setLanguagesJson("[]");
        entity.setEducationJson("[]");
        entity.setExperiencesJson("[]");
        entity.setProjectsJson("[]");
        entity.setConflictLog("[]");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        profileMapper.insert(entity);
        ProfileExtensionEntity ext = new ProfileExtensionEntity();
        ext.setProfileId(entity.getId());
        ext.setCompetitionsJson("[]");
        ext.setAwardsJson("[]");
        ext.setOpenSourceJson("[]");
        ext.setSideProjectsJson("[]");
        ext.setPapersJson("[]");
        ext.setPublicSpeakingJson("[]");
        ext.setOtherFactsJson("[]");
        ext.setUpdatedAt(LocalDateTime.now());
        extensionMapper.insert(ext);
        log.info("Profile 自动初始化完成，id={}", entity.getId());
    }

    /** 获取默认 Profile */
    @Transactional(readOnly = true)
    public UserProfileEntity getDefault() {
        UserProfileEntity p = profileMapper.findDefault();
        if (p == null) p = profileMapper.findFirst();
        return p;
    }

    /** 获取 Profile + Extension */
    @Transactional(readOnly = true)
    public Map<String, Object> getDefaultWithExtension() {
        UserProfileEntity p = getDefault();
        if (p == null) return Map.of();
        ProfileExtensionEntity ext = extensionMapper.findByProfileId(p.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", p);
        result.put("extension", ext == null ? new ProfileExtensionEntity() : ext);
        return result;
    }

    /** 从简历解析结果回填 Profile（FR-PROF-002） */
    @Transactional
    public void backfillFromResume(ResumeEntity resume) {
        if (resume == null || resume.getParsedJson() == null) return;
        UserProfileEntity localProfile = getDefault();
        if (localProfile == null) {
            initIfEmpty();
            localProfile = getDefault();
        }
        final UserProfileEntity profile = localProfile;
        Map<String, Object> parsed;
        try {
            parsed = OM.readValue(resume.getParsedJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("简历 JSON 解析失败", e);
            return;
        }
        List<Map<String, Object>> conflicts = parseListSafe(profile.getConflictLog());

        Map<String, Object> basics = castMap(parsed.get("basics"));
        // 字段级合并
        ifNotEmpty(basics.get("name"), v -> { if (isBlank(profile.getName())) profile.setName(v.toString()); else recordConflict(conflicts, "name", profile.getName(), v); });
        ifNotEmpty(basics.get("phone"), v -> { if (isBlank(profile.getPhone())) profile.setPhone(v.toString()); else recordConflict(conflicts, "phone", profile.getPhone(), v); });
        ifNotEmpty(basics.get("email"), v -> { if (isBlank(profile.getEmail())) profile.setEmail(v.toString()); else recordConflict(conflicts, "email", profile.getEmail(), v); });
        ifNotEmpty(basics.get("city"), v -> { if (isBlank(profile.getCity())) profile.setCity(v.toString()); else recordConflict(conflicts, "city", profile.getCity(), v); });
        Object expectedCityObj = basics.get("expected_city");
        if (expectedCityObj instanceof List) {
            try {
                String newVal = toJson(expectedCityObj);
                String old = profile.getExpectedCityJson();
                if (isBlank(old) || "[]".equals(old)) profile.setExpectedCityJson(newVal);
            } catch (Exception ignore) {}
        }
        Object expectedSalaryObj = basics.get("expected_salary_k");
        if (expectedSalaryObj instanceof List) {
            List<?> ks = (List<?>) expectedSalaryObj;
            if (ks.size() >= 1 && profile.getExpectedSalaryMinK() == null) profile.setExpectedSalaryMinK(toInt(ks.get(0)));
            if (ks.size() >= 2 && profile.getExpectedSalaryMaxK() == null) profile.setExpectedSalaryMaxK(toInt(ks.get(1)));
        }

        ifNotEmpty(parsed.get("summary"), v -> { if (isBlank(profile.getSummary())) profile.setSummary(v.toString()); });
        ifNotEmpty(parsed.get("skills"), v -> mergeIntoArray(profile::setSkillsJson, profile.getSkillsJson(), (List<?>) v));
        ifNotEmpty(parsed.get("certificates"), v -> mergeIntoArray(profile::setCertificatesJson, profile.getCertificatesJson(), (List<?>) v));
        ifNotEmpty(parsed.get("languages"), v -> mergeIntoArray(profile::setLanguagesJson, profile.getLanguagesJson(), (List<?>) v));
        ifNotEmpty(parsed.get("education"), v -> mergeIntoArray(profile::setEducationJson, profile.getEducationJson(), (List<?>) v));
        ifNotEmpty(parsed.get("experiences"), v -> mergeIntoArray(profile::setExperiencesJson, profile.getExperiencesJson(), (List<?>) v));
        ifNotEmpty(parsed.get("projects"), v -> mergeIntoArray(profile::setProjectsJson, profile.getProjectsJson(), (List<?>) v));

        // other_facts: 散落事实 AI 抽取
        try {
            String summary = String.valueOf(parsed.getOrDefault("summary", ""));
            if (!summary.isBlank()) {
                String extracted = aiService.sendRequest(
                        "请从以下文本中抽取 1~3 条'竞赛/获奖/开源/副业/论文/演讲/其他亮点'事实，每条独立成行，不要解释其他内容：\n" + summary);
                if (extracted != null && !extracted.isBlank()) {
                    ProfileExtensionEntity ext = extensionMapper.findByProfileId(profile.getId());
                    if (ext == null) {
                        ext = new ProfileExtensionEntity();
                        ext.setProfileId(profile.getId());
                        ext.setCompetitionsJson("[]");
                        ext.setAwardsJson("[]");
                        ext.setOpenSourceJson("[]");
                        ext.setSideProjectsJson("[]");
                        ext.setPapersJson("[]");
                        ext.setPublicSpeakingJson("[]");
                        ext.setOtherFactsJson("[]");
                    }
                    List<String> list = new ArrayList<>(parseStringListSafe(ext.getOtherFactsJson()));
                    for (String line : extracted.split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !list.contains(t)) list.add(t);
                    }
                    ext.setOtherFactsJson(toJson(list));
                    ext.setUpdatedAt(LocalDateTime.now());
                    if (ext.getId() == null) extensionMapper.insert(ext);
                    else extensionMapper.updateById(ext);
                }
            }
        } catch (Exception e) {
            log.warn("抽取 other_facts 失败: {}", e.getMessage());
        }

        try {
            profile.setConflictLog(toJson(conflicts));
        } catch (Exception ignore) {}
        profile.setLastMergedResumeId(resume.getId());
        profile.setLastMergedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(profile);

        ProfileChangeLogEntity log1 = new ProfileChangeLogEntity();
        log1.setProfileId(profile.getId());
        log1.setSource("RESUME_PARSE");
        try { log1.setChangeJson(toJson(Map.of("resume_id", resume.getId(), "conflicts", conflicts.size()))); }
        catch (Exception ignore) {}
        log1.setCreatedAt(LocalDateTime.now());
        changeLogMapper.insert(log1);

        mergedViewService.invalidate();
    }

    /** AI 写回 Profile（统一入口） */
    @Transactional
    public Long applyAiWriteBack(Long sessionId, Long messageId, String field, Object value) {
        UserProfileEntity profile = getDefault();
        if (profile == null) {
            initIfEmpty();
            profile = getDefault();
        }
        ProfileExtensionEntity ext = extensionMapper.findByProfileId(profile.getId());
        if (ext == null) {
            ext = new ProfileExtensionEntity();
            ext.setProfileId(profile.getId());
            ext.setCompetitionsJson("[]");
            ext.setAwardsJson("[]");
            ext.setOpenSourceJson("[]");
            ext.setSideProjectsJson("[]");
            ext.setPapersJson("[]");
            ext.setPublicSpeakingJson("[]");
            ext.setOtherFactsJson("[]");
        }

        String beforeSnap = snap(profile, ext);
        boolean changed = false;
        try {
            switch (field) {
                case "basics.name": profile.setName(value.toString()); changed = true; break;
                case "basics.phone": profile.setPhone(value.toString()); changed = true; break;
                case "basics.email": profile.setEmail(value.toString()); changed = true; break;
                case "basics.city": profile.setCity(value.toString()); changed = true; break;
                case "basics.expected_city": profile.setExpectedCityJson(toJson(value)); changed = true; break;
                case "basics.expected_salary_min": profile.setExpectedSalaryMinK(toInt(value)); changed = true; break;
                case "basics.expected_salary_max": profile.setExpectedSalaryMaxK(toInt(value)); changed = true; break;
                case "skill.add":
                    profile.setSkillsJson(appendToJsonArray(profile.getSkillsJson(), value.toString()));
                    changed = true; break;
                case "skill.remove":
                    String removed = removeFromJsonArray(profile.getSkillsJson(), value.toString());
                    if (removed != null) { profile.setSkillsJson(removed); changed = true; }
                    break;
                case "competition.add": ext.setCompetitionsJson(appendObjectToJsonArray(ext.getCompetitionsJson(), value)); changed = true; break;
                case "award.add": ext.setAwardsJson(appendObjectToJsonArray(ext.getAwardsJson(), value)); changed = true; break;
                case "open_source.add": ext.setOpenSourceJson(appendObjectToJsonArray(ext.getOpenSourceJson(), value)); changed = true; break;
                case "side_project.add": ext.setSideProjectsJson(appendObjectToJsonArray(ext.getSideProjectsJson(), value)); changed = true; break;
                case "paper.add": ext.setPapersJson(appendObjectToJsonArray(ext.getPapersJson(), value)); changed = true; break;
                case "public_speaking.add": ext.setPublicSpeakingJson(appendObjectToJsonArray(ext.getPublicSpeakingJson(), value)); changed = true; break;
                case "other_fact.add":
                    if (value != null) ext.setOtherFactsJson(appendObjectToJsonArray(ext.getOtherFactsJson(), value));
                    changed = true; break;
                // FR-AI-005 完整工具集：经验/项目/教育 增改删
                case "experience.add":
                    profile.setExperiencesJson(appendObjectToJsonArray(profile.getExperiencesJson(), value));
                    changed = true; break;
                case "experience.update": {
                    int idx = indexFromValue(value);
                    String updated = updateArrayAtIndex(profile.getExperiencesJson(), idx, objectFromValue(value));
                    if (updated != null) { profile.setExperiencesJson(updated); changed = true; }
                    break;
                }
                case "experience.remove": {
                    int idx = indexFromValue(value);
                    String after = removeArrayAtIndex(profile.getExperiencesJson(), idx);
                    if (after != null) { profile.setExperiencesJson(after); changed = true; }
                    break;
                }
                case "project.add":
                    profile.setProjectsJson(appendObjectToJsonArray(profile.getProjectsJson(), value));
                    changed = true; break;
                case "project.update": {
                    int idx = indexFromValue(value);
                    String updated = updateArrayAtIndex(profile.getProjectsJson(), idx, objectFromValue(value));
                    if (updated != null) { profile.setProjectsJson(updated); changed = true; }
                    break;
                }
                case "project.remove": {
                    int idx = indexFromValue(value);
                    String after = removeArrayAtIndex(profile.getProjectsJson(), idx);
                    if (after != null) { profile.setProjectsJson(after); changed = true; }
                    break;
                }
                case "education.add":
                    profile.setEducationJson(appendObjectToJsonArray(profile.getEducationJson(), value));
                    changed = true; break;
                case "education.update": {
                    int idx = indexFromValue(value);
                    String updated = updateArrayAtIndex(profile.getEducationJson(), idx, objectFromValue(value));
                    if (updated != null) { profile.setEducationJson(updated); changed = true; }
                    break;
                }
                case "education.remove": {
                    int idx = indexFromValue(value);
                    String after = removeArrayAtIndex(profile.getEducationJson(), idx);
                    if (after != null) { profile.setEducationJson(after); changed = true; }
                    break;
                }
                default:
                    log.warn("未知写回字段: {}", field);
                    return null;
            }
        } catch (Exception e) {
            log.error("AI 写回失败", e);
            return null;
        }

        if (!changed) return null;

        profile.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(profile);
        ext.setUpdatedAt(LocalDateTime.now());
        if (ext.getId() == null) extensionMapper.insert(ext);
        else extensionMapper.updateById(ext);

        String afterSnap = snap(profile, ext);
        ProfileChangeLogEntity log1 = new ProfileChangeLogEntity();
        log1.setProfileId(profile.getId());
        log1.setSource("AI_CHAT");
        log1.setSessionId(sessionId);
        log1.setMessageId(messageId);
        try {
            log1.setChangeJson(toJson(Map.of(
                    "field", field,
                    "value", value == null ? null : value.toString(),
                    "before", beforeSnap,
                    "after", afterSnap)));
        } catch (Exception ignore) { log1.setChangeJson("{\"field\":\"" + field + "\"}"); }
        log1.setCreatedAt(LocalDateTime.now());
        changeLogMapper.insert(log1);

        mergedViewService.invalidate();
        return log1.getId();
    }

    /** 手工编辑 Profile（含必填校验 + 冲突日志写入） */
    @Transactional
    public UserProfileEntity manualUpdate(UserProfileEntity updated) {
        UserProfileEntity p = getDefault();
        if (p == null) {
            initIfEmpty();
            p = getDefault();
        }
        // FR-PROF-003 必填字段校验
        if (updated.getName() != null) p.setName(updated.getName());
        if (updated.getPhone() != null) p.setPhone(updated.getPhone());
        if (updated.getEmail() != null) p.setEmail(updated.getEmail());
        validateRequiredFields(p);
        if (updated.getCity() != null) p.setCity(updated.getCity());
        if (updated.getExpectedCityJson() != null) p.setExpectedCityJson(updated.getExpectedCityJson());
        if (updated.getExpectedSalaryMinK() != null) p.setExpectedSalaryMinK(updated.getExpectedSalaryMinK());
        if (updated.getExpectedSalaryMaxK() != null) p.setExpectedSalaryMaxK(updated.getExpectedSalaryMaxK());
        if (updated.getSummary() != null) p.setSummary(updated.getSummary());
        if (updated.getSkillsJson() != null) p.setSkillsJson(updated.getSkillsJson());
        if (updated.getCertificatesJson() != null) p.setCertificatesJson(updated.getCertificatesJson());
        if (updated.getLanguagesJson() != null) p.setLanguagesJson(updated.getLanguagesJson());
        if (updated.getEducationJson() != null) p.setEducationJson(updated.getEducationJson());
        if (updated.getExperiencesJson() != null) p.setExperiencesJson(updated.getExperiencesJson());
        if (updated.getProjectsJson() != null) p.setProjectsJson(updated.getProjectsJson());
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);

        // 记录手工变更日志
        ProfileChangeLogEntity changeLog = new ProfileChangeLogEntity();
        changeLog.setProfileId(p.getId());
        changeLog.setSource("MANUAL");
        try { changeLog.setChangeJson(toJson(Map.of("action", "manual_update", "updated_at", p.getUpdatedAt().toString()))); }
        catch (Exception ignore) {}
        changeLog.setCreatedAt(LocalDateTime.now());
        changeLogMapper.insert(changeLog);

        mergedViewService.invalidate();
        return p;
    }

    /** 解析 conflict_log 供前端展示 */
    public List<Map<String, Object>> getConflicts() {
        UserProfileEntity p = getDefault();
        if (p == null || p.getConflictLog() == null) return List.of();
        try { return OM.readValue(p.getConflictLog(), new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    /** 用户在冲突卡片上选择 "以 Profile 为准"：写入冲突字段后清冲突日志 */
    @Transactional
    public UserProfileEntity resolveConflictByProfile(String field, String profileValue) {
        UserProfileEntity p = getDefault();
        if (p == null) return null;
        // 写入字段
        switch (field) {
            case "name": p.setName(profileValue); break;
            case "phone": p.setPhone(profileValue); break;
            case "email": p.setEmail(profileValue); break;
            case "city": p.setCity(profileValue); break;
            default: log.warn("未支持的冲突字段: {}", field); return null;
        }
        // 清除冲突日志
        p.setConflictLog("[]");
        p.setUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(p);
        mergedViewService.invalidate();
        return p;
    }

    public String snap(UserProfileEntity p, ProfileExtensionEntity e) {
        try {
            return toJson(Map.of(
                    "profile", p == null ? Map.of() : p,
                    "extension", e == null ? Map.of() : e));
        } catch (Exception ignore) {
            return "{}";
        }
    }

    // ---------- 私有辅助 ----------

    private void recordConflict(List<Map<String, Object>> list, String field, Object profileVal, Object resumeVal) {
        list.add(Map.of("field", field, "profile", profileVal, "resume", resumeVal));
    }

    private void ifNotEmpty(Object v, java.util.function.Consumer<Object> action) {
        if (v == null) return;
        if (v instanceof String && ((String) v).isBlank()) return;
        if (v instanceof Collection && ((Collection<?>) v).isEmpty()) return;
        action.accept(v);
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void mergeIntoArray(java.util.function.Consumer<String> setter, String oldJson, List<?> newItems) {
        try {
            List<String> cur = new ArrayList<>(parseStringListSafe(oldJson));
            for (Object o : newItems) if (o != null && !cur.contains(o.toString())) cur.add(o.toString());
            setter.accept(toJson(cur));
        } catch (Exception ignore) {}
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return Map.of();
    }

    private String appendToJsonArray(String oldJson, String newItem) {
        try {
            List<String> cur = new ArrayList<>(parseStringListSafe(oldJson));
            if (!cur.contains(newItem)) cur.add(newItem);
            return toJson(cur);
        } catch (Exception e) {
            return oldJson == null ? "[]" : oldJson;
        }
    }

    private String appendObjectToJsonArray(String oldJson, Object newItem) {
        try {
            List<Object> cur = new ArrayList<>(parseListSafe(oldJson));
            if (newItem != null && !cur.contains(newItem)) cur.add(newItem);
            return toJson(cur);
        } catch (Exception e) {
            return oldJson == null ? "[]" : oldJson;
        }
    }

    private String removeFromJsonArray(String oldJson, String item) {
        try {
            List<String> cur = new ArrayList<>(parseStringListSafe(oldJson));
            if (cur.remove(item)) return toJson(cur);
        } catch (Exception ignore) {}
        return null;
    }

    private List<String> parseStringListSafe(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OM.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> parseListSafe(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return OM.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }

    private String toJson(Object o) throws Exception {
        return OM.writeValueAsString(o);
    }

    /** FR-RES-003 / FR-PROF-003 必填字段校验：姓名/手机号/邮箱不能为空（保存时禁止放行） */
    public static class RequiredFieldException extends RuntimeException {
        public RequiredFieldException(String field) { super("必填字段不能为空: " + field); }
    }

    public void validateRequiredFields(UserProfileEntity p) {
        if (isBlank(p.getName())) throw new RequiredFieldException("name");
        if (isBlank(p.getPhone())) throw new RequiredFieldException("phone");
        if (isBlank(p.getEmail())) throw new RequiredFieldException("email");
    }

    /** 校验 Resume parsed_json 中 basics 三项非空 */
    public void validateResumeRequiredFields(String parsedJson) {
        if (parsedJson == null || parsedJson.isBlank()) throw new RequiredFieldException("parsed_json");
        try {
            Map<String, Object> m = OM.readValue(parsedJson, new TypeReference<Map<String, Object>>() {});
            Object basics = m.get("basics");
            if (!(basics instanceof Map)) throw new RequiredFieldException("basics");
            Map<?, ?> b = (Map<?, ?>) basics;
            if (isBlank(strVal(b, "name"))) throw new RequiredFieldException("basics.name");
            if (isBlank(strVal(b, "phone"))) throw new RequiredFieldException("basics.phone");
            if (isBlank(strVal(b, "email"))) throw new RequiredFieldException("basics.email");
        } catch (RequiredFieldException e) { throw e; }
        catch (Exception e) { throw new RequiredFieldException("parsed_json 格式错误"); }
    }

    private String strVal(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    // ---------- FR-AI-005 经验/项目/教育 索引工具 ----------

    @SuppressWarnings("unchecked")
    private int indexFromValue(Object value) {
        if (value instanceof Map) {
            Object idx = ((Map<String, Object>) value).get("index");
            if (idx instanceof Number) return ((Number) idx).intValue();
            try { return Integer.parseInt(idx.toString()); } catch (Exception e) { return -1; }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectFromValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            // 去掉 index 字段后剩余即真实对象
            Map<String, Object> out = new LinkedHashMap<>(m);
            out.remove("index");
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private String updateArrayAtIndex(String oldJson, int idx, Map<String, Object> newObj) {
        if (idx < 0) return null;
        try {
            List<Map<String, Object>> list = new ArrayList<>(parseListSafe(oldJson));
            if (idx >= list.size()) return null;
            list.set(idx, newObj);
            return toJson(list);
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private String removeArrayAtIndex(String oldJson, int idx) {
        if (idx < 0) return null;
        try {
            List<Map<String, Object>> list = new ArrayList<>(parseListSafe(oldJson));
            if (idx >= list.size()) return null;
            list.remove(idx);
            return toJson(list);
        } catch (Exception e) { return null; }
    }
}