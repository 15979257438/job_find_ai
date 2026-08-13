package com.getjobs.resume.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.Margin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * PDF 渲染服务（FR-RES-008 / FR-TPL-003）
 * 使用 Playwright headless Chromium 将 HTML 转为 A4 PDF。
 * 字体与样式随 HTML 自带，无外部 CDN 依赖。
 */
@Service
@Slf4j
public class PdfRenderService {

    /** 同步阻塞地把 HTML 文件路径转 PDF 文件路径。失败时抛 RuntimeException。 */
    public void renderPdfFromHtmlFile(String htmlPath, String pdfPath) {
        Path in = Paths.get(htmlPath);
        Path out = Paths.get(pdfPath);
        if (!Files.exists(in)) {
            throw new IllegalArgumentException("HTML 源文件不存在: " + htmlPath);
        }
        try {
            Files.createDirectories(out.getParent());
        } catch (Exception ignore) {}

        long t0 = System.currentTimeMillis();
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                String url = "file:///" + in.toAbsolutePath().toString().replace("\\", "/");
                page.navigate(url);
                try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000)); }
                catch (Exception ignore) {}
                Margin m = new Margin().setTop("12mm").setBottom("12mm").setLeft("12mm").setRight("12mm");
                page.pdf(new Page.PdfOptions()
                        .setPath(out.toAbsolutePath())
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(m)
                        .setPreferCSSPageSize(false));
                log.info("PDF 渲染完成：{} → {} 耗时{}ms", in.getFileName(), out.getFileName(), System.currentTimeMillis() - t0);
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            log.error("PDF 渲染失败", e);
            throw new RuntimeException("PDF 渲染失败：" + e.getMessage(), e);
        }
    }

    /** 阻塞限时版本（默认 10s） */
    public boolean renderPdfFromHtmlFileWithTimeout(String htmlPath, String pdfPath, long timeoutMs) {
        java.util.concurrent.FutureTask<Boolean> task = new java.util.concurrent.FutureTask<>(() -> {
            renderPdfFromHtmlFile(htmlPath, pdfPath);
            return true;
        });
        java.util.concurrent.ExecutorService es = java.util.concurrent.Executors.newSingleThreadExecutor();
        es.submit(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("PDF 渲染超时：{}", e.getMessage());
            return false;
        } finally {
            es.shutdownNow();
        }
    }
}
