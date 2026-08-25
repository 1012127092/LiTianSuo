package com.litiansuo.purifier.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已适配应用注册表。
 *
 * <p>本模块只对这里登记的应用生效——不做通用/启发式去广告。新增一个适配应用需要同步改三处：</p>
 * <ol>
 *   <li>这里加一条 {@link Entry}；</li>
 *   <li>{@code app/src/main/resources/META-INF/xposed/scope.list} 加一行包名
 *       （LSPosed 只会把模块注入 scope.list 声明的应用）；</li>
 *   <li>{@code AndroidManifest.xml} 的 {@code <queries>} 加一行，
 *       否则模块 App 查不到该应用是否安装。</li>
 * </ol>
 */
public final class AdaptedApps {

    private AdaptedApps() {
    }

    /** 123 云盘。 */
    public static final String PKG_PAN123 = "com.mfcloudcalculate.networkdisk";

    /** QQ 音乐。 */
    public static final String PKG_QQMUSIC = "com.tencent.qqmusic";

    /** 一个已适配应用的元信息。 */
    public static final class Entry {
        /** 目标应用包名。 */
        public final String packageName;
        /** 展示名。 */
        public final String label;
        /** 适配时验证过的版本，仅用于界面提示，不做版本门禁。 */
        public final String verifiedVersion;

        Entry(String packageName, String label, String verifiedVersion) {
            this.packageName = packageName;
            this.label = label;
            this.verifiedVersion = verifiedVersion;
        }
    }

    private static final Map<String, Entry> ENTRIES;

    static {
        LinkedHashMap<String, Entry> m = new LinkedHashMap<>();
        m.put(PKG_PAN123, new Entry(PKG_PAN123, "123 云盘", "3.2.17"));
        m.put(PKG_QQMUSIC, new Entry(PKG_QQMUSIC, "QQ 音乐", "20.7.5.8"));
        ENTRIES = Collections.unmodifiableMap(m);
    }

    /** 按登记顺序返回全部已适配应用。 */
    public static Map<String, Entry> all() {
        return ENTRIES;
    }

    /** 该包名是否为已适配应用。 */
    public static boolean isAdapted(String packageName) {
        return packageName != null && ENTRIES.containsKey(packageName);
    }

    public static Entry get(String packageName) {
        return packageName == null ? null : ENTRIES.get(packageName);
    }
}
