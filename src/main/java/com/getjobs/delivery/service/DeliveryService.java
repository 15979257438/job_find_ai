package com.getjobs.delivery.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.delivery.entity.DeliveryRequestEntity;
import com.getjobs.delivery.entity.DeliveryTargetEntity;
import com.getjobs.delivery.mapper.DeliveryRequestMapper;
import com.getjobs.delivery.mapper.DeliveryTargetMapper;
import com.getjobs.profile.service.MergedViewService;
import com.getjobs.resume.entity.ResumeEntity;
import com.getjobs.resume.entity.ResumeVersionEntity;
import com.getjobs.resume.mapper.ResumeMapper;
import com.getjobs.resume.service.ResumeService;
import com.getjobs.resume.service.TemplateService;
import com.getjobs.worker.service.PlatformDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 投递任务服务：需求落库、岗位拉取与匹配、过滤、差异化生成、投递触发、状态机。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeliveryService {
    private final DeliveryRequestMapper requestMapper;
    private final DeliveryTargetMapper targetMapper;
    private final ResumeMapper resumeMapper;
    private final ResumeService resumeService;
    private final TemplateService templateService;
    private final MergedViewService mergedViewService;
    private final AiService aiService;
    private final PlatformDispatchService dispatchService;

    private static final ObjectMapper OM = new ObjectMapper();

    /** 提交投递需求（FR-DEL-001） */
    @Transactional
    public DeliveryRequestEntity submit(DeliveryRequestEntity req) {
        if (req.getResumeId() == null) throw new IllegalArgumentException("resumeId 必填");
        ResumeEntity r = resumeMapper.selectById(req.getResumeId());
        if (r == null) throw new IllegalArgumentException("简历不存在");
        if (!"PARSED".equals(r.getParseStatus())) throw new IllegalStateException("简历尚未解析完成");
        // 要求 Profile 存在
        Long pid = mergedViewService.ensureDefaultProfileId();
        if (pid == null) throw new IllegalStateException("请先在个人数据库补全基础信息");

        if (req.getMaxCount() == null || req.getMaxCount() <= 0) req.setMaxCount(20);
        if (req.getMaxCount() > 100) req.setMaxCount(100);
        if (req.getMatchThreshold() == null || req.getMatchThreshold() < 40) req.setMatchThreshold(60);
        if (req.getMatchThreshold() < 40) req.setMatchThreshold(40);
        if (req.getMode() == null) req.setMode("STANDARD");
        if (req.getTemplateId() == null || req.getTemplateId().isBlank()) req.setTemplateId("editorial-dark-v1");
        req.setStatus("READY");
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.insert(req);
        log.info("投递需求已提交 requestId={} resumeId={}", req.getId(), req.getResumeId());
        return req;
    }

    /** 拉取岗位并落库（FR-DEL-002 / FR-DEL-003） */
    @Transactional
    public List<DeliveryTargetEntity> pullJobsAndMatch(Long requestId, List<Map<String, Object>> seedJobs) {
        DeliveryRequestEntity req = requestMapper.selectById(requestId);
        if (req == null) throw new IllegalArgumentException("投递需求不存在");
        Long profileId = mergedViewService.ensureDefaultProfileId();
        Map<String, Object> mergedWithResume = mergedViewService.merge(profileId, req.getResumeId());

        List<DeliveryTargetEntity> out = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (Map<String, Object> j : seedJobs) {
            String platform = String.valueOf(j.getOrDefault("platform", ""));
            String jobId = String.valueOf(j.getOrDefault("job_id", ""));
            if (platform.isBlank() || jobId.isBlank()) continue;
            String key = platform + ":" + jobId;
            if (!seenKeys.add(key)) continue;
            DeliveryTargetEntity t = new DeliveryTargetEntity();
            t.setDeliveryRequestId(requestId);
            t.setPlatform(platform);
            t.setJobId(jobId);
            t.setCompanyName(str(j, "company_name"));
            t.setJobName(str(j, "job_name"));
            t.setSalary(str(j, "salary"));
            t.setLocation(str(j, "location"));
            t.setJdText(str(j, "jd_text"));
            t.setStatus("PENDING");
            t.setCreatedAt(LocalDateTime.now());
            t.setUpdatedAt(LocalDateTime.now());
            // 重复跳过
            DeliveryTargetEntity existing = findExisting(requestId, platform, jobId);
            if (existing != null) {
                out.add(existing);
                continue;
            }
            targetMapper.insert(t);
            out.add(t);
        }
        // 触发匹配评分（优先 AI，失败降级到规则评分）
        for (DeliveryTargetEntity t : out) {
            if (t.getMatchScore() == null) {
                Map<String, Object> score = null;
                String error = null;
                try {
                    score = aiScore(mergedWithResume, t, req);
                } catch (Throwable e) {
                    error = e.getMessage();
                    log.warn("AI 评分失败，降级到规则评分 targetId={}: {}", t.getId(), e.getMessage());
                }
                if (score == null || score.isEmpty()) {
                    try { score = scoreJob(mergedWithResume, t, req); }
                    catch (Exception e) { log.warn("规则评分也失败 targetId={}: {}", t.getId(), e.getMessage()); }
                }
                if (score != null && !score.isEmpty()) {
                    t.setMatchScore(score.getOrDefault("score", 0) instanceof Number ? ((Number) score.get("score")).intValue() : 0);
                    t.setScoreBreakdownJson(toJson(score));
                } else {
                    t.setMatchScore(50);
                    t.setScoreBreakdownJson("{\"score\":50,\"reason\":\"评分失败兜底\"}");
                }
                t.setUpdatedAt(LocalDateTime.now());
                targetMapper.updateById(t);
            }
        }
        // 过滤并排序
        return filterAndSort(out, req);
    }

    /** 过滤与排序（FR-DEL-004） */
    public List<DeliveryTargetEntity> filterAndSort(List<DeliveryTargetEntity> list, DeliveryRequestEntity req) {
        List<String> blacklist = readStringList(req.getBlacklistKeywordsJson());
        int threshold = req.getMatchThreshold() == null ? 60 : req.getMatchThreshold();
        if (threshold < 40) threshold = 40;
        for (DeliveryTargetEntity t : list) {
            String reason = null;
            for (String b : blacklist) {
                if (b == null || b.isBlank()) continue;
                String all = ((t.getCompanyName() == null ? "" : t.getCompanyName()) + " " + (t.getJobName() == null ? "" : t.getJobName())).toLowerCase();
                if (all.contains(b.toLowerCase())) { reason = "黑名单:" + b; break; }
            }
            if (reason == null && t.getMatchScore() != null && t.getMatchScore() < threshold) {
                reason = "匹配分<" + threshold;
            }
            if (reason != null && !"FILTERED".equals(t.getStatus())) {
                t.setStatus("FILTERED");
                t.setFilterReason(reason);
                t.setUpdatedAt(LocalDateTime.now());
                targetMapper.updateById(t);
            }
        }
        list.sort((a, b) -> Integer.compare(b.getMatchScore() == null ? 0 : b.getMatchScore(), a.getMatchScore() == null ? 0 : a.getMatchScore()));
        return list;
    }

    /** 取投递数量上限的前 N 个非 FILTERED 目标 */
    public List<DeliveryTargetEntity> pickTopN(Long requestId, int n) {
        List<DeliveryTargetEntity> all = targetMapper.findByRequestId(requestId);
        List<DeliveryTargetEntity> ok = new ArrayList<>();
        for (DeliveryTargetEntity t : all) {
            if ("FILTERED".equals(t.getStatus())) continue;
            ok.add(t);
        }
        ok.sort((a, b) -> Integer.compare(b.getMatchScore() == null ? 0 : b.getMatchScore(), a.getMatchScore() == null ? 0 : a.getMatchScore()));
        return ok.size() > n ? ok.subList(0, n) : ok;
    }

    /** 为目标生成岗位定制版本简历（FR-DEL-005） */
    @Transactional
    public ResumeVersionEntity generateJobSpecific(Long requestId, Long targetId) {
        DeliveryRequestEntity req = requestMapper.selectById(requestId);
        DeliveryTargetEntity t = targetMapper.selectById(targetId);
        if (req == null || t == null) throw new IllegalArgumentException("request/target 不存在");
        // 修正：profileId 应取默认 profile（1），resumeId 取 req.resumeId
        Long profileId = mergedViewService.ensureDefaultProfileId();
        Map<String, Object> merged = mergedViewService.merge(profileId, req.getResumeId());
        Map<String, Object> adjustments = null;
        try {
            adjustments = computeAdjustments(merged, t, req);
        } catch (Exception e) {
            log.warn("生成 adjustments 失败：{}，使用空调整", e.getMessage());
            adjustments = Map.of();
        }
        ResumeVersionEntity v = templateService.renderAndSave(req.getResumeId(), "JOB_SPECIFIC", req.getTemplateId(), t.getPlatform() + ":" + t.getJobId(), merged, adjustments);
        t.setResumeVersionId(v.getId());
        t.setUpdatedAt(LocalDateTime.now());
        targetMapper.updateById(t);
        return v;
    }

    /** 标记投递结果 */
    @Transactional
    public void markResult(Long targetId, String status, String errorMessage) {
        DeliveryTargetEntity t = targetMapper.selectById(targetId);
        if (t == null) return;
        t.setStatus(status);
        t.setErrorMessage(errorMessage);
        t.setDeliveredAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        targetMapper.updateById(t);
    }

    /** 重试 FAILED 目标 */
    @Transactional
    public void retryFailed(Long targetId) {
        DeliveryTargetEntity t = targetMapper.selectById(targetId);
        if (t == null) return;
        if (!"FAILED".equals(t.getStatus())) throw new IllegalStateException("仅 FAILED 可重试");
        t.setStatus("PENDING");
        t.setErrorMessage(null);
        t.setUpdatedAt(LocalDateTime.now());
        targetMapper.updateById(t);
    }

    public DeliveryRequestEntity getRequest(Long id) { return requestMapper.selectById(id); }
    public DeliveryTargetEntity getTarget(Long id) { return targetMapper.selectById(id); }
    public List<DeliveryTargetEntity> listTargets(Long requestId) { return targetMapper.findByRequestId(requestId); }

    /** 仅把 configValidated / updatedAt 回写（不阻塞主流程） */
    @Transactional
    public void updatePlatformConfigValidated(DeliveryRequestEntity req) {
        if (req == null || req.getId() == null) return;
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
    }

    // ---------- 平台配置：归一化 + 校验 ----------

    /** 把用户提交的 platform_configs 归一化(逗号/方括号/JSON 数组 → 统一 List<String>) */
    public Map<String, Object> normalizePlatformConfigs(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) return out;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            String key = e.getKey();
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> src = (Map<String, Object>) e.getValue();
            Map<String, Object> dst = new LinkedHashMap<>();
            for (Map.Entry<String, Object> f : src.entrySet()) {
                dst.put(f.getKey(), normalizeValue(f.getValue()));
            }
            out.put(key, dst);
        }
        return out;
    }

    private Object normalizeValue(Object v) {
        if (v == null) return null;
        if (v instanceof List) {
            List<String> arr = new ArrayList<>();
            for (Object i : (List<?>) v) {
                if (i == null) continue;
                String s = String.valueOf(i).trim();
                if (!s.isEmpty()) arr.add(s);
            }
            return arr;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            // "[a, b, c]" → [a, b, c]
            if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
                String inner = s.substring(1, s.length() - 1).trim();
                if (inner.isEmpty()) return List.of();
                List<String> arr = new ArrayList<>();
                for (String tok : inner.split(",")) {
                    String tok2 = tok.trim();
                    if (tok2.startsWith("\"") || tok2.startsWith("'")) tok2 = tok2.substring(1);
                    if (tok2.endsWith("\"") || tok2.endsWith("'")) tok2 = tok2.substring(0, tok2.length() - 1);
                    if (!tok2.isEmpty()) arr.add(tok2);
                }
                return arr;
            }
            // "a, b, c" → [a, b, c]
            if (s.contains(",") && !s.contains("{") && !s.contains(":")) {
                List<String> arr = new ArrayList<>();
                for (String tok : s.split(",")) {
                    String tok2 = tok.trim();
                    if (!tok2.isEmpty()) arr.add(tok2);
                }
                return arr;
            }
            return s;
        }
        if (v instanceof Boolean || v instanceof Number) return v;
        return v;
    }

    /** 校验每个被勾选平台的必填项；缺则抛 IllegalArgumentException */
    public void validateRequiredPlatformConfigs(DeliveryRequestEntity req) {
        if (req == null) throw new IllegalArgumentException("req 为空");
        List<String> platforms = readStringList(req.getPlatformsJson());
        if (platforms.isEmpty()) throw new IllegalArgumentException("请至少勾选一个平台");
        Map<String, Object> cfg = readPlatformConfigs(req.getPlatformConfigsJson());
        if (cfg == null) cfg = Map.of();
        for (String p : platforms) {
            String pk = p == null ? "" : p.trim().toLowerCase();
            if (pk.isEmpty()) continue;
            Map<String, Object> c = subMap(cfg, pk);
            List<String> keywords = readFieldAsList(c, "keywords");
            if (keywords.isEmpty()) {
                throw new IllegalArgumentException("平台 " + pk + " 缺少必填项: keywords(至少 1 个)");
            }
            switch (pk) {
                case "boss": {
                    requireBoss(c, "city");          requireBoss(c, "jobType");
                    requireBoss(c, "experience");     requireBoss(c, "degree");
                    break;
                }
                case "job51": {
                    List<String> area = readFieldAsList(c, "jobArea");
                    if (area.isEmpty()) throw new IllegalArgumentException("平台 job51 缺少必填项: jobArea(至少 1 个)");
                    break;
                }
                case "liepin":
                case "zhilian": {
                    String city = stringField(c, "city");
                    if (city.isEmpty()) throw new IllegalArgumentException("平台 " + pk + " 缺少必填项: city");
                    break;
                }
                default: throw new IllegalArgumentException("不支持的平台: " + pk);
            }
        }
    }

    private void requireBoss(Map<String, Object> c, String field) {
        Object v = c.get(field);
        if (v == null) throw new IllegalArgumentException("平台 boss 缺少必填项: " + field);
        if (v instanceof String) {
            if (((String) v).trim().isEmpty()) throw new IllegalArgumentException("平台 boss 缺少必填项: " + field);
        } else if (v instanceof List) {
            if (((List<?>) v).isEmpty()) throw new IllegalArgumentException("平台 boss 缺少必填项: " + field);
        }
    }

    private String stringField(Map<String, Object> c, String field) {
        Object v = c.get(field);
        if (v == null) return "";
        return String.valueOf(v).trim();
    }

    private List<String> readFieldAsList(Map<String, Object> c, String field) {
        Object v = c.get(field);
        if (v == null) return List.of();
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object i : (List<?>) v) if (i != null) {
                String s = String.valueOf(i).trim(); if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return List.of();
            if (s.startsWith("[") && s.endsWith("]")) {
                String inner = s.substring(1, s.length() - 1);
                List<String> out = new ArrayList<>();
                for (String t : inner.split(",")) {
                    String t2 = t.trim();
                    if (t2.startsWith("\"") || t2.startsWith("'")) t2 = t2.substring(1);
                    if (t2.endsWith("\"") || t2.endsWith("'")) t2 = t2.substring(0, t2.length() - 1);
                    if (!t2.isEmpty()) out.add(t2);
                }
                return out;
            }
            return List.of(s);
        }
        return List.of(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPlatformConfigs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return OM.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    // ---------- 平台派发 ----------

    /** 派发 4 个 worker（按 platforms 选）执行覆盖式爬虫投递 */
    public String executeByPlatforms(DeliveryRequestEntity req) {
        if (req == null || req.getId() == null) throw new IllegalArgumentException("投递需求不存在");
        validateRequiredPlatformConfigs(req);
        List<String> platforms = readStringList(req.getPlatformsJson());
        if (platforms.isEmpty()) throw new IllegalArgumentException("platforms 为空");
        // 懒解析 platform_configs
        Map<String, Object> cfg = readPlatformConfigs(req.getPlatformConfigsJson());
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger(0);
        StringBuilder dispatched = new StringBuilder();
        for (String p : platforms) {
            String pk = p == null ? "" : p.trim().toLowerCase();
            Map<String, Object> overrides = subMap(cfg, pk);
            if (pk.equals("boss"))     { dispatchAsync("boss",     () -> dispatchService.executeDeliveryOverride(req, overrides, "boss"));     dispatched.append("boss,");     ran.incrementAndGet(); }
            else if (pk.equals("job51")){ dispatchAsync("job51",    () -> dispatchService.executeDeliveryOverride(req, overrides, "job51"));    dispatched.append("job51,");    ran.incrementAndGet(); }
            else if (pk.equals("liepin")){dispatchAsync("liepin",   () -> dispatchService.executeDeliveryOverride(req, overrides, "liepin"));   dispatched.append("liepin,");   ran.incrementAndGet(); }
            else if (pk.equals("zhilian")){dispatchAsync("zhilian", () -> dispatchService.executeDeliveryOverride(req, overrides, "zhilian")); dispatched.append("zhilian,"); ran.incrementAndGet(); }
            else log.warn("未知平台跳过: {}", pk);
        }
        req.setStatus("RUNNING");
        req.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(req);
        return dispatched.toString();
    }

    private void dispatchAsync(String tag, Runnable r) {
        new Thread(() -> {
            try { r.run(); }
            catch (Throwable t) { log.error("[{}] 派发执行异常: {}", tag, t.getMessage(), t); }
        }, "delivery-dispatch-" + tag).start();
    }

    /** 计算 merged_view 与 JD 的匹配分（FR-DEL-003） */
    public Map<String, Object> scoreJob(Map<String, Object> merged, DeliveryTargetEntity t, DeliveryRequestEntity req) {
        String jd = t.getJdText() == null ? (t.getJobName() == null ? "" : t.getJobName()) : t.getJdText();
        List<String> reqKeywords = readStringList(req.getKeywordsJson());
        List<String> skills = readStringList(stringFromMerged(merged, "skills"));
        Map<String, Object> basics = subMap(merged, "basics");
        List<String> profileExtensions = collectProfileExtension(merged);

        // 维度：关键词覆盖 / 技能重合 / 经验 / 城市薪资 / 扩展段加成
        int keywordHit = countKeywordHits(reqKeywords, jd);
        int keywordScore = clamp(reqKeywords.isEmpty() ? 70 : keywordHit * 100 / Math.max(1, reqKeywords.size()), 0, 100);

        // 技能匹配：skills 通常是"分类：描述"长句，须先抽取每条技能的核心关键词（按常见分隔符切，取首段/单词）
        int skillHit = 0;
        if (skills != null) {
            for (String s : skills) {
                if (s == null) continue;
                String ss = s.toLowerCase();
                if (jd == null) continue;
                String jdLow = jd.toLowerCase();
                // 1) 整句命中
                if (jdLow.contains(ss)) { skillHit++; continue; }
                // 2) 取短关键词（前 12 字 或 第一段冒号前）
                String shortKey = extractSkillKeyword(s);
                if (!shortKey.isEmpty() && jdLow.contains(shortKey.toLowerCase())) skillHit++;
            }
        }
        int skillScore = clamp(skills == null || skills.isEmpty() ? 60 : skillHit * 100 / skills.size(), 0, 100);

        int expScore = req.getExperience() == null || req.getExperience().isBlank() ? 80 : 80; // 简化

        int cityScore = 80;
        if (basics.get("expected_city") instanceof List) {
            List<?> exCities = (List<?>) basics.get("expected_city");
            String loc = t.getLocation() == null ? "" : t.getLocation();
            for (Object c : exCities) {
                if (c != null && loc.contains(c.toString())) { cityScore = 100; break; }
                cityScore = 60;
            }
        }
        int salaryScore = 80;
        int bonus = 0;
        for (String ext : profileExtensions) {
            if (ext != null && jd != null && jd.toLowerCase().contains(ext.toLowerCase())) {
                bonus += 5;
                if (bonus > 5) bonus = 5;
            }
        }
        int total = clamp((int) Math.round(keywordScore * 0.35 + skillScore * 0.30 + expScore * 0.10 + cityScore * 0.15 + salaryScore * 0.10 + bonus), 0, 100);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("score", total);
        breakdown.put("keyword_coverage", keywordScore);
        breakdown.put("skill_overlap", skillScore);
        breakdown.put("experience", expScore);
        breakdown.put("city_salary", (cityScore + salaryScore) / 2);
        breakdown.put("extension_bonus", bonus);
        return breakdown;
    }

    /** 调用 AI 计算更精细的匹配分（带 breakdown） */
    public Map<String, Object> aiScore(Map<String, Object> merged, DeliveryTargetEntity t, DeliveryRequestEntity req) {
        try {
            String prompt = "你是岗位匹配专家。请基于以下 merged_view 与 JD 文本，返回严格 JSON：\n" +
                    "{\"score\":0-100整数, \"breakdown\": {\"keyword\":0-100, \"skill\":0-100, \"experience\":0-100, \"city_salary\":0-100, \"extension_bonus\":0-5}}\n" +
                    "只输出 JSON。\nmerged_view=" + toJson(merged) + "\nJD=" + (t.getJdText() == null ? t.getJobName() : t.getJdText());
            String resp = aiService.sendRequest(prompt);
            String json = stripCodeBlock(resp);
            return OM.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("AI 评分失败 fallback：{}", e.getMessage());
            return scoreJob(merged, t, req);
        }
    }

    /** 计算 AI 差异化调整对象（FR-DEL-006） */
    public Map<String, Object> computeAdjustments(Map<String, Object> merged, DeliveryTargetEntity t, DeliveryRequestEntity req) {
        try {
            String prompt = "你是简历差异化专家。请基于 merged_view 与 JD，输出严格 JSON（不要任何其他文字）：\n" +
                    "{\n" +
                    "  \"summary\":\"面向本岗位改写的个人简介\",\n" +
                    "  \"highlighted_skills\":[\"技能排序后最相关的技能\"],\n" +
                    "  \"experience_emphasis\":[{\"experience_index\":0,\"bullets\":[\"调整后的要点\"]}],\n" +
                    "  \"project_emphasis\":[{\"project_index\":0,\"bullets\":[\"调整后的要点\"]}],\n" +
                    "  \"extension_emphasis\":[{\"source\":\"competitions\",\"items\":[\"ACM 区域赛银奖 2022\"]}],\n" +
                    "  \"removed_sections\":[],\n" +
                    "  \"added_keywords\":[\"JD 中必须出现的关键词\"]\n" +
                    "}\n" +
                    "merged_view=" + toJson(merged) + "\nJD=" + (t.getJdText() == null ? t.getJobName() : t.getJdText());
            String resp = aiService.sendRequest(prompt);
            return OM.readValue(stripCodeBlock(resp), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("差异化调整对象生成失败：{}，使用空对象", e.getMessage());
            return Map.of();
        }
    }

    // ---------- 私有辅助 ----------

    private List<String> collectProfileExtension(Map<String, Object> merged) {
        List<String> out = new ArrayList<>();
        Object ext = merged.get("extension");
        if (ext instanceof Map) {
            for (Object o : ((Map<?, ?>) ext).values()) {
                if (o instanceof List) for (Object i : (List<?>) o) if (i != null) out.add(i.toString());
            }
        }
        return out;
    }

    private String stringFromMerged(Map<String, Object> merged, String key) {
        Object v = merged.get(key);
        if (v == null) return null;
        try { return OM.writeValueAsString(v); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subMap(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int countKeywordHits(List<String> keywords, String text) {
        if (text == null) return 0;
        String lower = text.toLowerCase();
        int n = 0;
        for (String k : keywords) if (k != null && !k.isBlank() && lower.contains(k.toLowerCase())) n++;
        return n;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return OM.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : v.toString();
    }

    private String toJson(Object o) {
        try { return OM.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    /** 从技能长句中抽取核心关键词（去掉"分类：..." 前缀，取前 8 个汉字或首段冒号前） */
    private String extractSkillKeyword(String s) {
        if (s == null) return "";
        String t = s.trim();
        int cIdx = t.indexOf('：');
        if (cIdx < 0) cIdx = t.indexOf(':');
        if (cIdx > 0 && cIdx <= 6) t = t.substring(cIdx + 1).trim();
        // 取前 8 个汉字字符（中文 / 英文都按字符算）
        if (t.length() > 12) t = t.substring(0, 12);
        return t;
    }

    private String stripCodeBlock(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        // 1) 剥离 <think>...</think> 思维链（MiniMax-M3 / DeepSeek 等推理模型）
        t = t.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "").trim();
        // 2) 剥离 ```json ... ``` 代码块围栏
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n'); if (nl > 0) t = t.substring(nl + 1);
            int lf = t.lastIndexOf("```"); if (lf > 0) t = t.substring(0, lf);
            t = t.trim();
        }
        // 3) 兜底：截取第一个完整的顶层 JSON 对象 {...}
        int firstBrace = t.indexOf('{');
        if (firstBrace < 0) return "{}";
        int depth = 0;
        boolean inStr = false;
        boolean escapeNext = false;
        for (int i = firstBrace; i < t.length(); i++) {
            char c = t.charAt(i);
            if (escapeNext) { escapeNext = false; continue; }
            if (c == '\\') { escapeNext = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return t.substring(firstBrace, i + 1);
            }
        }
        return t.substring(firstBrace);
    }

    private DeliveryTargetEntity findExisting(Long reqId, String platform, String jobId) {
        for (DeliveryTargetEntity t : targetMapper.findByRequestId(reqId)) {
            if (platform.equals(t.getPlatform()) && jobId.equals(t.getJobId())) return t;
        }
        return null;
    }
}