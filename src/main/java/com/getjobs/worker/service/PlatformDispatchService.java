package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.delivery.entity.DeliveryRequestEntity;
import com.getjobs.delivery.service.ConfigOverrideApplier;
import com.getjobs.delivery.service.DeliveryConfigOverrideHolder;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 把 delivery_request 的 platform_configs 覆盖到对应平台 Config 并启动 worker。
 * 解决"用户在投递中心填一次 → 立即驱动爬虫"，而无需再去平台配置页。
 *
 * 实现：先把 overrides 写到 ThreadLocal，再让 worker 读完 config 后 apply 一份到自己的 config。
 * 这样既不污染全局 config bean，也能让真实爬虫按用户填写执行。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlatformDispatchService {
    private final ConfigService configService;
    private final JobPlatformService bossJobService;
    private final JobPlatformService job51JobService;
    private final JobPlatformService liepinJobService;
    private final JobPlatformService zhilianJobService;

    public void executeDeliveryOverride(DeliveryRequestEntity req, Map<String, Object> overrides, String platform) {
        // 把 overrides 写入 ThreadLocal，让 worker 自己 apply
        DeliveryConfigOverrideHolder.set(overrides);
        try {
            // 预读一份 config 用于日志（也会被 ThreadLocal overrides 影响）
            switch (platform) {
                case "boss": {
                    BossConfig base = configService.getBossConfig();
                    ConfigOverrideApplier.applyBoss(base, overrides);
                    log.info("[boss] 覆盖式爬虫执行 requestId={} waitTime={} enableAI={} city={} jobType={}",
                            req.getId(), base.getWaitTime(), base.getEnableAI(), base.getCityCode(), base.getJobType());
                    if (System.getProperty("app.delivery.dry-run") != null) {
                        log.info("[boss] dry-run 模式：已应用覆盖配置，不实际启动 Playwright");
                        return;
                    }
                    log.info("[boss] 调用 worker class={}", bossJobService.getClass().getSimpleName());
                    bossJobService.executeDelivery(msg -> log.info("[boss] {}", msg));
                    break;
                }
                case "job51": {
                    Job51Config base = configService.getJob51Config();
                    ConfigOverrideApplier.applyJob51(base, overrides);
                    log.info("[job51] 覆盖式爬虫执行 requestId={} jobArea={} salary={}",
                            req.getId(), base.getJobArea(), base.getSalary());
                    if (System.getProperty("app.delivery.dry-run") != null) {
                        log.info("[job51] dry-run 模式：已应用覆盖配置，不实际启动 Playwright");
                        return;
                    }
                    log.info("[job51] 调用 worker class={}", job51JobService.getClass().getSimpleName());
                    job51JobService.executeDelivery(msg -> log.info("[job51] {}", msg));
                    break;
                }
                case "liepin": {
                    LiepinConfig base = configService.getLiepinConfig();
                    ConfigOverrideApplier.applyLiepin(base, overrides);
                    log.info("[liepin] 覆盖式爬虫执行 requestId={} city={} salary={}",
                            req.getId(), base.getCityCode(), base.getSalary());
                    if (System.getProperty("app.delivery.dry-run") != null) {
                        log.info("[liepin] dry-run 模式：已应用覆盖配置，不实际启动 Playwright");
                        return;
                    }
                    log.info("[liepin] 调用 worker class={}", liepinJobService.getClass().getSimpleName());
                    liepinJobService.executeDelivery(msg -> log.info("[liepin] {}", msg));
                    break;
                }
                case "zhilian": {
                    ZhilianConfig base = configService.getZhilianConfig();
                    ConfigOverrideApplier.applyZhilian(base, overrides);
                    log.info("[zhilian] 覆盖式爬虫执行 requestId={} city={} salary={}",
                            req.getId(), base.getCityCode(), base.getSalary());
                    if (System.getProperty("app.delivery.dry-run") != null) {
                        log.info("[zhilian] dry-run 模式：已应用覆盖配置，不实际启动 Playwright");
                        return;
                    }
                    log.info("[zhilian] 调用 worker class={}", zhilianJobService.getClass().getSimpleName());
                    zhilianJobService.executeDelivery(msg -> log.info("[zhilian] {}", msg));
                    break;
                }
                default:
                    log.warn("未知平台: {}", platform);
            }
        } finally {
            DeliveryConfigOverrideHolder.clear();
        }
    }
}