package com.getjobs.resume.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.resume.entity.ResumeEntity;
import com.getjobs.resume.entity.ResumeVersionEntity;
import com.getjobs.resume.mapper.ResumeVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.LinkedHashMap;

/**
 * 模板渲染服务：基于 merged_view + 调整对象生成 HTML 与（简化）PDF 文本。
 * PDF 由 PlaywrightManager 调用 headless 渲染（沿用项目既有能力），本服务负责：
 *   1. HTML 字符串生成（preview / 后端 PDF 源）
 *   2. HTML 文件落盘到 ./target/data/resumes/versions/
 *
 * 设计：editorial-dark 风格。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TemplateService {
    private final ResumeVersionMapper versionMapper;
    private final PdfRenderService pdfRenderService;
    private static final ObjectMapper OM = new ObjectMapper();

    private static final String VERSION_ROOT;
    static {
        String configured = System.getProperty("app.resume.storage-root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("RESUME_STORAGE_ROOT", "");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.home") + "/get_jobs-data/resumes";
        }
        VERSION_ROOT = configured + "/versions";
        System.out.println("[TemplateService] VERSION_ROOT = " + new File(VERSION_ROOT).getAbsolutePath());
    }

    /** 支持的模板清单 */
    public static final List<Map<String, Object>> TEMPLATES = List.of(
            Map.of(
                    "id", "editorial-dark-v1",
                    "name", "编辑深色 v1",
                    "description", "深底 #0a0a0f + 暖色强调 #f0b90b，衬线标题 + 无衬线正文（设计系统一致）。",
                    "preview", "/templates/editorial-dark-v1/preview.svg"
            ),
            Map.of(
                    "id", "editorial-light-v2",
                    "name", "编辑浅色 v2",
                    "description", "浅底 #fafaf7 + 深墨 #111827 + 暗金 #b8860b。同系列姊妹版（亮色场景）。",
                    "preview", "/templates/editorial-light-v2/preview.svg"
            )
    );

    public List<Map<String, Object>> listTemplates() { return TEMPLATES; }

    /**
     * 渲染 HTML（merged_view + adjustments）
     * @param resumeId 主简历 id
     * @param templateId 模板标识
     * @param merged merged_view
     * @param adjustments 差异化调整对象
     * @return 渲染后的 HTML 字符串
     */
    public String renderHtml(Long resumeId, String templateId, Map<String, Object> merged, Map<String, Object> adjustments) {
        String safeTpl = TEMPLATES.stream().anyMatch(t -> templateId.equals(t.get("id"))) ? templateId : "editorial-dark-v1";
        // 应用 AI 差异化调整到 merged（不修改原对象，拷贝出来）
        Map<String, Object> effectiveMerged = applyAdjustments(merged, adjustments);
        return switch (safeTpl) {
            case "editorial-dark-v1" -> renderEditorialDark(effectiveMerged, adjustments);
            case "editorial-light-v2" -> renderEditorialLight(effectiveMerged, adjustments);
            default -> renderEditorialDark(effectiveMerged, adjustments);
        };
    }

    /**
     * 把 AI 给出的 adjustments 叠加到 merged_view 上：
     *   - summary 用 AI 改写版（如有）
     *   - highlighted_skills 提到最前
     *   - extension_emphasis 注入到对应 extension 段开头
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAdjustments(Map<String, Object> merged, Map<String, Object> adj) {
        if (adj == null || adj.isEmpty()) return merged;
        Map<String, Object> out = new LinkedHashMap<>(merged);
        // 1) summary 覆盖
        Object sum = adj.get("summary");
        if (sum != null && !sum.toString().isBlank()) out.put("summary", sum.toString());
        // 2) skills 重新排序：highlighted_skills 在前
        Object hsObj = adj.get("highlighted_skills");
        if (hsObj instanceof List) {
            List<String> highlighted = new ArrayList<>();
            for (Object o : (List<?>) hsObj) if (o != null) highlighted.add(o.toString());
            List<String> original = stringListAt(merged, "skills");
            List<String> merged2 = new ArrayList<>(highlighted);
            for (String s : original) if (!merged2.contains(s)) merged2.add(s);
            out.put("skills", merged2);
        }
        // 3) extension_emphasis：把 items 当作额外事实写入 extension.<source>
        Object eeObj = adj.get("extension_emphasis");
        if (eeObj instanceof List) {
            Map<String, Object> extBase = subMap(merged, "extension");
            Map<String, Object> extOut = new LinkedHashMap<>(extBase);
            for (Object item : (List<?>) eeObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                String source = String.valueOf(m.getOrDefault("source", ""));
                Object itemsObj = m.get("items");
                if (source.isEmpty() || !(itemsObj instanceof List)) continue;
                // items 是字符串列表，每个直接作为一条对象 {name:...} 加入
                List<Map<String, Object>> existing = new ArrayList<>();
                Object existingObj = extOut.get(source);
                if (existingObj instanceof List) for (Object o : (List<?>) existingObj)
                    if (o instanceof Map) existing.add((Map<String, Object>) o);
                for (Object it : (List<?>) itemsObj) {
                    if (it == null) continue;
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("name", it.toString());
                    existing.add(rec);
                }
                extOut.put(source, existing);
            }
            out.put("extension", extOut);
        }
        // 4) added_keywords 不直接用，但写入 meta 以便检索
        if (adj.get("added_keywords") instanceof List) {
            out.put("added_keywords", adj.get("added_keywords"));
        }
        return out;
    }

    /** 渲染并落盘，返回版本实体 */
    public ResumeVersionEntity renderAndSave(Long resumeId, String type, String templateId, String targetJobId, Map<String, Object> merged, Map<String, Object> adjustments) {
        try {
            Files.createDirectories(Paths.get(VERSION_ROOT));
        } catch (IOException e) {
            log.warn("创建版本目录失败: {}", e.getMessage());
        }
        String html = renderHtml(resumeId, templateId, merged, adjustments);

        ResumeVersionEntity v;
        if ("JOB_SPECIFIC".equals(type) && targetJobId != null) {
            v = versionMapper.findJobSpecific(resumeId, targetJobId, templateId);
        } else {
            v = null;
        }
        if (v == null) {
            v = new ResumeVersionEntity();
            v.setResumeId(resumeId);
            v.setType(type);
            v.setTemplateId(templateId);
            v.setTargetJobId(targetJobId);
            v.setCreatedAt(java.time.LocalDateTime.now());
            v.setUpdatedAt(java.time.LocalDateTime.now());
            versionMapper.insert(v);
        } else {
            v.setUpdatedAt(java.time.LocalDateTime.now());
        }

        String htmlPath = VERSION_ROOT + "/" + v.getId() + ".html";
        String pdfPath = VERSION_ROOT + "/" + v.getId() + ".pdf";
        try {
            Files.write(Paths.get(htmlPath), html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("写 HTML 失败", e);
        }
        v.setRenderedHtmlPath(htmlPath);
        // FR-RES-008 / FR-TPL-003：同步生成 PDF（带超时保护，避免阻塞主线程）
        try {
            boolean ok = pdfRenderService.renderPdfFromHtmlFileWithTimeout(htmlPath, pdfPath, 8000L);
            if (!ok) {
                log.warn("PDF 渲染超时，使用占位 PDF 路径：{}", pdfPath);
            }
        } catch (Throwable e) {
            log.warn("PDF 渲染异常：{}（仍记录路径）", e.getMessage());
        }
        v.setRenderedPdfPath(pdfPath);
        try {
            v.setAdjustmentsJson(adjustments == null ? null : OM.writeValueAsString(adjustments));
        } catch (Exception ignore) {}
        versionMapper.updateById(v);
        return v;
    }

    /** 更新 PDF 路径（由 PDF 渲染任务调用） */
    public void setPdfPath(Long versionId, String pdfPath) {
        ResumeVersionEntity v = versionMapper.selectById(versionId);
        if (v == null) return;
        v.setRenderedPdfPath(pdfPath);
        v.setUpdatedAt(java.time.LocalDateTime.now());
        versionMapper.updateById(v);
    }

    /** 读取已落盘 HTML */
    public String readHtml(Long versionId) {
        ResumeVersionEntity v = versionMapper.selectById(versionId);
        if (v == null || v.getRenderedHtmlPath() == null) return null;
        try { return new String(Files.readAllBytes(Paths.get(v.getRenderedHtmlPath())), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    // ---------- 私有渲染器 ----------

    private String renderEditorialDark(Map<String, Object> merged, Map<String, Object> adjustments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>简历 - ").append(escape(str(merged, "basics.name"))).append("</title>");
        sb.append("<style>");
        sb.append(editorialDarkCss());
        sb.append("</style></head><body class=\"resume-page\">");
        Map<String, Object> basics = subMap(merged, "basics");

        // 顶部：姓名 / 联系方式
        sb.append("<header class=\"resume-header\">");
        sb.append("<h1 class=\"resume-name\">").append(escape(str(basics, "name"))).append("</h1>");
        sb.append("<div class=\"resume-contact\">");
        appendIfPresent(sb, basics, "phone", " · ");
        appendIfPresent(sb, basics, "email", " · ");
        appendIfPresent(sb, basics, "city", " · ");
        sb.append("</div></header>");

        // 期望
        Object expCity = basics.get("expected_city");
        Object expSal = basics.get("expected_salary_k");
        if (notEmpty(expCity) || notEmpty(expSal)) {
            sb.append("<div class=\"resume-expected\">期望：");
            if (notEmpty(expCity)) sb.append("城市 ").append(escape(toJsonInline(expCity)));
            if (notEmpty(expSal)) sb.append(" · 薪资 ").append(escape(toJsonInline(expSal)));
            sb.append("</div>");
        }

        // 个人简介
        String summary = stringAt(merged, "summary");
        if (notEmpty(summary)) {
            sb.append("<section class=\"resume-section\"><h2>个人简介</h2><p>").append(escape(summary)).append("</p></section>");
        }

        // 教育
        List<Map<String, Object>> edu = listAt(merged, "education");
        if (!edu.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>教育经历</h2><ul class=\"resume-list\">");
            for (Map<String, Object> e : edu) {
                sb.append("<li><div class=\"row\"><span>").append(escape(str(e, "school"))).append(" · ").append(escape(str(e, "major"))).append(" · ").append(escape(str(e, "degree"))).append("</span>");
                sb.append("<span class=\"muted\">").append(escape(str(e, "start"))).append(" ~ ").append(escape(str(e, "end"))).append("</span></div></li>");
            }
            sb.append("</ul></section>");
        }

        // 工作
        List<Map<String, Object>> exps = listAt(merged, "experiences");
        if (!exps.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>工作经历</h2><ul class=\"resume-list\">");
            int idx = 0;
            for (Map<String, Object> e : exps) {
                String title = str(e, "title");
                String company = str(e, "company");
                List<String> bullets = stringListAt(e, "bullets");
                List<String> overridden = findBulletOverride(adjustments, "experience_emphasis", idx, bullets);
                sb.append("<li><div class=\"row\"><span><b>").append(escape(company)).append("</b> · ").append(escape(title)).append("</span>");
                sb.append("<span class=\"muted\">").append(escape(str(e, "start"))).append(" ~ ").append(escape(str(e, "end"))).append("</span></div>");
                List<String> finalBullets = overridden != null ? overridden : bullets;
                if (!finalBullets.isEmpty()) {
                    sb.append("<ul class=\"bullets\">");
                    for (String b : finalBullets) sb.append("<li>").append(escape(b)).append("</li>");
                    sb.append("</ul>");
                }
                sb.append("</li>");
                idx++;
            }
            sb.append("</ul></section>");
        }

        // 项目
        List<Map<String, Object>> projects = listAt(merged, "projects");
        if (!projects.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>项目经历</h2><ul class=\"resume-list\">");
            int idx = 0;
            for (Map<String, Object> p : projects) {
                String name = str(p, "name");
                String role = str(p, "role");
                List<String> bullets = stringListAt(p, "bullets");
                List<String> overridden = findBulletOverride(adjustments, "project_emphasis", idx, bullets);
                sb.append("<li><div class=\"row\"><span><b>").append(escape(name)).append("</b> · ").append(escape(role)).append("</span></div>");
                List<String> finalBullets = overridden != null ? overridden : bullets;
                if (!finalBullets.isEmpty()) {
                    sb.append("<ul class=\"bullets\">");
                    for (String b : finalBullets) sb.append("<li>").append(escape(b)).append("</li>");
                    sb.append("</ul>");
                }
                sb.append("</li>");
                idx++;
            }
            sb.append("</ul></section>");
        }

        // 技能
        List<String> skills = stringListAt(merged, "skills");
        if (!skills.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>技能</h2><div class=\"tag-list\">");
            for (String s : skills) sb.append("<span class=\"tag\">").append(escape(s)).append("</span>");
            sb.append("</div></section>");
        }

        // 证书 / 语言
        List<String> certs = stringListAt(merged, "certificates");
        List<String> langs = stringListAt(merged, "languages");
        if (!certs.isEmpty() || !langs.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>证书 / 语言</h2><div class=\"tag-list\">");
            for (String c : certs) sb.append("<span class=\"tag\">").append(escape(c)).append("</span>");
            for (String l : langs) sb.append("<span class=\"tag\">").append(escape(l)).append("</span>");
            sb.append("</div></section>");
        }

        // 扩展段
        Map<String, Object> ext = subMap(merged, "extension");
        if (ext != null && !ext.isEmpty()) {
            renderExtension(sb, "竞赛", "competitions", ext);
            renderExtension(sb, "获奖", "awards", ext);
            renderExtension(sb, "开源", "open_source", ext);
            renderExtension(sb, "副业", "side_projects", ext);
            renderExtension(sb, "论文", "papers", ext);
            renderExtension(sb, "公开演讲", "public_speaking", ext);
            renderExtensionList(sb, "其他亮点", "other_facts", ext);
        }

        sb.append("<footer class=\"resume-footer\">© 简历生成于 ").append(java.time.LocalDateTime.now().toString()).append(" · 由 get_jobs 模板引擎生成</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void renderExtension(StringBuilder sb, String title, String key, Map<String, Object> ext) {
        Object raw = ext.get(key);
        List<Map<String, Object>> items = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) items.add((Map<String, Object>) o);
                else if (o != null) items.add(Map.of("name", o.toString()));
            }
        }
        if (items.isEmpty()) return;
        sb.append("<section class=\"resume-section\"><h2>").append(escape(title)).append(" <span class=\"source-tag\">来自个人数据库</span></h2><ul class=\"resume-list\">");
        for (Map<String, Object> item : items) {
            sb.append("<li>").append(escape(formatExtensionItem(item))).append("</li>");
        }
        sb.append("</ul></section>");
    }

    @SuppressWarnings("unchecked")
    private void renderExtensionList(StringBuilder sb, String title, String key, Map<String, Object> ext) {
        Object raw = ext.get(key);
        List<String> items = new ArrayList<>();
        if (raw instanceof List) for (Object o : (List<?>) raw) if (o != null) items.add(stripThinking(o.toString()));
        if (items.isEmpty()) return;
        sb.append("<section class=\"resume-section\"><h2>").append(escape(title)).append(" <span class=\"source-tag\">来自个人数据库</span></h2><ul class=\"resume-list\">");
        for (String s : items) sb.append("<li>").append(escape(s)).append("</li>");
        sb.append("</ul></section>");
    }

    /** 友好格式化扩展段项：例如 {name:Test, level:金奖, year:2024, role:队员} → "Test · 金奖 · 2024 · 队员" */
    private String formatExtensionItem(Map<String, Object> item) {
        // 优先字段
        String[] preferred = {"name", "title", "level", "venue", "year", "role", "stars", "desc", "description"};
        List<String> parts = new ArrayList<>();
        for (String p : preferred) {
            Object v = item.get(p);
            if (v == null) continue;
            String s = v.toString().trim();
            if (s.isEmpty()) continue;
            parts.add(s);
        }
        // 追加其他字段
        for (Map.Entry<String, Object> e : item.entrySet()) {
            if (Arrays.asList(preferred).contains(e.getKey())) continue;
            Object v = e.getValue();
            if (v == null) continue;
            String s = v.toString().trim();
            if (s.isEmpty()) continue;
            parts.add(e.getKey() + "=" + s);
        }
        return String.join(" · ", parts);
    }

    /** 剥离 <think>...</think> 推理前缀（防止 AI 抽取的 other_facts 把推理原文也存进去） */
    private String stripThinking(String s) {
        if (s == null) return "";
        return s.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "")
                .replaceAll("(?im)^\\s*<think>.*$", "")
                .trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> findBulletOverride(Map<String, Object> adjustments, String key, int idx, List<String> fallback) {
        if (adjustments == null) return null;
        Object o = adjustments.get(key);
        if (!(o instanceof List)) return null;
        for (Object item : (List<?>) o) {
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                Object mi = m.get("experience_index");
                Object pi = m.get("project_index");
                int wantIdx = key.equals("experience_emphasis") ? toInt(mi) : toInt(pi);
                if (wantIdx == idx) {
                    Object bullets = m.get("bullets");
                    if (bullets instanceof List) {
                        List<String> list = new ArrayList<>();
                        for (Object b : (List<?>) bullets) if (b != null) list.add(b.toString());
                        return list;
                    }
                }
            }
        }
        return null;
    }

    private Integer toInt(Object o) {
        if (o == null) return -1;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return -1; }
    }

    private String editorialDarkCss() {
        return """
                body.resume-page{margin:0;background:#0a0a0f;color:#e6e6ea;font-family:'DM Sans','PingFang SC','Microsoft YaHei',sans-serif;line-height:1.6;padding:48px 64px;max-width:900px;margin:0 auto;}
                .resume-header{border-bottom:1px solid #1f1f2a;padding-bottom:18px;margin-bottom:24px;}
                .resume-name{font-family:'Playfair Display','Lora',serif;font-weight:700;font-size:38px;line-height:1.2;color:#f5f5f7;margin:0;}
                .resume-contact{color:#9a9aa6;font-size:14px;margin-top:8px;}
                .resume-expected{color:#f0b90b;font-size:13px;margin-bottom:20px;letter-spacing:0.02em;}
                .resume-section{margin-top:28px;}
                .resume-section h2{font-family:'Playfair Display','Lora',serif;font-size:18px;color:#f0b90b;text-transform:uppercase;letter-spacing:0.12em;margin:0 0 10px;border-bottom:1px solid #1f1f2a;padding-bottom:4px;}
                .resume-list{list-style:none;padding:0;margin:0;}
                .resume-list li{padding:6px 0;border-bottom:1px dashed #1f1f2a;}
                .row{display:flex;justify-content:space-between;gap:12px;}
                .muted{color:#64748b;font-size:13px;}
                .bullets{padding-left:18px;margin:6px 0 0;}
                .bullets li{margin:2px 0;border:none;padding:0;}
                .tag-list{display:flex;flex-wrap:wrap;gap:8px;}
                .tag{display:inline-block;padding:4px 10px;border:1px solid #f0b90b;color:#f0b90b;font-size:12px;border-radius:2px;background:transparent;}
                .source-tag{display:inline-block;margin-left:8px;padding:1px 6px;background:#f0b90b;color:#0a0a0f;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;}
                .resume-footer{color:#64748b;font-size:11px;text-align:center;margin-top:48px;padding-top:14px;border-top:1px solid #1f1f2a;}
                """;
    }

    private String editorialLightCss() {
        return """
                body.resume-page{margin:0;background:#fafaf7;color:#111827;font-family:'DM Sans','PingFang SC','Microsoft YaHei',sans-serif;line-height:1.6;padding:48px 64px;max-width:900px;margin:0 auto;}
                .resume-header{border-bottom:1px solid #e5e7eb;padding-bottom:18px;margin-bottom:24px;}
                .resume-name{font-family:'Playfair Display','Lora',serif;font-weight:700;font-size:38px;line-height:1.2;color:#111827;margin:0;}
                .resume-contact{color:#6b7280;font-size:14px;margin-top:8px;}
                .resume-expected{color:#b8860b;font-size:13px;margin-bottom:20px;letter-spacing:0.02em;}
                .resume-section{margin-top:28px;}
                .resume-section h2{font-family:'Playfair Display','Lora',serif;font-size:18px;color:#b8860b;text-transform:uppercase;letter-spacing:0.12em;margin:0 0 10px;border-bottom:1px solid #e5e7eb;padding-bottom:4px;}
                .resume-list{list-style:none;padding:0;margin:0;}
                .resume-list li{padding:6px 0;border-bottom:1px dashed #e5e7eb;}
                .row{display:flex;justify-content:space-between;gap:12px;}
                .muted{color:#6b7280;font-size:13px;}
                .bullets{padding-left:18px;margin:6px 0 0;}
                .bullets li{margin:2px 0;border:none;padding:0;}
                .tag-list{display:flex;flex-wrap:wrap;gap:8px;}
                .tag{display:inline-block;padding:4px 10px;border:1px solid #b8860b;color:#b8860b;font-size:12px;border-radius:2px;background:transparent;}
                .source-tag{display:inline-block;margin-left:8px;padding:1px 6px;background:#b8860b;color:#fafaf7;font-size:10px;letter-spacing:0.1em;text-transform:uppercase;}
                .resume-footer{color:#6b7280;font-size:11px;text-align:center;margin-top:48px;padding-top:14px;border-top:1px solid #e5e7eb;}
                """;
    }

    /** 编辑浅色 v2 模板（同结构，亮色调色板） */
    private String renderEditorialLight(Map<String, Object> merged, Map<String, Object> adjustments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>简历 - ").append(escape(str(merged, "basics.name"))).append("</title>");
        sb.append("<style>");
        sb.append(editorialLightCss());
        sb.append("</style></head><body class=\"resume-page\">");
        Map<String, Object> basics = subMap(merged, "basics");

        sb.append("<header class=\"resume-header\">");
        sb.append("<h1 class=\"resume-name\">").append(escape(str(basics, "name"))).append("</h1>");
        sb.append("<div class=\"resume-contact\">");
        appendIfPresent(sb, basics, "phone", " · ");
        appendIfPresent(sb, basics, "email", " · ");
        appendIfPresent(sb, basics, "city", " · ");
        sb.append("</div></header>");

        Object expCity = basics.get("expected_city");
        Object expSal = basics.get("expected_salary_k");
        if (notEmpty(expCity) || notEmpty(expSal)) {
            sb.append("<div class=\"resume-expected\">期望：");
            if (notEmpty(expCity)) sb.append("城市 ").append(escape(toJsonInline(expCity)));
            if (notEmpty(expSal)) sb.append(" · 薪资 ").append(escape(toJsonInline(expSal)));
            sb.append("</div>");
        }

        String summary = stringAt(merged, "summary");
        if (notEmpty(summary)) {
            sb.append("<section class=\"resume-section\"><h2>个人简介</h2><p>").append(escape(summary)).append("</p></section>");
        }

        List<Map<String, Object>> edu = listAt(merged, "education");
        if (!edu.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>教育经历</h2><ul class=\"resume-list\">");
            for (Map<String, Object> e : edu) {
                sb.append("<li><div class=\"row\"><span>").append(escape(str(e, "school"))).append(" · ").append(escape(str(e, "major"))).append(" · ").append(escape(str(e, "degree"))).append("</span>");
                sb.append("<span class=\"muted\">").append(escape(str(e, "start"))).append(" ~ ").append(escape(str(e, "end"))).append("</span></div></li>");
            }
            sb.append("</ul></section>");
        }

        List<Map<String, Object>> exps = listAt(merged, "experiences");
        if (!exps.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>工作经历</h2><ul class=\"resume-list\">");
            int idx = 0;
            for (Map<String, Object> e : exps) {
                List<String> bullets = stringListAt(e, "bullets");
                List<String> overridden = findBulletOverride(adjustments, "experience_emphasis", idx, bullets);
                sb.append("<li><div class=\"row\"><span><b>").append(escape(str(e, "company"))).append("</b> · ").append(escape(str(e, "title"))).append("</span>");
                sb.append("<span class=\"muted\">").append(escape(str(e, "start"))).append(" ~ ").append(escape(str(e, "end"))).append("</span></div>");
                List<String> finalBullets = overridden != null ? overridden : bullets;
                if (!finalBullets.isEmpty()) {
                    sb.append("<ul class=\"bullets\">");
                    for (String b : finalBullets) sb.append("<li>").append(escape(b)).append("</li>");
                    sb.append("</ul>");
                }
                sb.append("</li>");
                idx++;
            }
            sb.append("</ul></section>");
        }

        List<Map<String, Object>> projects = listAt(merged, "projects");
        if (!projects.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>项目经历</h2><ul class=\"resume-list\">");
            int idx = 0;
            for (Map<String, Object> p : projects) {
                List<String> bullets = stringListAt(p, "bullets");
                List<String> overridden = findBulletOverride(adjustments, "project_emphasis", idx, bullets);
                sb.append("<li><div class=\"row\"><span><b>").append(escape(str(p, "name"))).append("</b> · ").append(escape(str(p, "role"))).append("</span></div>");
                List<String> finalBullets = overridden != null ? overridden : bullets;
                if (!finalBullets.isEmpty()) {
                    sb.append("<ul class=\"bullets\">");
                    for (String b : finalBullets) sb.append("<li>").append(escape(b)).append("</li>");
                    sb.append("</ul>");
                }
                sb.append("</li>");
                idx++;
            }
            sb.append("</ul></section>");
        }

        List<String> skills = stringListAt(merged, "skills");
        if (!skills.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>技能</h2><div class=\"tag-list\">");
            for (String s : skills) sb.append("<span class=\"tag\">").append(escape(s)).append("</span>");
            sb.append("</div></section>");
        }

        List<String> certs = stringListAt(merged, "certificates");
        List<String> langs = stringListAt(merged, "languages");
        if (!certs.isEmpty() || !langs.isEmpty()) {
            sb.append("<section class=\"resume-section\"><h2>证书 / 语言</h2><div class=\"tag-list\">");
            for (String c : certs) sb.append("<span class=\"tag\">").append(escape(c)).append("</span>");
            for (String l : langs) sb.append("<span class=\"tag\">").append(escape(l)).append("</span>");
            sb.append("</div></section>");
        }

        Map<String, Object> ext = subMap(merged, "extension");
        if (ext != null && !ext.isEmpty()) {
            renderExtension(sb, "竞赛", "competitions", ext);
            renderExtension(sb, "获奖", "awards", ext);
            renderExtension(sb, "开源", "open_source", ext);
            renderExtension(sb, "副业", "side_projects", ext);
            renderExtension(sb, "论文", "papers", ext);
            renderExtension(sb, "公开演讲", "public_speaking", ext);
            renderExtensionList(sb, "其他亮点", "other_facts", ext);
        }

        sb.append("<footer class=\"resume-footer\">© 简历生成于 ").append(java.time.LocalDateTime.now().toString()).append(" · 由 get_jobs 模板引擎生成</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, Map<String, Object> m, String key, String sep) {
        Object v = m == null ? null : m.get(key);
        if (v != null && !v.toString().isBlank()) sb.append(escape(v.toString())).append(sep);
    }

    private boolean notEmpty(Object o) {
        if (o == null) return false;
        if (o instanceof String) return !((String) o).isBlank();
        if (o instanceof Collection) return !((Collection<?>) o).isEmpty();
        if (o instanceof Map) return !((Map<?, ?>) o).isEmpty();
        return true;
    }

    private String str(Map<String, Object> m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subMap(Map<String, Object> m, String key) {
        if (m == null) return Map.of();
        Object v = m.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return Map.of();
    }

    private String stringAt(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listAt(Map<String, Object> m, String key) {
        if (m == null) return List.of();
        Object v = m.get(key);
        if (v instanceof List) return (List<Map<String, Object>>) v;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringListAt(Map<String, Object> m, String key) {
        if (m == null) return List.of();
        Object v = m.get(key);
        if (v instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object o : (List<?>) v) if (o != null) list.add(o.toString());
            return list;
        }
        return List.of();
    }

    private String toJsonInline(Object o) {
        try {
            if (o == null) return "";
            return OM.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}