package com.litiansuo.purifier.rules;

import com.litiansuo.purifier.hook.FeatureGuard;
import com.litiansuo.purifier.hook.Hooks;
import com.litiansuo.purifier.hook.RuntimeConfig;
import com.litiansuo.purifier.hook.XLog;

import io.github.libxposed.api.XposedInterface;

/**
 * 一个应用的去广告规则集。
 *
 * <p>每个适配的应用实现一个 {@code RuleSet}。规则集内部把功能拆成若干独立项，每项都用
 * {@link FeatureGuard#run} 包起来，任何一项因目标改版失效都不影响其它项。</p>
 */
public interface RuleSet {

    /** 规则安装时可用的全部上下文。 */
    final class Context {
        /** 用于 hook 与日志的框架接口。 */
        public final XposedInterface xposed;
        /** 目标应用真正的类加载器。 */
        public final ClassLoader classLoader;
        public final XLog log;
        public final FeatureGuard guard;
        public final Hooks hooks;
        public final RuntimeConfig config;
        public final String packageName;

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

    /** 安装本应用的全部规则。实现内部不应抛异常到外层。 */
    void install(Context ctx);
}
