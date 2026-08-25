package com.litiansuo.purifier.rules;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.litiansuo.purifier.hook.Reflect;

/**
 * 123 云盘（com.mfcloudcalculate.networkdisk）去广告规则。
 *
 * <p>背景：该应用用爱加密整体加固，业务 dex 加密存放在 {@code assets/ijiami.dat} 里，运行时
 * 才解密加载，静态反编译只能拿到壳（{@code s.h.e.l.l.*}）。所以这里<b>不依赖任何业务类名</b>，
 * 全部从广告 SDK 与 Android 框架这两个稳定面下手。</p>
 *
 * <p>实测结论（决定了本规则的重点）：开屏卡顿的直接原因是 <b>AnyThink 聚合</b>在做
 * head bidding，日志里反复出现 {@code "adtype":"Splash" ... "msg":"bid timeout!"}，
 * 向多家广告源逐一等待超时。所以掐断 SDK 初始化不仅去广告，也顺带治好了卡开屏。</p>
 */
final class Pan123Rules implements RuleSet {

    /**
     * 强引用根，防止 hook 相关对象被 GC。
     *
     * <p>自检证明 hook 在注册那一刻可用，但之后任何等待应用触发的钩子都不响。一个候选原因是
     * 拦截器 lambda 与它捕获的 {@code ctx} 只被框架侧弱引用，注册完就被回收。用静态集合
     * 持有强引用把这个变量排除掉——静态是有意的：它必须活得和进程一样久。</p>
     */
    private static final java.util.List<Object> ROOTS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 拦截广告 Activity 启动（开屏、激励视频、落地页都是独立 Activity）。 */
    private static final String FEAT_AD_ACTIVITY = "ad-activity";
    /** 广告 View 加入布局时隐藏（信息流内嵌广告、banner）。 */
    private static final String FEAT_AD_VIEW = "ad-view";
    /** 广告 SDK 探测：确认到底哪几家真的被加载。 */
    private static final String FEAT_PROBE = "probe";
    /** 广告 SDK 初始化拦截（从源头掐断，广告请求不会发出）。 */
    private static final String FEAT_SDK_INIT = "sdk-init";
    /**
     * 界面测绘：把控件资源名打进日志，用于定位应用自家运营位。
     *
     * <p>默认开启，规则写全后可在模块里单独关掉。它只记日志、不改行为。</p>
     */
    private static final String FEAT_SURVEY = "survey";
    /** 按资源名隐藏广告位：应对类名混淆但资源名明文的情况。 */
    private static final String FEAT_AD_VIEW_ID = "ad-view-id";

    /**
     * 广告容器的资源名。命中即整块隐藏。
     *
     * <p>这批名字来自真机测绘（{@link ViewSurvey}），不是猜的。资源名不参与混淆，
     * 是加固应用里唯一稳定的界面锚点。</p>
     *
     * <p>只收<b>容器</b>不收叶子控件：隐藏 {@code frame_ad_splash_container} 比逐个隐藏
     * 里面的 {@code iv_splash}/{@code iv_close} 干净，也不会留下空白占位。</p>
     */
    private static final Set<String> AD_CONTAINER_IDS = new HashSet<>(java.util.Arrays.asList(
            // 开屏广告容器（实测 +0.7s 加入布局）
            "frame_ad_splash_container",
            // Sigmob/WindMill 弹窗插屏：摇一摇、扭一扭、滑动跳转那一整套
            "wm_pop_pic_container",
            "wm_pop_media_container",
            "wm_pop_video_container",
            "wm_popup_shake_view",
            "wm_popup_twist_view",
            "wm_popup_swipe_horizontal_view",
            "wm_popup_swipe_vertical_view",
            "wm_shake_click_region",
            "wm_twist_click_region",
            "wm_click_region",
            "wm_interaction_view",
            // 美数广告标识
            "layout_ad_logo"
    ));

    /**
     * 各家 SDK 的初始化入口：类名 -> 候选方法名。
     *
     * <p>掐断 init 后 SDK 拿不到配置，后续 loadAd 会直接失败，比等广告渲染出来再隐藏更彻底，
     * 也顺带省掉广告的流量、耗电与竞价等待。逐条独立注册，某家没集成或改了签名只影响那一条。</p>
     *
     * <p>AnyThink 放在最前：实测它是开屏的实际入口，优先级最高。</p>
     */
    private static final Map<String, String[]> SDK_INIT_ENTRIES = new LinkedHashMap<>();

    static {
        // AnyThink / TopOn 聚合——开屏竞价的发起方
        SDK_INIT_ENTRIES.put("com.anythink.core.api.ATSDK",
                new String[]{"init", "initCustomMap", "start"});
        // 穿山甲：init 建实例、start 真正拉配置
        SDK_INIT_ENTRIES.put("com.bytedance.sdk.openadsdk.TTAdSdk",
                new String[]{"init", "start"});
        // 优量汇（广点通）
        SDK_INIT_ENTRIES.put("com.qq.e.comm.managers.GDTAdSdk",
                new String[]{"init", "initWithoutStart", "start"});
        // 快手
        SDK_INIT_ENTRIES.put("com.kwad.sdk.api.KsAdSDK", new String[]{"init", "start"});
        // Sigmob
        SDK_INIT_ENTRIES.put("com.sigmob.windad.WindAds",
                new String[]{"startWithOptions", "init"});
        // 倍孜
        SDK_INIT_ENTRIES.put("com.beizi.fusion.BeiZis", new String[]{"init", "asyncInit"});
        // 章鱼
        SDK_INIT_ENTRIES.put("com.octopus.ad.Octopus", new String[]{"init"});
        // 美数
        SDK_INIT_ENTRIES.put("com.meishu.sdk.core.AdSdk", new String[]{"init"});
        // 爱奇艺
        SDK_INIT_ENTRIES.put("com.mcto.sspsdk.QyClient", new String[]{"init"});
    }

    // ------------------------------------------------------------ early 阶段

    /**
     * {@inheritDoc}
     *
     * <p>此时壳还没解密业务 dex，只能 hook 系统提供的框架类。</p>
     */
    @Override
    public void installEarly(Context ctx) {
        // 强引用住上下文与规则实例：拦截器 lambda 捕获了它们，不能被回收
        ROOTS.add(ctx);
        ROOTS.add(this);

        installAdActivityBlock(ctx);
        installAdViewHide(ctx);
    }

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
            int n = 0;
            for (Method m : Reflect.methodsNamed(
                    android.app.Instrumentation.class, "execStartActivity")) {
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

    /**
     * 广告 View 被加入布局时隐藏它。
     *
     * <p>hook 点选 {@code ViewGroup#addView(View, int, LayoutParams)}：其余 addView 重载最终
     * 都会汇聚到这个方法，只堵这一个既完整又便宜。</p>
     *
     * <p>热路径纪律：这个方法在滑动列表时调用极频繁，所以回调里只做一次字符串前缀比较，
     * 命中结果按类对象缓存。不读配置、不查资源、不走 binder。</p>
     *
     * <p>处理方式是设为 {@code GONE} 而不是拒绝添加：GONE 不占布局空间，视觉上等同消失，
     * 同时 SDK 后续对该 View 的引用仍然有效，不会因为拿不到 parent 而抛异常。</p>
     */
    private void installAdViewHide(Context ctx) {
        ctx.feature(FEAT_AD_VIEW, () -> {
            Method addView = Reflect.method(ViewGroup.class, "addView",
                    View.class, int.class, ViewGroup.LayoutParams.class);

            // 类 -> 是否广告 View。用类对象做键，避免每次都做字符串前缀匹配。
            final Map<Class<?>, Boolean> cache = new ConcurrentHashMap<>();
            // 测绘：应用自家运营位无法用类名前缀识别，只能靠资源名，先把真实资源名收集出来
            final ViewSurvey survey = ctx.config.isFeatureEnabled(FEAT_SURVEY)
                    ? new ViewSurvey(ctx.log) : null;
            if (survey == null) {
                ctx.guard.markDisabled(FEAT_SURVEY);
            }
            // 按资源名隐藏：混淆过的 SDK 弹窗与应用自家运营位只能靠这条路
            final boolean hideById = ctx.config.isFeatureEnabled(FEAT_AD_VIEW_ID);
            if (!hideById) {
                ctx.guard.markDisabled(FEAT_AD_VIEW_ID);
            }

            ctx.hooks.intercept(FEAT_AD_VIEW, addView, chain -> {
                Object child = chain.getArg(0);
                if (child instanceof View) {
                    View v = (View) child;
                    Class<?> c = v.getClass();
                    Boolean isAd = cache.get(c);
                    if (isAd == null) {
                        isAd = AdSdk.isAdClass(c.getName());
                        cache.put(c, isAd);
                    }
                    if (isAd) {
                        v.setVisibility(View.GONE);
                        ctx.log.hit("hid ad view: " + c.getName());
                    } else if (hideById && hideByResourceId(ctx, v)) {
                        // 已按资源名处理，不再测绘
                    } else if (survey != null) {
                        // 只对非广告 View 测绘：广告 View 已经处理掉了，不需要再记
                        survey.record(v, 0);
                    }
                }
                return chain.proceed();
            });
        });
    }

    /**
     * 按资源名判定并隐藏广告容器。
     *
     * <p>为什么需要这条路：SDK 控件能靠类名前缀识别，但混淆过的 SDK（如 Sigmob 的
     * {@code wm_*} 弹窗）与应用自家运营位都不行。资源名不参与混淆，是这类广告唯一的稳定锚点。</p>
     *
     * <p>热路径纪律：先用 {@code getId()} 做整数判断挡掉绝大多数无 id 的 View，
     * 只有真的有 id 时才去查资源名——{@code getResourceEntryName} 要走 Resources 查表，
     * 不能对每个 addView 都调。</p>
     *
     * @return 是否命中并隐藏
     */
    private static boolean hideByResourceId(Context ctx, View v) {
        if (v.getId() == View.NO_ID) {
            return false;
        }
        String name;
        try {
            name = v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return false;
        }
        if (name == null || !AD_CONTAINER_IDS.contains(name)) {
            return false;
        }
        v.setVisibility(View.GONE);
        ctx.log.hit("hid ad container by id: " + name);
        return true;
    }

    // ------------------------------------------------------------- late 阶段

    /**
     * {@inheritDoc}
     *
     * <p>此时壳已解密并加载真实 dex，广告 SDK 的类可以定位了。</p>
     */
    @Override
    public void installLate(Context ctx) {
        installProbe(ctx);
        installSdkInitBlock(ctx);
    }

    /**
     * 只记录、不改行为：把实际加载成功的广告 SDK 打进日志。
     *
     * <p>这一项是后续迭代的依据——清单里列了十几家，但未必都会在运行时初始化。
     * 有了这份名单才知道该重点堵谁，而不是盲目加规则。</p>
     */
    private void installProbe(Context ctx) {
        ctx.feature(FEAT_PROBE, () -> {
            StringBuilder present = new StringBuilder();
            for (String cls : SDK_INIT_ENTRIES.keySet()) {
                if (Reflect.hasClass(ctx.classLoader(), cls)) {
                    if (present.length() > 0) {
                        present.append(", ");
                    }
                    present.append(cls.substring(cls.lastIndexOf('.') + 1));
                }
            }
            ctx.log.info("ad sdk present: " + (present.length() == 0 ? "(none)" : present));
        });
    }

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
                Class<?> cls = Reflect.findClass(ctx.classLoader(), clsName);
                if (cls == null) {
                    // 这家 SDK 没被集成或未加载，属正常情况
                    throw new ClassNotFoundException(clsName + " (not loaded)");
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
}
