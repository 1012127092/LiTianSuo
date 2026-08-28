package com.litiansuo.purifier.hook;

import android.app.Application;
import android.os.Build;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.litiansuo.purifier.core.AdaptedApps;
import com.litiansuo.purifier.rules.AppRules;
import com.litiansuo.purifier.rules.RuleSet;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口。类名登记在 {@code META-INF/xposed/java_init.list}。
 *
 * <p>几条必须遵守的框架约定：</p>
 * <ul>
 *   <li>必须有 public 无参构造器——框架用反射实例化后自己调 {@code attachFramework()}，
 *       模块不得调用它；</li>
 *   <li>不要在构造器里做任何初始化：那时 base 还没 attach，调用任何 API 会抛
 *       {@code IllegalStateException}；</li>
 *   <li>模块被注入某进程后，该进程里加载的<b>每一个</b>包都会触发回调，
 *       所以必须自己按包名和进程名过滤。</li>
 * </ul>
 *
 * <h2>为什么用 onPackageLoaded 而不是 onPackageReady</h2>
 *
 * <p>{@code onPackageReady} 需要框架取得应用的 {@code AppComponentFactory} 与最终
 * classloader。加固应用（如爱加密，manifest 里 {@code appComponentFactory="s.h.e.l.l.A"}）
 * 会在这个阶段自行替换 classloader，导致该回调<b>根本不触发</b>——实测 123 云盘只回调
 * {@code onModuleLoaded} 与 {@code onPackageLoaded}，没有 {@code onPackageReady}。</p>
 *
 * <p>因此改在 {@code onPackageLoaded} 落脚，并把规则拆成两阶段：此刻壳尚未解密业务 dex，
 * 只能 hook 框架类；等 {@code Instrumentation.callApplicationOnCreate} 被调用时，真实 dex
 * 已加载完毕，再装广告 SDK 相关的 hook。该时机仍早于 {@code Application.onCreate}，
 * 也就早于任何广告 SDK 初始化。</p>
 */
public final class PurifierModule extends XposedModule {

    /** 每个进程只初始化一次。静态是有意的：同进程内回调可能触发多次。 */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    /** late 阶段也只跑一次：callApplicationOnCreate 在多 Application 场景下可能被调多次。 */
    private static final AtomicBoolean LATE_DONE = new AtomicBoolean(false);

    public PurifierModule() {
        // 故意留空，初始化一律放到回调里
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // 无条件打一行：这是判断「模块到底有没有被注入」的唯一可靠信号。
        // 若这行都不出现，说明目标进程根本没加载本模块（作用域未授予），
        // 而不是后面的过滤把它挡掉了——两者的修法完全不同。
        try {
            android.util.Log.i(XLog.TAG, "onModuleLoaded process=" + param.getProcessName()
                    + " systemServer=" + param.isSystemServer());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();

        if (!AdaptedApps.isAdapted(pkg)) {
            return;
        }
        // 只在主进程干活：广告 UI 都在主进程，子进程（:push 等）hook 了纯属浪费。
        String process = currentProcessName();
        if (!pkg.equals(process)) {
            // UC 非必要子进程：阻止 :channel/:MediaPlayerService 的 Service 初始化
            // （三星 Freecess 绕过 Java startService 直接调 AMS，只能在子进程侧拦）
            if ("com.UCMobile".equals(pkg) && (process.endsWith(":channel")
                    || process.endsWith(":MediaPlayerService"))) {
                blockSubProcessService(process);
            }
            return;
        }
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        XLog log = new XLog(this, false);
        try {
            // 立刻打开文件日志通道。必须在这一刻做：这是唯一还能正常输出的窗口，
            // 而壳的 native 初始化之后 logcat 就哑了（见 FileLog 类注释）。
            FileLog.attach(pkg);
            log.info("onPackageLoaded pkg=" + pkg + " first=" + param.isFirstPackage());

            RuntimeConfig cfg = RuntimeConfig.load(this, log, pkg);
            if (!cfg.isEnabled()) {
                log.info("[" + pkg + "] disabled by user; no hooks installed");
                return;
            }

            RuleSet rules = AppRules.forPackage(pkg);
            if (rules == null) {
                // AdaptedApps 里登记了但没有对应规则，属于代码不一致，必须让人看见
                log.warn("[" + pkg + "] adapted but no rule set found; nothing to do");
                return;
            }

            FeatureGuard guard = new FeatureGuard(log);
            Hooks hooks = new Hooks(this, log);

            // early 阶段的 classloader 只是壳的，业务类不在其中；late 阶段会替换掉
            ClassLoader early = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    early = param.getDefaultClassLoader();
                }
            } catch (Throwable t) {
                log.warn("getDefaultClassLoader failed: " + t);
            }

            RuleSet.Context ctx = new RuleSet.Context(
                    this, early, log, guard, hooks, cfg, pkg);

            rules.installEarly(ctx);
            log.info("[" + pkg + "] early stage done, " + hooks.count() + " hook(s)");

            Application live = currentApplication();
            log.info("at onPackageLoaded: currentApplication=" + (live == null ? "null"
                    : live.getClass().getName())
                    + " defaultCL=" + (early == null ? "null" : early.getClass().getName()));

            // 不再用「hook 某个更晚的方法」当触发器。已实测：在 onPackageLoaded 里注册的
            // 钩子，回调返回后<b>全部失效</b>——自检（同一时刻）成功、loadClass 探针在 +6ms
            // 成功，之后 8 个钩子无一响应，连只用平台 Log 的裸探针都不响。最合理的解释是
            // 爱加密壳的 native 初始化紧随其后运行，把被 hook 方法的入口整体还原了一遍。
            //
            // 所以改用 Looper 定时重装：我们的代码本就跑在目标进程里，直接往主线程队列
            // post 任务即可，既绕开触发点选择问题，也落在壳的反 hook 动作之后。
            scheduleReinstall(ctx, rules, log, pkg);
        } catch (Throwable t) {
            // 允许重试：某些失败在下次进程启动时可能不再出现
            INITIALIZED.set(false);
            log.error("[" + pkg + "] initialization failed", t);
        }
    }

    /**
     * 在壳完成初始化之后重新安装全部规则。
     *
     * <p>用<b>自己的后台线程 + sleep</b>，不用 Handler/Looper。上一版往
     * {@code Looper.getMainLooper()} post 了三轮任务，日志确认 post 成功，但<b>没有任何一轮
     * 被执行</b>，而同一时刻进程活着且正在渲染 MainActivity——说明拿到的 mainLooper 不是最终
     * 真正在跑的那个循环（爱加密壳很可能重建了主线程环境）。装钩子并不需要主线程，
     * 所以直接去掉这个依赖。</p>
     *
     * <p>分多轮而不是只试一次：壳解密耗时受设备与冷热启动影响，固定单次延迟必然要么太早
     * （类还没加载）要么太晚（广告已经弹出）。每轮都完整重装一遍：早的负责赶在广告之前，
     * 晚的负责兜住解密慢的情况。{@code Hooks} 用同一 id 注册是原子替换而非叠加，
     * 重复装不会累积钩子。</p>
     *
     * <p>线程设为 daemon：它只是定时器，绝不能因为它而让目标进程无法退出。</p>
     */
    /**
     * 轮询等待壳解密完成，一就绪立刻安装 late 阶段规则。
     *
     * <p>为什么轮询而不是 hook 某个触发点：{@code onPackageReady} 对本应用永不触发
     * （爱加密的 {@code appComponentFactory} 替换了 classloader，框架拿不到最终 CL），
     * 而 {@code Application.attach} / {@code newApplication} / {@code onCreate} 等触发点
     * 实测全都不打响。轮询不依赖任何触发点，也不需要在热路径上挂钩子。</p>
     *
     * <p>为什么必须快：实测开屏容器 {@code frame_ad_splash_container} 在
     * <b>+0.7s</b> 就已加入布局，而应用真实类在 +0.25s 就能加载到。之前用 1.5s/4s/8s
     * 三轮固定延迟，第一轮直到 +7.7s 才装上 SDK 拦截，广告早就跑完了。所以这里改成
     * 每 100ms 探一次，就绪即装。</p>
     *
     * <p>只装一次：{@link #LATE_DONE} 保证幂等。上一版每轮都重装，钩子数从 42 涨到 110，
     * 说明同 id 注册是<b>叠加而非替换</b>——同一个方法挂了三份相同的拦截器，纯属浪费。</p>
     */
    private void scheduleReinstall(RuleSet.Context ctx, RuleSet rules, XLog log, String pkg) {
        Thread t = new Thread(() -> {
            raw("late-stage poller started");

            // 上限 15s：超过这个时间还没解密完，说明本次启动异常，继续等也无意义
            final int maxAttempts = 150;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    raw("late-stage poller interrupted");
                    return;
                }

                Application app = currentApplication();
                if (app == null) {
                    continue;
                }
                ClassLoader cl = app.getClassLoader();
                if (cl == null) {
                    continue;
                }

                if (!LATE_DONE.compareAndSet(false, true)) {
                    return;
                }
                raw("application ready after " + (attempt * 100) + "ms: "
                        + app.getClass().getName() + " cl=" + cl.getClass().getName());
                try {
                    ctx.updateClassLoader(cl);
                    rules.installLate(ctx);
                    ctx.guard.logSummary(pkg);
                    raw("late stage done, " + ctx.hooks.count() + " hook(s) total");
                } catch (Throwable e) {
                    // 不用 log.error：它会再走一次 xposed.log，可能二次抛异常把线程带走
                    raw("late stage FAILED: " + e);
                }
                return;
            }
            raw("late-stage poller gave up after " + (maxAttempts * 100) + "ms");
        }, "lts-late");
        t.setDaemon(true);
        t.start();
        raw("late-stage poller scheduled");
    }

    /**
     * 双通道诊断输出：logcat + 文件。
     *
     * <p>刻意不走 {@link XLog}：后者会调 {@code xposed.log(...)}，而这段代码要验证的正是
     * 「壳初始化之后 xposed 对象是否还能用」——用它记日志就成了循环论证。</p>
     */
    private static void raw(String msg) {
        try {
            android.util.Log.i(XLog.TAG, "raw: " + msg);
        } catch (Throwable ignored) {
        }
        FileLog.write("RAW " + msg);
    }

    /**
     * 等到应用真实 dex 加载完毕后再装剩余 hook。
     *
     * <p>挂三个互为备份的触发点，只要其一打响就够（{@link #LATE_DONE} 保证只执行一次）：</p>
     * <ol>
     *   <li>{@code Instrumentation.newApplication(Class, Context)} —— <b>首选</b>。它内部要做
     *       {@code newInstance()} 反射构造，方法体不短，不会被 ART 内联；返回值就是
     *       Application 实例，能直接取到壳解密后的真实 classloader；且早于
     *       {@code Application.onCreate}，仍在所有广告 SDK 初始化之前；</li>
     *   <li>{@code Instrumentation.callApplicationOnCreate(Application)}；</li>
     *   <li>{@code Application.onCreate()}。</li>
     * </ol>
     *
     * <p>为什么需要多个：实测 2、3 装上了却从未回调，原因是 <b>ART 内联</b>。尤其
     * {@code Application.onCreate()} 在 AOSP 里<b>方法体为空</b>，空方法是内联的头号目标，
     * 壳里 {@code super.onCreate()} 这行调用在编译期就被消掉了，钩子永远等不到。对调用方
     * {@code handleBindApplication} 做 {@code deoptimize} 虽返回 true，但已内联进机器码的
     * 调用点无法恢复，所以必须换一个「天然不会被内联」的点。</p>
     */
    private void scheduleLate(RuleSet.Context ctx, RuleSet rules, XLog log, String pkg) {
        // 解优化调用方，配合下面偏短的备用触发点
        try {
            Method caller = Reflect.methodByArity(
                    Class.forName("android.app.ActivityThread"), "handleBindApplication", 1);
            boolean ok = deoptimize(caller);
            log.info("deoptimize ActivityThread#handleBindApplication -> " + ok);
        } catch (Throwable t) {
            log.warn("deoptimize caller failed: " + t);
        }

        // 首选：Application.attach(Context)。
        // 这个点取自同设备上验证可用的参考模块（coolapkpurifier 用的正是
        // Application.class.getDeclaredMethod("attach", Context.class)）。
        // 它由 newApplication 内部调用，方法体要给一批字段赋值，不会被内联，且早于 onCreate。
        installLateTrigger(ctx, rules, log, pkg, "late-trigger/Application.attach", () ->
                Reflect.method(Application.class, "attach", android.content.Context.class));

        // 备用：newApplication 全部重载。必须挂全部——LoadedApk.makeApplication 走静态三参
        // newApplication(ClassLoader, String, Context)，而非实例二参 newApplication(Class, Context)。
        int n = 0;
        for (Method m : Reflect.methodsNamed(
                android.app.Instrumentation.class, "newApplication")) {
            final Method target = m;
            installLateTrigger(ctx, rules, log, pkg,
                    "late-trigger/newApplication/" + m.getParameterCount(), () -> target);
            n++;
        }
        log.info("late trigger installed on " + n + " newApplication overload(s)");

        installLateTrigger(ctx, rules, log, pkg, "late-trigger/callApplicationOnCreate", () ->
                Reflect.method(android.app.Instrumentation.class,
                        "callApplicationOnCreate", Application.class));
    }

    /** 供 {@link #installLateTrigger} 定位触发方法，允许抛受检异常。 */
    private interface MethodSupplier {
        Method get() throws Throwable;
    }

    private void installLateTrigger(RuleSet.Context ctx, RuleSet rules, XLog log, String pkg,
                                    String id, MethodSupplier target) {
        try {
            Method m = target.get();
            XposedInterface.HookHandle[] self = new XposedInterface.HookHandle[1];
            self[0] = hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(id)
                    .intercept(chain -> {
                        // 先让原方法执行完：newApplication 的 Application 实例是它的
                        // 返回值，只有 proceed 之后才拿得到。对另外两个触发点，
                        // 先执行也不影响——它们此时 dex 已加载。
                        Object result = chain.proceed();

                        if (LATE_DONE.compareAndSet(false, true)) {
                            try {
                                log.info("late stage triggered by " + id);
                                ctx.updateClassLoader(resolveAppClassLoader(chain, result));
                                rules.installLate(ctx);
                                ctx.guard.logSummary(pkg);
                                log.info("[" + pkg + "] ready, " + ctx.hooks.count()
                                        + " hook(s) installed (framework=" + getFrameworkName()
                                        + " " + getFrameworkVersion()
                                        + ", api=" + getApiVersion() + ")");
                            } catch (Throwable t) {
                                log.error("[" + pkg + "] late stage failed", t);
                            }
                        }
                        // 触发器用完即摘：留着会让后续每次调用都多走一层拦截
                        try {
                            if (self[0] != null) {
                                self[0].unhook();
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
        } catch (Throwable t) {
            log.warn("failed to install " + id + ": " + t);
        }
    }

    /**
     * 从拦截上下文里取出应用真实的 classloader。
     *
     * <p>三个触发点里 Application 实例的位置各不相同，逐一尝试：
     * {@code newApplication} 是<b>返回值</b>，{@code callApplicationOnCreate} 是参数 0，
     * {@code Application.onCreate} 是 this。</p>
     */
    private static ClassLoader resolveAppClassLoader(XposedInterface.Chain chain, Object result) {
        Application app = null;

        if (result instanceof Application) {
            app = (Application) result;
        }
        if (app == null) {
            try {
                // 参数为空时 getArg(0) 会抛越界，所以包 try
                Object first = chain.getArg(0);
                if (first instanceof Application) {
                    app = (Application) first;
                }
            } catch (Throwable ignored) {
            }
        }
        if (app == null) {
            try {
                Object self = chain.getThisObject();
                if (self instanceof Application) {
                    app = (Application) self;
                }
            } catch (Throwable ignored) {
            }
        }
        return app == null ? null : app.getClassLoader();
    }

    /**
     * 取当前进程已创建的 Application 实例，尚未创建时返回 null。
     *
     * <p>用 {@code ActivityThread.currentApplication()}（静态、无参）。这是判断
     * 「hook 时机相对 Application 创建的先后」最直接的办法，比猜测框架回调顺序可靠。</p>
     */
    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Application ? (Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在 UC 非必要子进程（:channel/:MediaPlayerService）中 hook
     * {@code ActivityThread.handleCreateService} 让它空实现——Service 不创建，
     * 进程启动后无事可做很快被系统回收。省内存 + 省长连接心跳发热。
     */
    private void blockSubProcessService(String process) {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Class<?> csdCls = Class.forName("android.app.ActivityThread$CreateServiceData");
            java.lang.reflect.Method hcs = atCls.getDeclaredMethod("handleCreateService", csdCls);
            new Hooks(this, new XLog(this, false)).blockVoid("subproc-block/hcs", hcs);
            android.util.Log.i(XLog.TAG, "subproc-block: hooked handleCreateService in " + process);
        } catch (Throwable t) {
            android.util.Log.e(XLog.TAG, "subproc-block failed in " + process, t);
        }
    }

    /**
     * 取当前真实进程名。
     *
     * <p>不能用包名代替：多进程应用里子进程名形如 {@code pkg:push}。
     * API 28+ 有现成方法，更低版本读 {@code /proc/self/cmdline}。</p>
     */
    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String n = Application.getProcessName();
            return n == null ? "" : n;
        }
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            return new String(buf, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
