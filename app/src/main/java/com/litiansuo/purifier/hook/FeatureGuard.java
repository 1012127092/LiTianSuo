package com.litiansuo.purifier.hook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * 功能隔离与状态记录。
 *
 * <p>核心约定：每个 hook 的注册都必须包在 {@link #run(String, Runnable)} 里。任何一项因为
 * 目标应用改版、类名变化、SDK 缺失而抛异常，只让那一项标记为不可用，其余功能照常工作，
 * 绝不让整个模块死掉。</p>
 *
 * <p>注意这里拦的是<b>注册阶段</b>的异常。hook 命中后回调内部的异常由框架的
 * {@code ExceptionMode.PROTECTIVE} 负责兜住（module.prop 里已全局设为 protective），
 * 两者互补：注册失败靠本类，运行期失败靠框架。</p>
 *
 * <p>每个目标进程一个实例，不做静态共享——同一个模块可能同时注入多个应用进程。</p>
 */
public final class FeatureGuard {

    /** 一项功能的最终状态。 */
    public enum State {
        /** 注册成功。 */
        OK,
        /** 注册抛异常，该功能不可用。 */
        FAILED,
        /** 用户在模块里关掉了这一项。 */
        DISABLED
    }

    private final Logger log;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();

    public FeatureGuard(Logger log) {
        this.log = log;
    }

    /**
     * 注册逻辑。
     *
     * <p>刻意允许抛受检异常：定位类和方法时的 {@link ClassNotFoundException} /
     * {@link NoSuchMethodException} 正是「这一项不可用」的常规信号，用 {@link Runnable}
     * 会迫使每个规则自己写 try-catch 再包一层 RuntimeException，既啰嗦又容易把异常吞掉。</p>
     */
    public interface Registration {
        void run() throws Throwable;
    }

    /**
     * 注册一项功能。异常只影响这一项。
     *
     * @param featureId 功能标识，同时用于配置键与日志，保持稳定不要随意改名
     * @param body      注册逻辑
     * @return 是否注册成功
     */
    public boolean run(String featureId, Registration body) {
        try {
            body.run();
            states.put(featureId, State.OK);
            return true;
        } catch (Throwable t) {
            states.put(featureId, State.FAILED);
            failures.put(featureId, describe(t));
            // 单项失败是预期内的（目标应用改版、SDK 未集成等），按 warn 记录，不当成崩溃
            log.warn("feature unavailable: " + featureId + " -> " + describe(t));
            return false;
        }
    }

    /** 记录一项被用户关闭的功能，便于在日志里区分「关了」和「坏了」。 */
    public void markDisabled(String featureId) {
        states.put(featureId, State.DISABLED);
    }

    /**
     * 把本进程的功能状态汇总打一行日志。
     *
     * <p>这是排查现场问题的主要依据：用户只要给一份 logcat，就能看出哪些功能生效了、
     * 哪些因为改版失效了。</p>
     */
    public void logSummary(String packageName) {
        StringBuilder ok = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        StringBuilder disabled = new StringBuilder();
        for (Map.Entry<String, State> e : states.entrySet()) {
            StringBuilder target;
            switch (e.getValue()) {
                case OK:
                    target = ok;
                    break;
                case FAILED:
                    target = failed;
                    break;
                default:
                    target = disabled;
                    break;
            }
            if (target.length() > 0) {
                target.append(", ");
            }
            target.append(e.getKey());
        }
        log.info("[" + packageName + "] features OK(" + count(State.OK) + "): "
                + (ok.length() == 0 ? "(none)" : ok));
        if (failed.length() > 0) {
            log.info("[" + packageName + "] features FAILED(" + count(State.FAILED) + "): " + failed);
            for (Map.Entry<String, String> e : failures.entrySet()) {
                log.info("  " + e.getKey() + ": " + e.getValue());
            }
        }
        if (disabled.length() > 0) {
            log.info("[" + packageName + "] features DISABLED(" + count(State.DISABLED) + "): " + disabled);
        }
    }

    private int count(State s) {
        int n = 0;
        for (State v : states.values()) {
            if (v == s) {
                n++;
            }
        }
        return n;
    }

    public Map<String, State> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    /** 只取异常类型与消息，不带整条堆栈——注册失败通常是「类不存在」，堆栈没有信息量。 */
    private static String describe(Throwable t) {
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        return msg == null ? name : name + ": " + msg;
    }

    /** 极简日志门面，便于把 {@link XposedInterface#log} 与 android.util.Log 统一起来。 */
    public interface Logger {
        void info(String msg);

        void warn(String msg);

        void error(String msg, Throwable t);
    }
}
