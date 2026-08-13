package com.getjobs.resume.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.profile.service.ProfileService;
import com.getjobs.resume.entity.ResumeEntity;
import com.getjobs.resume.entity.ResumeVersionEntity;
import com.getjobs.resume.mapper.ResumeMapper;
import com.getjobs.resume.mapper.ResumeVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 简历中心服务：上传、解析、版本管理、文本提取。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResumeService {
    private final ResumeMapper resumeMapper;
    private final ResumeVersionMapper versionMapper;
    private final AiService aiService;
    private final ProfileService profileService;

    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "doc");
    private static final ObjectMapper OM = new ObjectMapper();

    private static final String STORAGE_ROOT;
    static {
        // 关键修复：MultipartFile.transferTo() 在 Spring Boot/Tomcat 下会把相对路径解析到
        // Tomcat 的工作目录 (例如 AppData/Local/Temp/tomcat.xxxx/work/Tomcat/localhost/ROOT/...)，
        // 必须使用绝对路径。这里优先用 app.resume.storage-root 配置项；未配置时取 user.home。
        String configured = System.getProperty("app.resume.storage-root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv().getOrDefault("RESUME_STORAGE_ROOT", "");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("user.home") + "/get_jobs-data/resumes";
        }
        STORAGE_ROOT = configured;
        // 启动时打印一次，方便排查
        System.out.println("[ResumeService] STORAGE_ROOT = " + new File(STORAGE_ROOT).getAbsolutePath());
    }
    private static final long DEFAULT_USER_ID = 1L;

    public record UploadResult(ResumeEntity resume, boolean reused, String message) {}

    /** 上传并入库 + 异步解析 */
    @Transactional
    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String original = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String ext = lowerExt(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("仅支持 .pdf / .docx / .doc 格式");
        }
        long size = file.getSize();
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("文件大小超过 10MB 上限");
        }
        String sha = sha256(file);

        // 去重
        ResumeEntity existing = resumeMapper.findBySha256(sha);
        if (existing != null) {
            return new UploadResult(existing, true, "文件已存在，已复用 record id=" + existing.getId());
        }

        // 落盘
        String relDir = STORAGE_ROOT + "/" + DEFAULT_USER_ID;
        new File(relDir).mkdirs();
        ResumeEntity r = new ResumeEntity();
        r.setName(stripExt(original));
        r.setOriginalFilename(original);
        r.setFileSha256(sha);
        r.setFileSizeBytes(size);
        r.setParseStatus("PENDING");
        r.setIsManuallyEdited(0);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        resumeMapper.insert(r);
        String relPath = relDir + "/" + r.getId() + "." + ext;
        try {
            file.transferTo(new File(relPath));
        } catch (IOException e) {
            log.error("保存简历文件失败", e);
            throw new RuntimeException("保存文件失败: " + e.getMessage());
        }
        r.setOriginalPath(relPath);
        resumeMapper.updateById(r);

        // 异步解析
        asyncParse(r.getId());

        return new UploadResult(r, false, "上传成功");
    }

    /** 异步解析（避免阻塞 HTTP） */
    private void asyncParse(Long resumeId) {
        Thread t = new Thread(() -> {
            try {
                parse(resumeId);
            } catch (Exception e) {
                log.error("异步解析失败 resumeId={}", resumeId, e);
            }
        }, "resume-parse-" + resumeId);
        t.setDaemon(true);
        t.start();
    }

    /** 解析简历：抽取文本 → AI 结构化 → 写入 parsed_json → 生成 MASTER 版本 */
    @Transactional
    public ResumeEntity parse(Long resumeId) {
        ResumeEntity r = resumeMapper.selectById(resumeId);
        if (r == null) throw new IllegalArgumentException("resume not found: " + resumeId);
        r.setParseStatus("PARSING");
        r.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(r);

        String text;
        try {
            text = extractText(new File(r.getOriginalPath()));
        } catch (Exception e) {
            r.setParseStatus("PARSE_FAILED");
            r.setUpdatedAt(LocalDateTime.now());
            resumeMapper.updateById(r);
            throw new RuntimeException("文本提取失败: " + e.getMessage());
        }

        try {
            String aiResp = aiService.sendRequest(buildParsePrompt(text));
            String json = extractJson(aiResp);
            r.setParsedJson(json);
            r.setParseStatus("PARSED");
            r.setUpdatedAt(LocalDateTime.now());
            resumeMapper.updateById(r);

            // 生成 MASTER 版本
            ResumeVersionEntity v = new ResumeVersionEntity();
            v.setResumeId(r.getId());
            v.setType("MASTER");
            v.setCreatedAt(LocalDateTime.now());
            v.setUpdatedAt(LocalDateTime.now());
            versionMapper.insert(v);

            // 同步回填 Profile
            try { profileService.backfillFromResume(r); } catch (Exception e) { log.warn("回填 Profile 失败: {}", e.getMessage()); }
        } catch (Exception e) {
            log.error("AI 解析失败 resumeId={}", resumeId, e);
            r.setParseStatus("PARSE_FAILED");
            r.setUpdatedAt(LocalDateTime.now());
            resumeMapper.updateById(r);
            throw e;
        }
        return r;
    }

    /** 重新触发解析 */
    public ResumeEntity retryParse(Long resumeId) { return parse(resumeId); }

    /** 手工修订 parsed_json（含必填字段校验 FR-RES-003） */
    @Transactional
    public ResumeEntity updateParsedJson(Long resumeId, String parsedJson) {
        ResumeEntity r = resumeMapper.selectById(resumeId);
        if (r == null) throw new IllegalArgumentException("resume not found: " + resumeId);
        // FR-RES-003 必填校验：basics.name/phone/email 不能为空
        profileService.validateResumeRequiredFields(parsedJson);
        r.setParsedJson(parsedJson);
        r.setIsManuallyEdited(1);
        r.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(r);
        return r;
    }

    /** 列表（含各派生版本数量） */
    public List<Map<String, Object>> list() {
        List<ResumeEntity> all = resumeMapper.findAllOrderByCreatedDesc();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResumeEntity r : all) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("name", r.getName());
            item.put("original_filename", r.getOriginalFilename());
            item.put("parse_status", r.getParseStatus());
            item.put("is_manually_edited", r.getIsManuallyEdited());
            item.put("created_at", r.getCreatedAt());
            item.put("updated_at", r.getUpdatedAt());
            item.put("template_count", versionMapper.countByResumeIdAndType(r.getId(), "TEMPLATE"));
            item.put("job_specific_count", versionMapper.countByResumeIdAndType(r.getId(), "JOB_SPECIFIC"));
            out.add(item);
        }
        return out;
    }

    /** 删除主版本（不允许被引用） */
    @Transactional
    public void delete(Long resumeId) {
        ResumeEntity r = resumeMapper.selectById(resumeId);
        if (r == null) return;
        int templateCount = versionMapper.countByResumeIdAndType(resumeId, "TEMPLATE");
        int jobSpecificCount = versionMapper.countByResumeIdAndType(resumeId, "JOB_SPECIFIC");
        if (templateCount > 0 || jobSpecificCount > 0) {
            throw new IllegalStateException("该简历已有派生版本，无法删除");
        }
        // 检查 delivery_target 引用（此处简化：如有 resume_version 引用就不允许删除）
        // 实际中应检查 delivery_target.resume_version_id -> resume_version.resume_id
        try {
            File f = new File(r.getOriginalPath());
            if (f.exists()) f.delete();
        } catch (Exception ignore) {}
        resumeMapper.deleteById(resumeId);
    }

    /** 生成模板版本（TEMPLATE） */
    @Transactional
    public ResumeVersionEntity createTemplateVersion(Long resumeId, String templateId) {
        ResumeEntity r = resumeMapper.selectById(resumeId);
        if (r == null) throw new IllegalArgumentException("resume not found");
        if (!"PARSED".equals(r.getParseStatus())) {
            throw new IllegalStateException("简历尚未解析成功");
        }
        // 简化：直接记录渲染路径；实际渲染由 TemplateService 完成
        ResumeVersionEntity v = new ResumeVersionEntity();
        v.setResumeId(resumeId);
        v.setType("TEMPLATE");
        v.setTemplateId(templateId);
        v.setCreatedAt(LocalDateTime.now());
        v.setUpdatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        return v;
    }

    /** 获取简历 */
    public ResumeEntity get(Long id) { return resumeMapper.selectById(id); }

    /** 获取版本 */
    public ResumeVersionEntity getVersion(Long id) { return versionMapper.findById(id); }

    public List<ResumeVersionEntity> listVersions(Long resumeId) { return versionMapper.findAllByResumeId(resumeId); }

    /** 更新版本的 HTML/PDF 路径与调整对象 */
    @Transactional
    public void updateVersionArtifacts(Long versionId, String htmlPath, String pdfPath, String adjustmentsJson) {
        ResumeVersionEntity v = versionMapper.selectById(versionId);
        if (v == null) return;
        if (htmlPath != null) v.setRenderedHtmlPath(htmlPath);
        if (pdfPath != null) v.setRenderedPdfPath(pdfPath);
        if (adjustmentsJson != null) v.setAdjustmentsJson(adjustmentsJson);
        v.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(v);
    }

    // ---------- 私有辅助 ----------

    private String lowerExt(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String stripExt(String filename) {
        if (filename == null) return "resume";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sha256(MultipartFile f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = f.getBytes();
            md.update(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算 SHA-256 失败", e);
        }
    }

    /** 文本提取 */
    private String extractText(File f) throws IOException {
        String name = f.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            try (PDDocument doc = PDDocument.load(f)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        if (name.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(new FileInputStream(f));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                return extractor.getText();
            }
        }
        if (name.endsWith(".doc")) {
            // 老 doc 二进制格式无法直接解析，尝试以文本回退
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        return "";
    }

    private String buildParsePrompt(String text) {
        return "你是一名资深 HR 简历解析助手。请将以下简历原文解析为严格的 JSON（不要包含 Markdown 代码块标记），\n" +
                "结构必须包含：\n" +
                "{\n" +
                "  \"basics\": { \"name\":\"\", \"phone\":\"\", \"email\":\"\", \"city\":\"\", \"expected_city\":[], \"expected_salary_k\":[] },\n" +
                "  \"summary\":\"\",\n" +
                "  \"education\":[ { \"school\":\"\", \"major\":\"\", \"degree\":\"\", \"start\":\"\", \"end\":\"\" } ],\n" +
                "  \"experiences\":[ { \"company\":\"\", \"title\":\"\", \"start\":\"\", \"end\":\"\", \"bullets\":[] } ],\n" +
                "  \"projects\":[ { \"name\":\"\", \"role\":\"\", \"bullets\":[] } ],\n" +
                "  \"skills\":[],\n" +
                "  \"certificates\":[],\n" +
                "  \"languages\":[]\n" +
                "}\n" +
                "未知字段留空字符串或空数组。只输出 JSON，不要任何解释：\n" +
                "===\n" + text + "\n===";
    }

    private String extractJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();

        // 1) 剥离 <think>...</think> 思维链（MiniMax-M3 / DeepSeek 等推理模型都会输出）
        t = t.replaceAll("(?is)<\\s*think\\s*>.*?<\\s*/\\s*think\\s*>", "");
        t = t.trim();

        // 2) 剥离 ```json ... ``` 或 ``` ... ``` 代码块围栏
        if (t.startsWith("```")) {
            int firstNL = t.indexOf('\n');
            if (firstNL > 0) t = t.substring(firstNL + 1);
            int lastFence = t.lastIndexOf("```");
            if (lastFence > 0) t = t.substring(0, lastFence);
            t = t.trim();
        }

        // 3) 在剩余文本中查找第一个完整的顶层 JSON 对象 {...}
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
                if (depth == 0) {
                    return t.substring(firstBrace, i + 1);
                }
            }
        }
        // 兜底：未配对则返回原文（外层会捕获 JSON 异常并写 PARSE_FAILED）
        return t.substring(firstBrace);
    }

    @SuppressWarnings("unused")
    private Path ensureDir(String path) throws IOException {
        Path p = Paths.get(path);
        Files.createDirectories(p);
        return p;
    }
}