package com.litiansuo.purifier.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 目标进程内<b>可写</b>的开关存储。
 *
 * <p>为什么不用 {@link RuntimeConfig}：那份配置来自 LSPosed 的远程 SharedPreferences，
 * 在目标进程里<b>只读</b>——{@code getRemotePreferences} 返回的对象没有可用的 editor。
 * 而「在 QQ 音乐界面里勾选一个开关」这件事必须在目标进程完成写入，所以只能落在
 * 目标应用自己的私有 prefs 文件里（{@code /data/data/<pkg>/shared_prefs/}）。</p>
 *
 * <p>两份配置的分工是清楚的：远程 prefs 决定「模块 App 里配的东西」，本地 prefs
 * 决定「用户在目标应用内面板里临时勾的东西」。读取时本地优先——用户刚在眼前点的
 * 开关理应立刻算话。</p>
 *
 * <h3>全部值缓存在内存里</h3>
 *
 * <p>拦截器是热路径，绝不能每次去读 prefs（即使是本地文件也要过一层
 * {@code SharedPreferencesImpl} 的锁）。所以构造时全量读进 {@link #cache}，
 * 之后靠 {@code OnSharedPreferenceChangeListener} 增量更新。读取路径上只有一次
 * {@code HashMap.get}，量级和读字段相当。</p>
 */
public final class LocalPrefs {

    /** prefs 文件名。带模块前缀避免与目标应用自己的键冲突。 */
    private static final String FILE = "litiansuo_local";

    private final SharedPreferences prefs;
    private final XLog log;

    /** 键 -> 值。整体替换而不是原地改，读取侧就不需要加锁。 */
    private volatile Map<String, Boolean> cache = Collections.emptyMap();

    /** 监听器必须持强引用：SharedPreferences 只弱引用它，否则注册完就被 GC。 */
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;

    private LocalPrefs(SharedPreferences prefs, XLog log) {
        this.prefs = prefs;
        this.log = log;
        this.listener = (sp, key) -> {
            try {
                reload();
            } catch (Throwable t) {
                log.error("local prefs reload failed", t);
            }
        };
    }

    /**
     * 打开目标应用私有目录下的开关文件。
     *
     * <p>失败返回 {@code null} 而不是抛：这套开关是「增强」而非「必需」，
     * 读不到时规则应当按默认值继续工作，不该因为存不下用户偏好就整项失效。</p>
     */
    public static LocalPrefs open(Context appContext, XLog log) {
        if (appContext == null) {
            return null;
        }
        try {
            SharedPreferences sp = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            LocalPrefs lp = new LocalPrefs(sp, log);
            lp.reload();
            sp.registerOnSharedPreferenceChangeListener(lp.listener);
            return lp;
        } catch (Throwable t) {
            log.error("failed to open local prefs", t);
            return null;
        }
    }

    private void reload() {
        Map<String, Boolean> next = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() instanceof Boolean) {
                next.put(e.getKey(), (Boolean) e.getValue());
            }
        }
        this.cache = next;
    }

    /** 读开关；键不存在时返回 {@code def}。 */
    public boolean get(String key, boolean def) {
        Boolean v = cache.get(key);
        return v == null ? def : v;
    }

    /** 键是否被显式设置过。用于区分「用户关掉了」与「用户还没碰过」。 */
    public boolean isSet(String key) {
        return cache.containsKey(key);
    }

    /**
     * 读 long 值（如上次签到时间戳）；键不存在或类型不符时返回 {@code def}。
     *
     * <p>不走 {@link #cache}（那只缓存 Boolean），直接读底层 prefs。long 值不在
     * 拦截热路径上——只在启动后签到判定时读一次，无需缓存。</p>
     */
    public long getLong(String key, long def) {
        try {
            return prefs.getLong(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 写 long 值。用 {@code apply} 异步落盘，不阻塞调用线程。 */
    public void setLong(String key, long value) {
        try {
            prefs.edit().putLong(key, value).apply();
        } catch (Throwable t) {
            log.error("failed to write local long " + key, t);
        }
    }

    /** 读 String 值；键不存在或类型不符时返回 {@code def}。 */
    public String getString(String key, String def) {
        try {
            return prefs.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /** 写 String 值。用 {@code apply} 异步落盘。 */
    public void setString(String key, String value) {
        try {
            prefs.edit().putString(key, value).apply();
        } catch (Throwable t) {
            log.error("failed to write local string " + key, t);
        }
    }

    /**
     * 写开关。
     *
     * <p>用 {@code apply} 而不是 {@code commit}：这是在 UI 线程的点击回调里调用的，
     * {@code commit} 会同步写盘阻塞主线程。缓存由监听器回调更新，不在这里直接改——
     * 让「写入」与「生效」走同一条路径，避免两者不一致。</p>
     */
    public void set(String key, boolean value) {
        try {
            prefs.edit().putBoolean(key, value).apply();
        } catch (Throwable t) {
            log.error("failed to write local pref " + key, t);
        }
    }
}
