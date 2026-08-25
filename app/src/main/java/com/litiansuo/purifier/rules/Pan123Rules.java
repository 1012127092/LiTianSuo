package com.litiansuo.purifier.rules;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import com.litiansuo.purifier.hook.Reflect;

import io.github.libxposed.api.XposedInterface;

/**
 * 123 云盘（com.mfcloudcalculate.networkdisk）去广告规则。
 *
 * <p>背景：该应用用爱加密整体加固，业务 dex 加密存放在 {@code assets/ijiami.dat} 里，运行时
 * 才解密加载，静态反编译只能拿到壳（{@code s.h.e.l.l.*}）。所以这里<b>不依赖任何业务类名</b>，
 * 全部从广告 SDK 与 Android 框架这两个稳定面下手：</p>
 * <ul>
 *   <li>广告 SDK 类名未混淆且跨版本稳定 —— 见 {@link AdSdk}；</li>
 *   <li>Android 框架方法（{@code Instrumentation}、{@code ViewGroup}）签名由系统保证。</li>
 * </ul>
 *
 * <p>加固对本模块不构成障碍：hook 发生在运行时，此时 dex 已被壳解密加载。</p>
 */
final class Pan123Rules implements RuleSet {

    /** 广告 SDK 探测：确认这 9 家里到底哪几家真的被加载。 */
    static final String FEAT_PROBE = "probe";
    /** 拦截广告 Activity 启动（开屏、激励视频、落地页都是独立 Activity）。 */
    static final String FEAT_AD_ACTIVITY = "ad-activity";
    /** 广告 View 加入布局时隐藏（信息流内嵌广告、banner）。 */
    static final String FEAT_AD_VIEW = "ad-view";
    /** 广告 SDK 初始化拦截（从源头掐断，广告请求不会发出）。 */
    static final String FEAT_SDK_INIT = "sdk-init";

    /**
     * 各家 SDK 的初始化入口：类名 -> 方法名。
     *
     * <p>掐断 init 后 SDK 拿不到配置，后续 loadAd 会直接失败，比等广告渲染出来再隐藏更彻底，
     * 也顺带省掉广告的流量与耗电。逐条独立注册，某家 SDK 没集成或改了签名只影响那一条。</p>
     */
    private static final Map<String, String[]> SDK_INIT_ENTRIES = new LinkedHashMap<>();

    static {
        // 穿山甲：init 建实例、start 真正拉配置
        SDK_INIT_ENTRIES.put("com.bytedance.sdk.openadsdk.TTAdSdk", new String[]{"init", "start"});
        // 优量汇（广点通）
        SDK_INIT_ENTRIES.put("com.qq.e.comm.managers.GDTAdSdk",
                new String[]{"init", "initWithoutStart", "start"});
        // 快手联盟
        SDK_INIT_ENTRIES.put("com.kwad.sdk.api.KsAdSDK", new String[]{"init", "start"});
        // Sigmob
        SDK_INIT_ENTRIES.put("com.sigmob.windad.WindAds", new String[]{"startWithOptions", "init"});
        // 倍孜
        SDK_INIT_ENTRIES.put("com.beizi.fusion.BeiZis", new String[]{"init", "asyncInit"});
        // 章鱼
        SDK_INIT_ENTRIES.put("com.octopus.ad.Octopus", new String[]{"init"});
    }

    @Override
    public void install(Context ctx) {
        installProbe(ctx);
        installAdActivityBlock(ctx);
        installAdViewHide(ctx);
        installSdkInitBlock(ctx);
    }

    // ------------------------------------------------------------------ 探测

    /**
     * 只记录、不改行为：把实际加载成功的广告 SDK 打进日志。
     *
     * <p>这一项是后续迭代的依据——manifest 里声明了 9 家，但未必都会在运行时初始化。
     * 有了这份名单才知道该重点堵谁，而不是盲目加规则。</p>
     */
    private void installProbe(Context ctx) {
        ctx.feature(FEAT_PROBE, () -> {
            StringBuilder present = new StringBuilder();
            for (String cls : SDK_INIT_ENTRIES.keySet()) {
                if (Reflect.hasClass(ctx.classLoader, cls)) {
                    if (present.length() > 0) {
                        present.append(", ");
                    }
                    present.append(cls.substring(cls.lastIndexOf('.') + 1));
                }
            }
            ctx.log.info("ad sdk present: " + (present.length() == 0 ? "(none yet)" : present));
        });
    }

    // ------------------------------------------------- 广告 Activity 拦截

    /**
     * 在 {@code Instrumentation.execStartActivity} 处拦下广告 Activity。
     *
     * <p>选这个点是因为它是所有 Activity 启动的收敛处：不管从 Activity、Service 还是
     * Application context 发起，最终都要过这里，比逐个 hook {@code startActivity} 重载省事。</p>
     *
     * <p>不调用 {@code chain.proceed()} 直接返回 null，等价于「这次启动没发生」——
     * 开屏广告、激励视频、落地页因此都不会出现。</p>
     */
    private void installAdActivityBlock(Context ctx) {
        ctx.feature(FEAT_AD_ACTIVITY, () -> {
            Class<?> inst = Reflect.findClass(ctx.classLoader, "android.app.Instrumentation");
            if (inst == null) {
                throw new IllegalStateException("android.app.Instrumentation not found");
            }
            int n = 0;
            for (Method m : Reflect.methodsNamed(inst, "execStartActivity")) {
                final int intentIndex = indexOfIntent(m);
                if (intentIndex < 0) {
                    continue;
                }
                ctx.hooks.intercept(FEAT_AD_ACTIVITY + "/" + m.getParameterCount(), m, chain -> {
                    Object arg = chain.getArg(intentIndex);
                    if (arg instanceof Intent && isAdIntent((Intent) arg)) {
                        ctx.log.hit("blocked ad activity: " + describe((Intent) arg));
                        // 返回 null：调用方把它当作「没有返回结果」，不会崩
                        return null;
                    }
                    return chain.proceed();
                });
                n++;
            }
            if (n == 0) {
                throw new NoSuchMethodException(
                        "Instrumentation#execStartActivity has no overload with an Intent parameter");
            }
            ctx.log.info("ad activity block installed on " + n + " overload(s)");
        });
    }

    private static int indexOfIntent(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (Intent.class.equals(ps[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Intent 的目标组件是否属于广告 SDK。 */
    private static boolean isAdIntent(Intent intent) {
        if (intent.getComponent() != null) {
            return AdSdk.isAdClass(intent.getComponent().getClassName());
        }
        // 没有显式组件的隐式 Intent 一律放过：广告 SDK 的页面都是显式启动的，
        // 在这里瞎猜会误杀应用自身的分享、拨号等正常跳转。
        return false;
    }

    private static String describe(Intent intent) {
        return intent.getComponent() == null ? String.valueOf(intent.getAction())
                : intent.getComponent().getClassName();
    }

    // ---------------------------------------------------- 广告 View 隐藏

    /**
     * 广告 View 被加入布局时隐藏它。
     *
     * <p>hook 点选 {@code ViewGroup#addView(View, int, LayoutParams)}：其余 addView 重载最终
     * 都会汇聚到这个方法，只堵这一个既完整又便宜。</p>
     *
     * <p>热路径纪律：这个方法在滑动列表时调用极频繁，所以回调里只做一次字符串前缀比较，
     * 命中结果按类对象缓存。不读配置、不查资源、不走 binder。</p>
     *
     * <p>处理方式是设为 {@code GONE} 而不是拒绝添加：GONE 不占布局空间，视觉效果等同于消失，
     * 同时 SDK 后续对该 View 的引用仍然有效，不会因为拿不到 parent 而抛异常。</p>
     */
    private void installAdViewHide(Context ctx) {
        ctx.feature(FEAT_AD_VIEW, () -> {
            Method addView = Reflect.method(ViewGroup.class, "addView",
                    View.class, int.class, ViewGroup.LayoutParams.class);

            // 类 -> 是否广告 View。用类对象做键，避免每次都做字符串前缀匹配。
            final Map<Class<?>, Boolean> cache = new java.util.concurrent.ConcurrentHashMap<>();

            ctx.hooks.intercept(FEAT_AD_VIEW, addView, chain -> {
                Object child = chain.getArg(0);
                if (child instanceof View) {
                    Class<?> c = child.getClass();
                    Boolean isAd = cache.get(c);
                    if (isAd == null) {
                        isAd = AdSdk.isAdClass(c.getName());
                        cache.put(c, isAd);
                    }
                    if (isAd) {
                        ((View) child).setVisibility(View.GONE);
                        ctx.log.hit("hid ad view: " + c.getName());
                    }
                }
                return chain.proceed();
            });
        });
    }

    // ------------------------------------------------ 广告 SDK 初始化拦截

    /**
     * 掐掉各家 SDK 的初始化入口。
     *
     * <p>每个 SDK 单独一项功能（{@code sdk-init/<名字>}），这样某家改了签名只失效那一家，
     * 其余照常。只堵 void 重载：有返回值的 init 变体贸然返回假值可能让 SDK 进入未定义状态。</p>
     */
    private void installSdkInitBlock(Context ctx) {
        for (Map.Entry<String, String[]> e : SDK_INIT_ENTRIES.entrySet()) {
            final String clsName = e.getKey();
            final String[] methods = e.getValue();
            final String shortName = clsName.substring(clsName.lastIndexOf('.') + 1);
            final String featureId = FEAT_SDK_INIT + "/" + shortName;

            ctx.feature(featureId, () -> {
                Class<?> cls = Reflect.findClass(ctx.classLoader, clsName);
                if (cls == null) {
                    // 这家 SDK 没被集成/没加载，属正常情况
                    throw new ClassNotFoundException(clsName + " (sdk not loaded)");
                }
                int blocked = 0;
                StringBuilder missing = new StringBuilder();
                for (String name : methods) {
                    try {
                        blocked += ctx.hooks.blockAllNamed(featureId + "#" + name, cls, name);
                    } catch (NoSuchMethodException nsme) {
                        if (missing.length() > 0) {
                            missing.append(",");
                        }
                        missing.append(name);
                    }
                }
                if (blocked == 0) {
                    throw new NoSuchMethodException(
                            clsName + " has none of the expected init methods: " + missing);
                }
                ctx.log.info("blocked " + shortName + " init (" + blocked + " method(s))"
                        + (missing.length() > 0 ? ", absent: " + missing : ""));
            });
        }
    }

    /** 供将来需要时使用：拿到框架接口做更深的操作（如 deoptimize）。 */
    @SuppressWarnings("unused")
    private static XposedInterface xposedOf(Context ctx) {
        return ctx.xposed;
    }
}
