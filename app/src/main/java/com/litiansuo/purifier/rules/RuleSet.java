package com.litiansuo.purifier.rules;

import com.litiansuo.purifier.hook.FeatureGuard;
import com.litiansuo.purifier.hook.Hooks;
import com.litiansuo.purifier.hook.RuntimeConfig;
import com.litiansuo.purifier.hook.XLog;

import io.github.libxposed.api.XposedInterface;

/**
 * 一个应用的去广告规则集。
 *
 * <p>规则分两个阶段安装，这是被加固应用逼出来的设计：</p>
 * <ol>
 *   <li>{@link #installEarly} —— 在 {@code onPackageLoaded} 时立即执行。此刻壳（如爱加密）
 *       还没解密业务 dex，<b>应用自身与广告 SDK 的类都还不存在</b>，所以这一阶段只能 hook
 *       由系统提供、始终可用的框架类（{@code Instrumentation}、{@code ViewGroup} 等）。</li>
 *   <li>{@link #installLate} —— 在 {@code Application.onCreate} 之前触发。此时壳已经把真实
 *       dex 加载完毕，广告 SDK 的类可以定位了；而这仍然早于任何 SDK 初始化，掐断依然有效。</li>
 * </ol>
 *
 * <p>每个阶段内部把功能拆成若干独立项，每项都用 {@link Context#feature} 包起来，
 * 任何一项因目标改版失效都不影响其它项。</p>
 */
public interface RuleSet {

    /** 规则安装时可用的全部上下文。 */
    final class Context {
        /** 用于 hook 与日志的框架接口。 */
        public final XposedInterface xposed;
        public final XLog log;
        public final FeatureGuard guard;
        public final Hooks hooks;
        public final RuntimeConfig config;
        public final String packageName;

        /**
         * 目标应用的类加载器。
         *
         * <p>刻意可变：early 阶段拿到的是壳的 classloader，业务类不在其中；进入 late 阶段前
         * 会被替换为 {@code Application.getClassLoader()}，那才是能加载广告 SDK 的那一个。</p>
         */
        private volatile ClassLoader classLoader;

        public Context(XposedInterface xposed, ClassLoader classLoader, XLog log,
                       FeatureGuard guard, Hooks hooks, RuntimeConfig config, String packageName) {
            this.xposed = xposed;
            this.classLoader = classLoader;
            this.log = log;
            this.guard = guard;
            this.hooks = hooks;
            this.config = config;
            this.packageName = packageName;
        }

        public ClassLoader classLoader() {
            return classLoader;
        }

        /** 由入口在进入 late 阶段前调用。 */
        public void updateClassLoader(ClassLoader cl) {
            if (cl != null) {
                this.classLoader = cl;
            }
        }

        /**
         * 按用户开关决定是否安装一项功能，并统一记录状态。
         *
         * <p>规则实现应当只调用这个方法，而不要直接用 {@link FeatureGuard#run}——
         * 这里额外处理了「被用户关闭」的分支，让日志能区分「关了」与「坏了」。</p>
         */
        public void feature(String featureId, FeatureGuard.Registration body) {
            if (!config.isFeatureEnabled(featureId)) {
                guard.markDisabled(featureId);
                return;
            }
            guard.run(featureId, body);
        }
    }

    /**
     * 只依赖框架类的 hook，在 {@code onPackageLoaded} 时立即安装。
     *
     * <p>此阶段<b>不得</b>通过 {@code ctx.classLoader()} 定位应用或广告 SDK 的类。</p>
     */
    void installEarly(Context ctx);

    /** 需要应用真实 dex 已加载的 hook，在 {@code Application.onCreate} 前安装。 */
    void installLate(Context ctx);
}
