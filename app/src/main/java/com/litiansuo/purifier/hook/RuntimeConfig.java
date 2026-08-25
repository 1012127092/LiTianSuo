package com.litiansuo.purifier.hook;

import android.util.Log;

import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 本进程的运行时配置。
 *
 * <p>只在 {@code onPackageReady} 里读一次并缓存：hook 回调是热路径，逐次读远程 prefs
 * 会走 binder，绝不能放进 {@code intercept} 里。</p>
 *
 * <p>配置变更通过 {@code OnSharedPreferenceChangeListener} 增量更新缓存字段，
 * 不需要重启目标应用即可改开关（但已经注册/未注册的 hook 不会因此增减，
 * 这部分仍需重启目标进程，界面上有提示）。</p>
 */
public final class RuntimeConfig {

    private final XLog log;
    private volatile boolean enabled;
    private volatile boolean verbose;
    private volatile Set<String> disabledFeatures = java.util.Collections.emptySet();

    private RuntimeConfig(XLog log) {
        this.log = log;
    }

    /**
     * 读取指定应用的配置。
     *
     * <p>框架不支持远程配置（{@code PROP_CAP_REMOTE} 未置位，例如嵌入式框架）或读取失败时，
     * 返回一份「默认全开」的配置：宁可去广告生效，也不要因为读不到配置而静默失效——
     * 后者会让用户以为模块坏了却看不到原因。</p>
     */
    public static RuntimeConfig load(XposedInterface xposed, XLog log, String packageName) {
        RuntimeConfig cfg = new RuntimeConfig(log);
        cfg.enabled = true;
        cfg.verbose = false;

        if ((xposed.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) == 0) {
            log.warn("framework has no remote-preferences capability; using defaults (all on)");
            return cfg;
        }

        try {
            android.content.SharedPreferences prefs =
                    xposed.getRemotePreferences(com.litiansuo.purifier.core.PrefKeys.GROUP);

            cfg.apply(prefs, packageName);

            // 监听变更：用户在模块里改开关后立即生效，不必重启目标应用
            prefs.registerOnSharedPreferenceChangeListener((sp, key) -> {
                try {
                    cfg.apply(sp, packageName);
                    log.info("config reloaded (key=" + key + ") enabled=" + cfg.enabled);
                } catch (Throwable t) {
                    log.error("config reload failed", t);
                }
            });
        } catch (UnsupportedOperationException e) {
            // 嵌入式框架下 getRemotePreferences 明确抛这个
            log.warn("remote preferences unsupported; using defaults (all on)");
        } catch (Throwable t) {
            log.error("failed to read remote preferences; using defaults (all on)", t);
        }
        return cfg;
    }

    private void apply(android.content.SharedPreferences prefs, String packageName) {
        Set<String> on = prefs.getStringSet(
                com.litiansuo.purifier.core.PrefKeys.KEY_ENABLED_PACKAGES, null);
        // 键不存在或为空集合都视为启用，避免「装了没反应」。
        // 空集合必须与 null 同等对待：用户把唯一一个应用关掉再打开，中间态就是空集合，
        // 若判为禁用会与界面「未配置即启用」的显示不一致。禁用由「集合非空且不含本包」表达。
        this.enabled = (on == null) || on.isEmpty() || on.contains(packageName);
        this.verbose = prefs.getBoolean(com.litiansuo.purifier.core.PrefKeys.KEY_VERBOSE_LOG, false);
        log.setVerbose(this.verbose);

        java.util.HashSet<String> off = new java.util.HashSet<>();
        String prefix = com.litiansuo.purifier.core.PrefKeys.PREFIX_FEATURE + packageName + ".";
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(prefix) && Boolean.FALSE.equals(e.getValue())) {
                off.add(k.substring(prefix.length()));
            }
        }
        this.disabledFeatures = off;
    }

    /** 该应用是否启用去广告。 */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /** 某项功能是否被用户单独关掉；缺省为开启。 */
    public boolean isFeatureEnabled(String featureId) {
        return !disabledFeatures.contains(featureId);
    }

    void debugDump() {
        Log.d(XLog.TAG, "enabled=" + enabled + " verbose=" + verbose
                + " disabled=" + disabledFeatures);
    }
}
