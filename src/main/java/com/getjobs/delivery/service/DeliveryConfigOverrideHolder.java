package com.getjobs.delivery.service;

import java.util.Map;

/**
 * 用 ThreadLocal 在 PlatformDispatchService 和 worker 之间传递覆盖 overrides，
 * 避免污染全局 config bean。worker 在读取 config 后会先 apply 这份 overrides。
 */
public final class DeliveryConfigOverrideHolder {
    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();
    private DeliveryConfigOverrideHolder() {}

    public static void set(Map<String, Object> o) { CURRENT.set(o); }
    public static Map<String, Object> get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}