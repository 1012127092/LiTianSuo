package com.litiansuo.purifier.hook;

import android.app.Application;
import android.os.Build;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.litiansuo.purifier.core.AdaptedApps;
import com.litiansuo.purifier.rules.AppRules;
import com.litiansuo.purifier.rules.RuleSet;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口。类名登记在 {@code META-INF/xposed/java_init.list}。
 *
 * <p>几条必须遵守的框架约定：</p>
 * <ul>
 *   <li>必须有 public 无参构造器——框架用 {@code getDeclaredConstructor()} 反射实例化，
 *       之后自己调 {@code attachFramework()}。模块不得调用它；</li>
 *   <li>不要在构造器里做任何初始化：那时 base 还没 attach，调用任何 API 会抛
 *       {@code IllegalStateException}；</li>
 *   <li>模块被注入某进程后，该进程里加载的<b>每一个</b>包都会触发回调，包括
 *       {@code scope.list} 之外的包（sharedUserId、createPackageContext 等），
 *       所以必须自己按包名和进程名过滤。</li>
 * </ul>
 */
public final class PurifierModule extends XposedModule {

    /** 每个进程只初始化一次。静态是有意的：同进程内回调可能触发多次。 */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public PurifierModule() {
        // 故意留空，初始化一律放到 onPackageReady
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();

        // 只处理已适配的应用。其它包（含同进程内被顺带加载的包）直接放过。
        if (!AdaptedApps.isAdapted(pkg)) {
            return;
        }

        // 只在主进程干活：广告 UI 都在主进程，子进程（:push / :remote 等）hook 了纯属浪费，
        // 还会让同一份日志重复出现干扰排查。
        String process = currentProcessName();
        if (!pkg.equals(process)) {
            return;
        }

        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        XLog log = new XLog(this, false);
        try {
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

            rules.install(new RuleSet.Context(
                    this, param.getClassLoader(), log, guard, hooks, cfg, pkg));

            guard.logSummary(pkg);
            log.info("[" + pkg + "] ready, " + hooks.count() + " hook(s) installed"
                    + " (framework=" + getFrameworkName() + " " + getFrameworkVersion()
                    + ", api=" + getApiVersion() + ")");
        } catch (Throwable t) {
            // 允许重试：某些失败（如类还没加载）在下次进程启动时可能不再出现
            INITIALIZED.set(false);
            log.error("[" + pkg + "] initialization failed", t);
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
