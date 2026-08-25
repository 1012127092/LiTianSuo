package com.litiansuo.purifier.hook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志：绕过 logcat 的独立证据通道。
 *
 * <p>为什么需要它：加固壳（爱加密）在 native 初始化时会静默日志输出——这是加固产品常见的
 * 反分析手段。实测现象完全吻合：{@code onPackageLoaded} 期间（壳的 native 尚未运行）所有
 * 日志正常，壳初始化之后<b>钩子回调、后台线程心跳、普通 {@code Log.i} 全部消失</b>，
 * 而进程始终存活。也就是说钩子很可能一直在工作，只是看不到输出。</p>
 *
 * <p>日志写到应用的<b>外部</b>私有目录（{@code /sdcard/Android/data/<pkg>/files/}）：
 * 应用写它不需要任何权限，而 adb shell 能直接读——内部 {@code filesDir} 在未 root 的设备上
 * 拉不出来，起不到证据作用。</p>
 *
 * <p>失败一律静默：这只是诊断通道，任何异常都不该影响目标应用。</p>
 */
public final class FileLog {

    private static final String FILE_NAME = "litiansuo-diag.log";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile File target;

    private FileLog() {
    }

    /**
     * 按包名直接绑定，不需要 Context。
     *
     * <p>{@code /sdcard/Android/data/<pkg>/files/} 是该应用的外部私有目录，应用自身写它
     * 无需任何权限。这个重载的意义在于<b>时机</b>：{@code onPackageLoaded} 阶段
     * {@code currentApplication()} 还是 null，拿不到 Context，而这一阶段恰恰是唯一还能
     * 正常输出日志的窗口，必须在此刻就把文件通道打开。</p>
     */
    public static void attach(String packageName) {
        if (target != null || packageName == null || packageName.isEmpty()) {
            return;
        }
        try {
            File dir = new File("/sdcard/Android/data/" + packageName + "/files");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, FILE_NAME);
            target = f;
            write("---- attached by package: " + f.getAbsolutePath() + " ----");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 绑定输出文件。可以重复调用，只有第一次成功的生效。
     *
     * @param ctx 通常传 Application；为 null 时本类退化为空实现
     */
    public static void attach(android.content.Context ctx) {
        if (target != null || ctx == null) {
            return;
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                // 没有外部存储时退回内部目录：读不出来，但至少不会崩
                dir = ctx.getFilesDir();
            }
            if (dir != null) {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File f = new File(dir, FILE_NAME);
                target = f;
                write("---- attached: " + f.getAbsolutePath() + " ----");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 是否已经绑定到文件。 */
    public static boolean isAttached() {
        return target != null;
    }

    /** 追加一行。未绑定时什么也不做。 */
    public static void write(String line) {
        File f = target;
        if (f == null) {
            return;
        }
        // 每次开关文件而不是长期持有 writer：诊断日志量很小，
        // 而长期持有的流会在进程被杀时丢掉最后几行——那几行往往正是关键。
        try (FileOutputStream out = new FileOutputStream(f, true);
             OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write(TS.format(new Date()));
            w.write(" [");
            w.write(Thread.currentThread().getName());
            w.write("] ");
            w.write(line == null ? "null" : line);
            w.write('\n');
        } catch (Throwable ignored) {
        }
    }
}
