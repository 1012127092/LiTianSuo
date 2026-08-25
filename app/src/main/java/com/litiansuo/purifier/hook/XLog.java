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
        FileLog.write("I " + msg);
        if (xposed != null) {
            xposed.log(Log.INFO, TAG, msg);
        }
    }

    @Override
    public void warn(String msg) {
        Log.w(TAG, msg);
        FileLog.write("W " + msg);
        if (xposed != null) {
            xposed.log(Log.WARN, TAG, msg);
        }
    }

    @Override
    public void error(String msg, Throwable t) {
        FileLog.write("E " + msg + (t == null ? "" : " :: " + t));
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

    /**
     * 命中类日志。
     *
     * <p>logcat 与 LSPosed 日志受 {@code verbose} 控制（量大且会拖慢目标应用），
     * 但<b>文件通道始终写</b>：命中记录是验证规则是否真的起作用的唯一直接证据，
     * 而命中次数本身是有界的——只有真的遇到广告才会触发，不像测绘那样随滑动线性增长。</p>
     *
     * <p>之前把整个 hit 都关在 verbose 后面，导致「规则装上了但看不到任何命中」，
     * 白白把日志缺失误判成规则失效。</p>
     */
    public void hit(String msg) {
        FileLog.write("H " + msg);
        if (verbose) {
            Log.i(TAG, msg);
            if (xposed != null) {
                xposed.log(Log.INFO, TAG, msg);
            }
        }
    }
}
