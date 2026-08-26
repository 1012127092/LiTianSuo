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

    /** 每个 key 的命中计数，用于 {@link #hitThrottled}。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            hitCounts = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 节流版命中日志：只在第 1、10、100、1000… 次时写一行，并带上累计次数。
     *
     * <p>为什么需要它：{@link #hit} 每次都写文件，而<b>有些拦截点会被目标应用反复调用</b>。
     * 实测 QQ 音乐里广点通的 {@code initWith} 十分钟被拦 1950 次，日志涨到 308 KB——
     * 信息量却只有一句「拦到了」。文件 I/O 本身成了比广告更大的开销。</p>
     *
     * <p>用「十进制量级」而不是固定间隔，是为了让日志长度对次数取对数：拦 3 次和拦
     * 3 万次都只有几行，但两者的量级差异一眼可辨。</p>
     *
     * <p>仍然保证<b>第一次必写</b>：规则是否真的生效必须能从日志直接确认，
     * 静默成功比可见失败更危险。</p>
     *
     * @return 该 key 的累计命中次数（含本次），便于调用方在首次命中时做额外定性
     */
    public long hitThrottled(String key, String msg) {
        long n = hitCounts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        boolean milestone = n == 1;
        if (!milestone) {
            // n 是否为 10 的整数次幂
            for (long p = 10; p <= n; p *= 10) {
                if (p == n) {
                    milestone = true;
                    break;
                }
            }
        }
        if (milestone) {
            hit(msg + (n == 1 ? "" : " (x" + n + ")"));
        }
        return n;
    }

    /**
     * 取当前调用栈的可读摘要，用于定性「谁在反复调用这个方法」。
     *
     * <p>调用方<b>必须</b>自己保证只取一次（例如只在首次命中时）：取栈要遍历整个
     * 调用链并分配字符串，放在被高频调用的拦截点上会明显拖慢目标应用。</p>
     *
     * @param maxFrames 最多保留的帧数，避免一行日志过长
     */
    public static String callerSummary(int maxFrames) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            // 跳过取栈自身与本模块的帧，只留目标应用与广告 SDK 的调用链
            if (cn.startsWith("java.lang.Thread") || cn.startsWith("com.litiansuo.purifier.")) {
                continue;
            }
            if (kept > 0) {
                sb.append(" <- ");
            }
            sb.append(cn).append('#').append(e.getMethodName());
            if (++kept >= maxFrames) {
                break;
            }
        }
        return sb.toString();
    }
}
