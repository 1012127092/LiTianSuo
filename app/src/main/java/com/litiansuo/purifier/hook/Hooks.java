package com.litiansuo.purifier.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * hook 注册的薄封装。
 *
 * <p>统一三件事，避免每个规则各写一遍：</p>
 * <ul>
 *   <li>统一设 {@code ExceptionMode.PROTECTIVE}：hook 回调内部抛异常时框架只记日志、
 *       调用照原样继续，不会把目标应用带崩；</li>
 *   <li>统一设 {@code id}：便于日志定位。<b>注意 id 不去重</b>——同 id 重复注册是
 *       叠加而非替换（实测钩子数 8→42→76→110 逐轮递增），所以必须由调用方保证
 *       每个 hook 只注册一次，不能指望 id 兜底；</li>
 *   <li>统一记录 {@link io.github.libxposed.api.XposedInterface.HookHandle}，
 *       {@link #unhookAll()} 可一次性摘掉全部 hook。</li>
 * </ul>
 *
 * <p>注意本类<b>不吞注册异常</b>：注册失败要让 {@link FeatureGuard} 看见并把那一项标记为
 * 不可用，所以异常必须往外抛。</p>
 */
public final class Hooks {

    private final XposedInterface xposed;
    private final XLog log;
    private final java.util.List<XposedInterface.HookHandle> handles = new java.util.ArrayList<>();

    public Hooks(XposedInterface xposed, XLog log) {
        this.xposed = xposed;
        this.log = log;
    }

    /**
     * 让目标方法直接返回一个固定值，不执行原实现。
     *
     * <p>等价于旧 API 的 {@code XC_MethodReplacement}：不调用 {@code chain.proceed()}
     * 即跳过原方法与下游所有 hook。</p>
     */
    public XposedInterface.HookHandle replaceResult(String id, Method m, Object value) {
        return intercept(id, m, chain -> {
            log.hit(id + " -> return " + value);
            return value;
        });
    }

    /**
     * 让目标方法变成空实现（void 方法用）。
     *
     * <p>广告 SDK 的 {@code init} / {@code loadAd} / {@code show} 基本都是 void，
     * 直接掐掉是最省事也最彻底的做法——广告压根不会被创建。</p>
     */
    public XposedInterface.HookHandle blockVoid(String id, Method m) {
        return intercept(id, m, chain -> {
            log.hit(id + " -> blocked");
            return null;
        });
    }

    /** 注册一个自定义拦截器。 */
    public XposedInterface.HookHandle intercept(String id, Method m, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle h = xposed.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept(hooker);
        handles.add(h);
        return h;
    }

    /** 注册构造函数拦截器。 */
    public XposedInterface.HookHandle interceptCtor(String id, Constructor<?> c,
                                                    XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle h = xposed.hook(c)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept(hooker);
        handles.add(h);
        return h;
    }

    /**
     * 把某个类里所有同名方法（全部重载）一次性掐掉。
     *
     * <p>广告 SDK 常见 {@code loadAd()} / {@code loadAd(int)} / {@code loadAd(Config)}
     * 多个重载，只堵一个会漏。找不到任何同名方法时抛异常，由 FeatureGuard 降级——
     * 静默成功比失败更危险，那意味着功能其实没生效。</p>
     *
     * @return 实际注册成功的数量
     */
    public int blockAllNamed(String idPrefix, Class<?> cls, String name) throws NoSuchMethodException {
        java.util.List<Method> ms = Reflect.methodsNamed(cls, name);
        if (ms.isEmpty()) {
            throw new NoSuchMethodException(cls.getName() + "#" + name + " (no overload found)");
        }
        int n = 0;
        for (Method m : ms) {
            // void 方法直接空实现；有返回值的方法交给调用方自己处理，这里不猜
            if (m.getReturnType() == void.class) {
                blockVoid(idPrefix + "/" + m.getParameterCount(), m);
                n++;
            }
        }
        if (n == 0) {
            throw new NoSuchMethodException(cls.getName() + "#" + name + " has no void overload");
        }
        return n;
    }

    public int count() {
        return handles.size();
    }

    /** 摘掉本实例注册的全部 hook。{@code unhook()} 是幂等的，重复调用安全。 */
    public void unhookAll() {
        for (XposedInterface.HookHandle h : handles) {
            try {
                h.unhook();
            } catch (Throwable ignored) {
                // 摘钩失败没有补救手段，也不影响其它钩子
            }
        }
        handles.clear();
    }
}
