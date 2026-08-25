package com.litiansuo.purifier.hook;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 日志实现：同时写 logcat 与 LSPosed 模块日志。
 *
 * <p>写两份是有意的：logcat 便于开发期用 adb 抓，LSPosed 模块日志便于用户在管理器里
 * 直接截图反馈。</p>
 *
 * <p>{@code verbose} 默认关闭。命中类日志量随界面滑动线性增长，常开会拖慢目标应用，
 * 只在用户主动打开开关时输出。</p>
 */
public final class XLog implements FeatureGuard.Logger {

    public static final String TAG = "LiTianSuo";

    private final XposedInterface xposed;
    private volatile boolean verbose;

    public XLog(XposedInterface xposed, boolean verbose) {
        this.xposed = xposed;
        this.verbose = verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @Override
    public void info(String msg) {
        Log.i(TAG, msg);
        if (xposed != null) {
            xposed.log(Log.INFO, TAG, msg);
        }
    }

    @Override
    public void warn(String msg) {
        Log.w(TAG, msg);
        if (xposed != null) {
            xposed.log(Log.WARN, TAG, msg);
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        if (t == null) {
            Log.e(TAG, msg);
            if (xposed != null) {
                xposed.log(Log.ERROR, TAG, msg);
            }
        } else {
            Log.e(TAG, msg, t);
            if (xposed != null) {
                xposed.log(Log.ERROR, TAG, msg, t);
            }
        }
    }

    /** 命中类日志，仅在 verbose 打开时输出。调用点不需要自己判断开关。 */
    public void hit(String msg) {
        if (verbose) {
            info(msg);
        }
    }
}
