package com.litiansuo.purifier.ui;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.litiansuo.purifier.core.PrefKeys;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 侧与 LSPosed 的连接。
 *
 * <p>为什么必须走 {@link XposedService}：新版 API 下模块 App 自己<b>不再被 hook</b>，
 * 旧的「在模块里 hook 自己判断是否激活」那招失效；而配置也不能再用
 * {@code XSharedPreferences}（API 102 禁止调用 legacy 包）。激活状态和配置读写都得靠它。</p>
 *
 * <p>连接是异步的：框架通过 ContentProvider 递交 binder，到达时间不确定，所以界面必须能
 * 处理「尚未连接」的中间态，不能假设 {@link #service()} 一定非空。</p>
 */
public final class ServiceBridge {

    /** 界面回调，一律在主线程执行。 */
    public interface Callback {
        /** 已连接。此后 {@link #service()} 可用。 */
        void onConnected(XposedService service);

        /** 连接断开或从未建立（模块未在 LSPosed 启用时也走这里）。 */
        void onUnavailable();
    }

    private static final ServiceBridge INSTANCE = new ServiceBridge();

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile XposedService service;
    private volatile Callback callback;
    private boolean listenerRegistered;

    private ServiceBridge() {
    }

    public static ServiceBridge get() {
        return INSTANCE;
    }

    /**
     * 注册监听。{@code XposedServiceHelper.registerListener} 全进程只应调用一次，
     * 这里用标志位保证幂等——Activity 重建时会重复调用本方法。
     */
    public synchronized void attach(Callback cb) {
        this.callback = cb;

        if (service != null) {
            XposedService s = service;
            main.post(() -> {
                Callback c = callback;
                if (c != null) {
                    c.onConnected(s);
                }
            });
            return;
        }

        if (listenerRegistered) {
            return;
        }
        listenerRegistered = true;

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService s) {
                service = s;
                main.post(() -> {
                    Callback c = callback;
                    if (c != null) {
                        c.onConnected(s);
                    }
                });
            }

            @Override
            public void onServiceDied(XposedService s) {
                service = null;
                main.post(() -> {
                    Callback c = callback;
                    if (c != null) {
                        c.onUnavailable();
                    }
                });
            }
        });
    }

    public void detach() {
        this.callback = null;
    }

    /** 未连接时返回 null。 */
    public XposedService service() {
        return service;
    }

    public boolean isConnected() {
        return service != null;
    }

    // ---------------------------------------------------------------- 配置读写

    /** 未连接时返回 null。 */
    private SharedPreferences prefs() {
        XposedService s = service;
        if (s == null) {
            return null;
        }
        try {
            return s.getRemotePreferences(PrefKeys.GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读已启用的应用集合；未连接时返回空集。 */
    public Set<String> enabledPackages() {
        SharedPreferences p = prefs();
        if (p == null) {
            return Collections.emptySet();
        }
        Set<String> s = p.getStringSet(PrefKeys.KEY_ENABLED_PACKAGES, null);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    /**
     * 设置某应用是否启用。
     *
     * @return 是否写入成功（未连接时为 false）
     */
    public boolean setPackageEnabled(String packageName, boolean enabled) {
        SharedPreferences p = prefs();
        if (p == null) {
            return false;
        }
        Set<String> cur = new HashSet<>(enabledPackages());
        if (enabled) {
            cur.add(packageName);
        } else {
            cur.remove(packageName);
        }
        // 用 commit 而不是 apply：要立刻知道是否真的写进了框架数据库，
        // 界面需要据此提示用户，静默失败会让人以为开关生效了。
        return p.edit().putStringSet(PrefKeys.KEY_ENABLED_PACKAGES, cur).commit();
    }

    public boolean isVerboseLog() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(PrefKeys.KEY_VERBOSE_LOG, false);
    }

    public boolean setVerboseLog(boolean on) {
        SharedPreferences p = prefs();
        return p != null && p.edit().putBoolean(PrefKeys.KEY_VERBOSE_LOG, on).commit();
    }

    /** LSPosed 里本模块当前的作用域；未连接时返回空列表。 */
    public List<String> scope() {
        XposedService s = service;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            List<String> l = s.getScope();
            return l == null ? Collections.emptyList() : l;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** 框架描述，用于界面上显示「已激活」。 */
    public String frameworkSummary() {
        XposedService s = service;
        if (s == null) {
            return null;
        }
        try {
            return s.getFrameworkName() + " " + s.getFrameworkVersion()
                    + " (API " + s.getApiVersion() + ")";
        } catch (Throwable t) {
            return null;
        }
    }
}
