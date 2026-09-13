package com.bidarena.bootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境变量的唯一读取点。
 *
 * <p>为什么单独抽一个类：配置读取分散在各处时，"哪些配置是必填的"这个问题没有答案——
 * 只能靠通读全部代码。集中在这里之后，{@link #required} 的每个调用点就是一份必填清单，
 * 与 {@code .env.example} 一一对应，评审可以直接对照。
 *
 * <h2>系统属性优先于环境变量</h2>
 * 便于临时覆盖（{@code -DDB_URL=...}）而不必改动 shell 环境，测试也可以按需注入。
 * 这个优先级是刻意的：越"临时"的配置来源应当越靠前，避免"我明明改了却还是旧值"。
 *
 * <h2>为什么缓存</h2>
 * 环境变量在进程生命周期内不变，重复读取只是浪费；更重要的是，
 * 缓存让 {@link #required} 的失败发生在**第一次读取**时，而不是某次请求中，
 * 使配置缺失表现为启动失败而不是运行时 500。
 */
public final class Env {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Env() {}

    /** 读取配置；不存在或空白返回 null。 */
    public static String read(String key) {
        // computeIfAbsent 不能返回 null，因此用哨兵值把"确实不存在"也缓存起来，
        // 否则每次读缺失的 key 都会重新查一遍 System.getProperty/getenv。
        String cached = CACHE.computeIfAbsent(key, Env::lookup);
        return ABSENT.equals(cached) ? null : cached;
    }

    private static final String ABSENT = "\u0000";

    private static String lookup(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key);
        }
        return (value == null || value.isBlank()) ? ABSENT : value;
    }

    /**
     * 读取必填配置。
     *
     * @throws IllegalStateException 缺失时抛出，且信息里包含文件名与补救方式
     */
    public static String required(String key) {
        String value = read(key);
        if (value == null) {
            throw new IllegalStateException(
                    "缺少必填配置 " + key + "。请从 .env.example 复制出 .env 并加载环境变量后重试（见 README 的快速启动）。");
        }
        return value;
    }

    /** 读取整数配置，缺省时用 {@code fallback}。 */
    public static int intOr(String key, int fallback) {
        String value = read(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 不是合法整数：" + value, e);
        }
    }

    /** 读取长整数配置，缺省时用 {@code fallback}。 */
    public static long longOr(String key, long fallback) {
        String value = read(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + key + " 不是合法整数：" + value, e);
        }
    }

    /** 仅供测试清除缓存，使同一 JVM 内可以模拟不同的配置。 */
    static void resetCacheForTest() {
        CACHE.clear();
    }
}
