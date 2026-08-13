package com.getjobs.delivery.service;

import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.zhilian.ZhilianConfig;

import java.util.List;
import java.util.Map;

/**
 * 把用户在投递中心填写的 platform_configs 覆盖到 Spring Bean 中的 *Config。
 * 仅覆盖用户实际传入的字段（非 null / 非空），不抹掉全局默认。
 */
public final class ConfigOverrideApplier {
    private ConfigOverrideApplier() {}

    // ---------- Boss：19 个字段 ----------
    public static void applyBoss(BossConfig base, Map<String, Object> o) {
        if (base == null || o == null || o.isEmpty()) return;
        if (str(o.get("keywords")) != null) base.setKeywords(asList(o.get("keywords")));
        if (str(o.get("city")) != null)     base.setCityCode(asList(o.get("city")));
        if (str(o.get("industry")) != null) base.setIndustry(asList(o.get("industry")));
        if (str(o.get("experience")) != null) base.setExperience(asList(o.get("experience")));
        if (str(o.get("degree")) != null)   base.setDegree(asList(o.get("degree")));
        if (str(o.get("salary")) != null)   base.setSalary(asList(o.get("salary")));
        if (str(o.get("scale")) != null)    base.setScale(asList(o.get("scale")));
        if (str(o.get("stage")) != null)    base.setStage(asList(o.get("stage")));
        if (str(o.get("jobType")) != null)  base.setJobType(str(o.get("jobType")));
        if (o.get("expectedSalaryMin") instanceof Number || o.get("expectedSalaryMax") instanceof Number) {
            Integer mn = o.get("expectedSalaryMin") instanceof Number ? ((Number) o.get("expectedSalaryMin")).intValue() : null;
            Integer mx = o.get("expectedSalaryMax") instanceof Number ? ((Number) o.get("expectedSalaryMax")).intValue() : null;
            java.util.List<Integer> pair = new java.util.ArrayList<>();
            if (mn != null) pair.add(mn);
            if (mx != null) pair.add(mx);
            base.setExpectedSalary(pair);
        }
        if (str(o.get("sayHi")) != null)    base.setSayHi(str(o.get("sayHi")));
        if (o.get("enableAI") instanceof Boolean) base.setEnableAI((Boolean) o.get("enableAI"));
        if (o.get("sendImgResume") instanceof Boolean) base.setSendImgResume((Boolean) o.get("sendImgResume"));
        if (o.get("filterDeadHr") instanceof Boolean)  base.setFilterDeadHR((Boolean) o.get("filterDeadHr"));
        if (str(o.get("deadStatus")) != null) base.setDeadStatus(asList(o.get("deadStatus")));
        if (o.get("debugger") instanceof Boolean) base.setDebugger((Boolean) o.get("debugger"));
        if (o.get("waitTime") instanceof Number)     base.setWaitTime(String.valueOf(((Number) o.get("waitTime")).intValue()));
    }

    // ---------- Job51：3 个字段 ----------
    public static void applyJob51(Job51Config base, Map<String, Object> o) {
        if (base == null || o == null || o.isEmpty()) return;
        if (str(o.get("keywords")) != null) base.setKeywords(asList(o.get("keywords")));
        if (str(o.get("jobArea")) != null) base.setJobArea(asList(o.get("jobArea")));
        if (str(o.get("salary")) != null)  base.setSalary(asList(o.get("salary")));
    }

    // ---------- Liepin：3 个字段 ----------
    public static void applyLiepin(LiepinConfig base, Map<String, Object> o) {
        if (base == null || o == null || o.isEmpty()) return;
        if (str(o.get("keywords")) != null) base.setKeywords(asList(o.get("keywords")));
        if (str(o.get("city")) != null)     base.setCityCode(str(o.get("city")));
        if (str(o.get("salary")) != null)   base.setSalary(str(o.get("salary")));
    }

    // ---------- Zhilian：3 个字段 ----------
    public static void applyZhilian(ZhilianConfig base, Map<String, Object> o) {
        if (base == null || o == null || o.isEmpty()) return;
        if (str(o.get("keywords")) != null) base.setKeywords(asList(o.get("keywords")));
        if (str(o.get("city")) != null)     base.setCityCode(str(o.get("city")));
        if (str(o.get("salary")) != null)   base.setSalary(str(o.get("salary")));
    }

    // ---------- helpers ----------
    private static String str(Object v) {
        if (v == null) return null;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() ? null : s;
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object v) {
        if (v == null) return null;
        if (v instanceof List) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object i : (List<Object>) v) if (i != null) {
                String s = String.valueOf(i).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return null;
            // "[a, b, c]"
            if (s.startsWith("[") && s.endsWith("]")) {
                String inner = s.substring(1, s.length() - 1);
                java.util.List<String> out = new java.util.ArrayList<>();
                for (String t : inner.split(",")) {
                    String t2 = t.trim();
                    if (t2.startsWith("\"") || t2.startsWith("'")) t2 = t2.substring(1);
                    if (t2.endsWith("\"") || t2.endsWith("'")) t2 = t2.substring(0, t2.length() - 1);
                    if (!t2.isEmpty()) out.add(t2);
                }
                return out;
            }
            // "a, b, c"
            if (s.contains(",")) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (String t : s.split(",")) {
                    String t2 = t.trim();
                    if (!t2.isEmpty()) out.add(t2);
                }
                return out;
            }
            return java.util.List.of(s);
        }
        return null;
    }
}