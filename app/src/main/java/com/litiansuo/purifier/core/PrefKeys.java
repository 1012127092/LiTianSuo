package com.litiansuo.purifier.core;

/**
 * 模块与目标进程共享的配置约定。
 *
 * <p>配置通过 LSPosed 的远程 SharedPreferences 传递：模块 App 侧用
 * {@code XposedService.getRemotePreferences(group)} 写，目标进程侧用
 * {@code XposedInterface.getRemotePreferences(group)} 读。两侧必须用同一个 group 名。</p>
 *
 * <p>不要在这里放任何需要目标应用类加载器才能反序列化的类型，远程 prefs 只支持
 * 基本类型与 String/Set&lt;String&gt;。</p>
 */
public final class PrefKeys {

    private PrefKeys() {
    }

    /** 远程配置分组名。 */
    public static final String GROUP = "purifier";

    /** 已启用去广告的应用包名集合，Set&lt;String&gt;。 */
    public static final String KEY_ENABLED_PACKAGES = "enabled_packages";

    /** 详细日志开关，boolean，默认 false。开启后每条命中都会打 logcat。 */
    public static final String KEY_VERBOSE_LOG = "verbose_log";

    /**
     * 单个功能的开关前缀，boolean。键名形如
     * {@code feat.<packageName>.<featureId>}，缺省视为开启。
     */
    public static final String PREFIX_FEATURE = "feat.";

    /** 拼出某个应用某项功能的开关键名。 */
    public static String featureKey(String packageName, String featureId) {
        return PREFIX_FEATURE + packageName + "." + featureId;
    }
}
