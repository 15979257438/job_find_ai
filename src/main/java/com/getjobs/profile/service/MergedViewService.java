package com.getjobs.profile.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.profile.entity.ProfileExtensionEntity;
import com.getjobs.profile.entity.UserProfileEntity;
import com.getjobs.profile.mapper.ProfileExtensionMapper;
import com.getjobs.profile.mapper.UserProfileMapper;
import com.getjobs.resume.entity.ResumeEntity;
import com.getjobs.resume.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并视图服务：profile ⨆ resume
 * 所有对简历的优化、生成、匹配、预览、对话查询都必须经过本服务。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MergedViewService {
    private final UserProfileMapper profileMapper;
    private final ProfileExtensionMapper extensionMapper;
    private final ResumeMapper resumeMapper;

    private static final ObjectMapper OM = new ObjectMapper();

    /** 简单进程级缓存：profileId+resumeId -> JSON 字符串 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 计算缓存键 */
    public String cacheKey(Long profileId, Long resumeId, UserProfileEntity profile, ResumeEntity resume) {
        long pUp = profile == null ? 0L : (profile.getUpdatedAt() == null ? 0 : profile.getUpdatedAt().toEpochSecond(java.time.ZoneOffset.UTC));
        long rUp = resume == null ? 0L : (resume.getUpdatedAt() == null ? 0 : resume.getUpdatedAt().toEpochSecond(java.time.ZoneOffset.UTC));
        return profileId + ":" + resumeId + ":" + pUp + ":" + rUp;
    }

    /** 失效缓存（在 Profile / Resume 写回后调用） */
    public void invalidate() {
        cache.clear();
    }

    /**
     * 计算 merged_view，纯函数。
     * 规则：
     * - basics/education/experiences/projects/skills/certificates/languages：同名字段以 Resume 为准。
     * - Resume 没有而 Profile 有的字段：进入 merged。
     * - 冲突记录到 profile.conflict_log。
     * - extension 段（competitions/awards/open_source/side_projects/papers/public_speaking/other_facts）只取 Profile。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> merge(Long profileId, Long resumeId) {
        UserProfileEntity profile = profileId == null ? null : profileMapper.selectById(profileId);
        ResumeEntity resume = resumeId == null ? null : resumeMapper.selectById(resumeId);
        return merge(profile, resume);
    }

    public Map<String, Object> merge(UserProfileEntity profile, ResumeEntity resume) {
        // 缓存命中则直接返回
        String cacheK = cacheKey(profile == null ? null : profile.getId(), resume == null ? null : resume.getId(), profile, resume);
        String hit = cache.get(cacheK);
        if (hit != null) {
            try { return OM.readValue(hit, new TypeReference<Map<String, Object>>() {}); }
            catch (Exception ignore) {}
        }

        Map<String, Object> resumeMap = parseJson(resume == null ? null : resume.getParsedJson());
        Map<String, Object> basicsR = section(resumeMap, "basics");
        Map<String, Object> profileBasics = profileBasics(profile);

        Map<String, Object> basics = new LinkedHashMap<>();
        // 字段级冲突处理：Resume 优先
        Set<String> basicKeys = new LinkedHashSet<>();
        if (profileBasics != null) basicKeys.addAll(profileBasics.keySet());
        if (basicsR != null) basicKeys.addAll(basicsR.keySet());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String bk : basicKeys) {
            Object rVal = basicsR == null ? null : basicsR.get(bk);
            Object pVal = profileBasics == null ? null : profileBasics.get(bk);
            if (isEmpty(rVal) && !isEmpty(pVal)) {
                basics.put(bk, pVal);
            } else if (!isEmpty(rVal) && !isEmpty(pVal) && !Objects.equals(rVal, pVal)) {
                // 冲突：以 Resume 为准，记录日志
                basics.put(bk, rVal);
                conflicts.add(Map.of("field", bk, "resume", rVal, "profile", pVal));
            } else {
                if (rVal != null) basics.put(bk, rVal);
                else if (pVal != null) basics.put(bk, pVal);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basics", basics);
        result.put("summary", firstNonNull(textAt(resumeMap, "summary"), profile == null ? null : profile.getSummary()));
        result.put("education", mergeList(resumeMap, "education", profile == null ? null : profile.getEducationJson()));
        result.put("experiences", mergeList(resumeMap, "experiences", profile == null ? null : profile.getExperiencesJson()));
        result.put("projects", mergeList(resumeMap, "projects", profile == null ? null : profile.getProjectsJson()));
        result.put("skills", mergeSkills(resumeMap, profile == null ? null : profile.getSkillsJson()));
        result.put("certificates", mergeList(resumeMap, "certificates", profile == null ? null : profile.getCertificatesJson()));
        result.put("languages", mergeList(resumeMap, "languages", profile == null ? null : profile.getLanguagesJson()));
        result.put("extension", extensionSection(profile == null ? null : profile.getId()));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resume_id", resume == null ? null : resume.getId());
        meta.put("profile_id", profile == null ? null : profile.getId());
        meta.put("conflicts", conflicts);
        meta.put("merged_at", java.time.LocalDateTime.now().toString());
        result.put("_meta", meta);

        // 缓存
        try {
            cache.put(cacheK, OM.writeValueAsString(result));
        } catch (Exception ignore) {}
        return result;
    }

    public String mergeAsJson(Long profileId, Long resumeId) {
        try {
            return OM.writeValueAsString(merge(profileId, resumeId));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 取当前默认 profile 的 id（不存在则懒创建） */
    @Transactional
    public Long ensureDefaultProfileId() {
        UserProfileEntity existing = profileMapper.findDefault();
        if (existing != null) return existing.getId();
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
        entity.setCreatedAt(java.time.LocalDateTime.now());
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        profileMapper.insert(entity);
        // 同步创建 extension
        ProfileExtensionEntity ext = new ProfileExtensionEntity();
        ext.setProfileId(entity.getId());
        ext.setCompetitionsJson("[]");
        ext.setAwardsJson("[]");
        ext.setOpenSourceJson("[]");
        ext.setSideProjectsJson("[]");
        ext.setPapersJson("[]");
        ext.setPublicSpeakingJson("[]");
        ext.setOtherFactsJson("[]");
        ext.setUpdatedAt(java.time.LocalDateTime.now());
        extensionMapper.insert(ext);
        log.info("已创建默认 Profile，id={}", entity.getId());
        return entity.getId();
    }

    // ---------- 私有辅助 ----------

    private Map<String, Object> extensionSection(Long profileId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (profileId == null) {
            out.put("competitions", List.of());
            out.put("awards", List.of());
            out.put("open_source", List.of());
            out.put("side_projects", List.of());
            out.put("papers", List.of());
            out.put("public_speaking", List.of());
            out.put("other_facts", List.of());
            return out;
        }
        ProfileExtensionEntity ext = extensionMapper.findByProfileId(profileId);
        if (ext == null) {
            ProfileExtensionEntity e = new ProfileExtensionEntity();
            e.setProfileId(profileId);
            e.setCompetitionsJson("[]");
            e.setAwardsJson("[]");
            e.setOpenSourceJson("[]");
            e.setSideProjectsJson("[]");
            e.setPapersJson("[]");
            e.setPublicSpeakingJson("[]");
            e.setOtherFactsJson("[]");
            e.setUpdatedAt(java.time.LocalDateTime.now());
            extensionMapper.insert(e);
            ext = e;
        }
        out.put("competitions", parseList(ext.getCompetitionsJson()));
        out.put("awards", parseList(ext.getAwardsJson()));
        out.put("open_source", parseList(ext.getOpenSourceJson()));
        out.put("side_projects", parseList(ext.getSideProjectsJson()));
        out.put("papers", parseList(ext.getPapersJson()));
        out.put("public_speaking", parseList(ext.getPublicSpeakingJson()));
        out.put("other_facts", parseList(ext.getOtherFactsJson()));
        return out;
    }

    private Map<String, Object> section(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        Object o = parent.get(key);
        if (o instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        return null;
    }

    private List<?> mergeList(Map<String, Object> parent, String key, String profileJson) {
        List<?> resumeList = parent == null ? null : asList(parent.get(key));
        List<?> profileList = parseList(profileJson);
        if (isEmpty(resumeList)) {
            return profileList == null ? List.of() : profileList;
        }
        return resumeList;
    }

    private List<?> mergeSkills(Map<String, Object> parent, String profileJson) {
        List<?> resumeSkills = parent == null ? null : asList(parent.get("skills"));
        List<?> profileSkills = parseList(profileJson);
        if (isEmpty(resumeSkills)) return profileSkills == null ? List.of() : profileSkills;
        // 合并去重
        Set<String> set = new LinkedHashSet<>();
        if (resumeSkills != null) for (Object o : resumeSkills) if (o != null) set.add(o.toString());
        if (profileSkills != null) for (Object o : profileSkills) if (o != null) set.add(o.toString());
        return new ArrayList<>(set);
    }

    private List<?> asList(Object o) {
        if (o == null) return null;
        if (o instanceof List) return (List<?>) o;
        return List.of(o);
    }

    private Map<String, Object> profileBasics(UserProfileEntity profile) {
        if (profile == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (profile.getName() != null) m.put("name", profile.getName());
        if (profile.getPhone() != null) m.put("phone", profile.getPhone());
        if (profile.getEmail() != null) m.put("email", profile.getEmail());
        if (profile.getCity() != null) m.put("city", profile.getCity());
        if (profile.getExpectedCityJson() != null) m.put("expected_city", parseList(profile.getExpectedCityJson()));
        if (profile.getExpectedSalaryMinK() != null || profile.getExpectedSalaryMaxK() != null) {
            m.put("expected_salary_k", new ArrayList<Object>() {{
                if (profile.getExpectedSalaryMinK() != null) add(profile.getExpectedSalaryMinK());
                if (profile.getExpectedSalaryMaxK() != null) add(profile.getExpectedSalaryMaxK());
            }});
        }
        return m;
    }

    private String textAt(Map<String, Object> parent, String key) {
        if (parent == null) return null;
        Object o = parent.get(key);
        return o == null ? null : o.toString();
    }

    private Object firstNonNull(Object a, Object b) {
        return a == null ? b : a;
    }

    private boolean isEmpty(Object o) {
        if (o == null) return true;
        if (o instanceof String) return ((String) o).isBlank();
        if (o instanceof Collection) return ((Collection<?>) o).isEmpty();
        if (o instanceof Map) return ((Map<?, ?>) o).isEmpty();
        return false;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OM.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("parseJson 失败: {}", e.getMessage());
            return null;
        }
    }

    private List<?> parseList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return OM.readValue(json, new TypeReference<List<?>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}