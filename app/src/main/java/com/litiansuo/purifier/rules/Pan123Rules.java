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
    /** 网络栈探测：判定广告数据能否在 Java 层拦下。 */
    private static final String FEAT_NET_PROBE = "net-probe";
    /** 请求 URL 探测：找出广告内容对应的接口。 */
    private static final String FEAT_URL_PROBE = "url-probe";
    /** 广告接口拦截：清空应用自家运营位的返回内容。 */
    private static final String FEAT_AD_API = "ad-api";
    /** 响应体结构探测：写过滤规则前必须先看到真实 JSON。 */
    private static final String FEAT_BODY_PROBE = "body-probe";
    /** Flutter 平台通道测绘：找出 Dart 侧广告位的数据来源。 */
    private static final String FEAT_FLUTTER = "flutter-probe";
    /** 原生桥过滤：在应用自家 Flutter 桥上改掉去广告开关。 */
    private static final String FEAT_BRIDGE = "native-bridge";

    /**
     * 桥调用参数里代表「显示广告/会员入口」的键名，一律改成关闭。
     *
     * <p>来自实测的 {@code storageAdFreeData} 与 {@code appAdConfig} 调用：</p>
     * <pre>
     * storageAdFreeData: button_splash_screen, button_upload, button_download,
     *                    button_quit, button_user_center, button_return_file,
     *                    remove_ads_effect, removeAdsTime
     * appAdConfig:       isPreloadSplash, isPreloadInter, isOpenSplash,
     *                    splashInterval, allInterval
     * </pre>
     *
     * <p>按键名匹配而不是按方法名：同一批开关会出现在多个调用里，
     * 盯键名一次覆盖全部，也不会因为应用改版换方法名而失效。</p>
     *
     * <p>{@code removeAdsTime} / {@code splashInterval} / {@code allInterval} 不在此列：
     * 它们是时长而非开关，置 0 可能被理解成「间隔 0 秒」而变成无限加载。</p>
     */
    private static final Set<String> AD_OFF_KEYS = new HashSet<>(java.util.Arrays.asList(
            "button_splash_screen",
            "button_upload",
            "button_download",
            "button_quit",
            "button_user_center",
            "button_return_file",
            "remove_ads_effect",
            "isPreloadSplash",
            "isPreloadInter",
            "isOpenSplash"
    ));

    /**
     * 需要逐条记录 payload 的通道。
     *
     * <p>{@code com.wisdom.water.main} 是应用自家的原生桥，实测形如
     * {@code pluginPool + methodName=getAppChannel}——单通道 + 方法名分发。
     * 这种通道按名字去重只会留下第一条，必须逐条看才能找出取广告配置的那次调用。</p>
     *
     * <p>{@code anythink_sdk} 是 AnyThink 聚合广告的 Dart 桥，广告请求就从这里发起。</p>
     */
    private static final Set<String> DETAIL_CHANNELS = new HashSet<>(java.util.Arrays.asList(
            "com.wisdom.water.main",
            "anythink_sdk"
    ));

    /**
     * 需要打印响应体的接口。
     *
     * <p><b>重要发现：整个应用只有 3 个请求走 Java 层 OkHttp</b>——
     * {@code /getconfig-api/v1/getconfig}、{@code /app/config/get}、
     * {@code /advert_resource/get}，都在启动阶段。之后无论怎么翻页、切 tab，
     * {@code url-probe} 再也不出新记录。</p>
     *
     * <p>这说明业务请求由 Dart 侧的 {@code dart:io HttpClient} 发出，走 native socket，
     * <b>Java hook 完全看不到</b>。所以能拦的就只有这 3 个启动期接口；
     * 传输页横幅与右下角浮标那类 Flutter 自绘、Dart 取数的内容不在射程内。</p>
     *
     * <p>保留配置接口是为了随时核对补丁是否真的写进去了。</p>
     */
    private static final String[] PROBE_PATHS = {
            // 不能写成 CONFIG_API_PATH：它声明在本字段之后，属于非法前向引用
            "/app/config/get",
    };

    /**
     * 替换后的广告接口响应。
     *
     * <p>{@code data.list} 原本是「广告位号 → 广告数组」的字典，置空对象即可让所有位置都没有
     * 广告，不必逐个枚举位置号——位置号会随运营调整，枚举等于埋雷。</p>
     *
     * <p>{@code code:0} 必须保留：改成错误码会让应用走失败分支，可能弹重试提示。</p>
     */
    private static final String EMPTY_AD_JSON =
            "{\"code\":0,\"message\":\"ok\",\"data\":{\"list\":{}}}";

    /**
     * 应用自家广告接口的 path 片段。
     *
     * <p>实测得来：主界面是 Flutter 绘制的，控件不是 Android View，按类名/资源名隐藏无效，
     * 唯一的着力点是数据源。而这个接口正是首页轮播、右下角浮标、传输页会员条的内容来源。</p>
     */
    private static final String AD_API_PATH = "/advert_resource/get";

    /**
     * 全局配置接口，{@code removeAdConfig} 里的开关就在这里下发。
     *
     * <p>它同时承载登录态、接口地址、客服链接等必需内容，所以只能定点改字段，
     * 绝不能像广告接口那样整体替换。</p>
     */
    private static final String CONFIG_API_PATH = "/app/config/get";

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
        installNetStackProbe(ctx);
        installUrlProbe(ctx);
        installBodyProbe(ctx);
        installFlutterChannelProbe(ctx);
        installNativeBridgeFilter(ctx);
        installAdApiFilter(ctx);
        installSdkInitBlock(ctx);
    }

    /**
     * 在应用自家的 Flutter 原生桥上改掉去广告配置。
     *
     * <p>为什么这是剩余两处广告的正确着力点：通道测绘显示 Dart 侧通过
     * {@code com.wisdom.water.main} 调用 {@code storageAdFreeData} 把
     * {@code button_upload} / {@code button_user_center} / {@code remove_ads_effect}
     * 等字段<b>存进原生侧</b>，随后再用 {@code obtainSharedPreferences} 取回来判断显示。</p>
     *
     * <p>这意味着改 HTTP 响应不够：Dart 侧可能有自己的缓存或默认值，
     * 而这里是「配置真正落地」的地方。把存进去的值全部改成 0，
     * 无论 Dart 从哪儿拿到原始配置，最终读到的都是关闭状态。</p>
     *
     * <p>hook 点选 {@code MethodCall} 的<b>构造函数</b>。原先想拦
     * {@code MethodChannel$MethodCallHandler.onMethodCall}，但那是接口里的抽象方法，
     * 框架直接拒绝：{@code Cannot hook abstract methods}。抽象方法没有实体可替换，
     * 要拦只能找到每个实现类——而实现类在应用侧且被混淆，不可行。</p>
     *
     * <p>{@code MethodCall} 由编解码器在解出完整参数后构造，是所有通道方法的必经之路，
     * 且它就在 Flutter 框架里、类名稳定。{@code arguments} 字段是 final，
     * 但解码出来的 Map 本身可变，<b>就地改内容</b>即可，不必碰字段。</p>
     *
     * <p><b>只改值不拦调用</b>：Dart 侧在 await 这些调用的回复，
     * 直接不放行会让它永远等下去，界面就卡住了。</p>
     */
    private void installNativeBridgeFilter(Context ctx) {
        ctx.feature(FEAT_BRIDGE, () -> {
            Class<?> callCls = Reflect.findClass(ctx.classLoader(),
                    "io.flutter.plugin.common.MethodCall");
            if (callCls == null) {
                throw new ClassNotFoundException("io.flutter.plugin.common.MethodCall");
            }
            java.lang.reflect.Constructor<?> ctor =
                    Reflect.ctor(callCls, String.class, Object.class);

            ctx.hooks.interceptCtor(FEAT_BRIDGE, ctor, chain -> {
                try {
                    Object args = chain.getArg(1);
                    if (args instanceof Map) {
                        patchBridgeArgs(ctx, (Map<?, ?>) args);
                    }
                } catch (Throwable t) {
                    ctx.log.error("native bridge filter failed", t);
                }
                return chain.proceed();
            });
        });
    }

    /**
     * 把桥调用参数里的广告开关全部改成关闭。
     *
     * <p>按<b>键名</b>匹配而不是按方法名：同一批开关会出现在
     * {@code storageAdFreeData}、{@code appAdConfig} 等多个调用里，
     * 盯键名一次覆盖全部，也不会因为应用改版换方法名而失效。</p>
     */
    @SuppressWarnings("unchecked")
    private static void patchBridgeArgs(Context ctx, Map<?, ?> args) {
        Map<Object, Object> m = (Map<Object, Object>) args;
        int changed = 0;
        for (Map.Entry<Object, Object> e : m.entrySet()) {
            if (!(e.getKey() instanceof String)) {
                continue;
            }
            String k = (String) e.getKey();
            Object v = e.getValue();
            if (AD_OFF_KEYS.contains(k)) {
                // 类型必须与原值一致：Dart 侧按声明类型解码，int 换成 bool 会直接抛异常
                if (v instanceof Boolean && (Boolean) v) {
                    e.setValue(Boolean.FALSE);
                    changed++;
                } else if (v instanceof Integer && (Integer) v != 0) {
                    e.setValue(0);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            ctx.log.hit("bridge args patched: " + changed + " key(s)");
        }
    }

    /**
     * 测绘 Flutter 平台通道，找出剩余广告位的数据来源。
     *
     * <p>为什么走这条路：传输页上传屏横幅与右下角「免广告」浮标是 Flutter 绘制的，
     * 既没有 Android View 可隐藏，也不经过 Java 层 OkHttp（全应用只有 3 个启动期请求走
     * OkHttp）。但 Flutter 与原生之间的<b>所有</b>通信都必须经过平台通道，
     * 而通道实现在 Java 侧——这是 Dart 世界唯一对 Java hook 敞开的入口。</p>
     *
     * <p>{@code DartMessenger} 是通道的唯一收敛点：{@code handleMessageFromDart} 是
     * Dart→Java，{@code send} 是 Java→Dart。只要配置或广告数据是通过原生侧取的，
     * 就一定出现在这里。</p>
     *
     * <p>{@code send} 的重载不止一个，用 {@link Reflect#methodsNamed} 全挂，
     * 避免猜错签名而漏掉真正在用的那个。</p>
     *
     * <p>成本控制：按「方向 + 通道名」去重，每个通道只记一次；
     * payload 另设总量上限，避免通道高频往返时把日志刷爆。</p>
     */
    private void installFlutterChannelProbe(Context ctx) {
        ctx.feature(FEAT_FLUTTER, () -> {
            Class<?> messengerCls = Reflect.findClass(ctx.classLoader(),
                    "io.flutter.embedding.engine.dart.DartMessenger");
            if (messengerCls == null) {
                throw new ClassNotFoundException("io.flutter.embedding.engine.dart.DartMessenger");
            }
            final Set<String> seen = java.util.Collections.synchronizedSet(new HashSet<>(64));
            final java.util.concurrent.atomic.AtomicInteger dumps =
                    new java.util.concurrent.atomic.AtomicInteger();

            int hooked = 0;
            for (Method m : Reflect.methodsNamed(messengerCls, "handleMessageFromDart")) {
                ctx.hooks.intercept(FEAT_FLUTTER, m, chain -> {
                    recordChannel(ctx, seen, dumps, "dart->java",
                            chain.getArg(0), chain.getArg(1));
                    return chain.proceed();
                });
                hooked++;
            }
            for (Method m : Reflect.methodsNamed(messengerCls, "send")) {
                ctx.hooks.intercept(FEAT_FLUTTER, m, chain -> {
                    recordChannel(ctx, seen, dumps, "java->dart",
                            chain.getArg(0), chain.getArg(1));
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("DartMessenger has no send/handleMessageFromDart");
            }
        });
    }

    /** 记录一条平台通道消息（按方向+通道名去重）。 */
    private static void recordChannel(Context ctx, Set<String> seen,
                                      java.util.concurrent.atomic.AtomicInteger dumps,
                                      String dir, Object channel, Object message) {
        try {
            if (!(channel instanceof String)) {
                return;
            }
            String name = (String) channel;
            String payload = message instanceof java.nio.ByteBuffer
                    ? readable((java.nio.ByteBuffer) message) : "";

            // 应用自家的桥要逐条看：它是「一个通道 + methodName 分发」的形式，
            // 按通道名去重只会留下第一条，真正关心的调用全被吃掉。
            boolean detail = DETAIL_CHANNELS.contains(name);
            String key = detail ? dir + " " + name + " " + payload : dir + " " + name;
            if (!seen.add(key)) {
                return;
            }
            if (detail && dumps.incrementAndGet() > 400) {
                return; // 逐条模式设总量上限，避免高频往返把日志刷爆
            }
            ctx.log.info("flutter channel " + dir + " " + name
                    + (payload.isEmpty() ? "" : " payload=" + payload));
        } catch (Throwable ignored) {
            // 测绘失败不该影响通道通信
        }
    }

    /**
     * 把通道消息的字节读成可读文本。
     *
     * <p>必须用 {@code duplicate()}：直接读会推进原 buffer 的 position，
     * 下游解码就会拿到残缺数据——这会把应用弄坏，而且症状与本模块看起来毫无关系。</p>
     *
     * <p>StandardMessageCodec 用 UTF-8 编码字符串，所以按 UTF-8 解能保住中文；
     * 长度与类型标记等二进制字节统一换成 {@code .}，只为看清结构与关键字。</p>
     */
    private static String readable(java.nio.ByteBuffer buf) {
        java.nio.ByteBuffer d = buf.duplicate();
        int len = Math.min(d.remaining(), 2048);
        byte[] bytes = new byte[len];
        d.get(bytes);
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '.' : c);
        }
        return sb.toString();
    }

    /**
     * 应用自家运营位的开关，全在 {@code /app/config/get} 的 {@code removeAdConfig} 里。
     *
     * <p>实测响应（已确认，非推测）：</p>
     * <pre>
     * "isInitAd":1,
     * "removeAdConfig":{"BuyRemoveAds":2,"button_download":1,"button_quit":1,
     *   "button_return_file":1,"button_splash_screen":1,"button_upload":1,
     *   "button_user_center":1,"mainTitle":"畅享无广告纯净体验",...}
     * </pre>
     *
     * <p>这几个 {@code button_*} 就是用户反馈的位置：传输页左右滑动的三屏
     * （上传 / 下载 / 离线下载）各有一条「VIP 连续包月」，对应
     * {@code button_upload} / {@code button_download} / {@code button_return_file}；
     * 右下角的「免广告」浮标对应 {@code button_user_center}。把它们置 0 即可整条消失，
     * 比在 UI 层找控件可靠得多——界面是 Flutter 画的，根本没有 Android View 可隐藏。</p>
     *
     * <p>{@code isInitAd} 置 0 是从源头关掉广告 SDK 初始化，与 {@code sdk-init} 的方法级掐断
     * 互为兜底：前者让应用自己不去初始化，后者防止它绕过开关。</p>
     *
     * <p><b>不动 {@code remove_ads_effect}</b>：字面看是「免广告已生效」，含义未确认，
     * 乱改可能让应用以为用户已购买而进入异常分支。只关入口，不伪造权益状态。</p>
     *
     * <p><b>已验证的效果</b>：{@code button_*} 全置 0 后，传输页三屏的会员横幅从 3 条降到 1 条
     * （下载页与离线下载页的消失，上传页的仍在）。这证明这批字段确实驱动那些位置。</p>
     *
     * <p><b>已验证无效</b>：{@code continuousPay} / {@code loadVipBuyId} /
     * {@code loadBuyEntryMode} 一并置 0，上传页那条横幅与右下角「免广告」浮标依然存在。
     * 结合「整个应用只有 3 个请求走 Java OkHttp」这一事实（见 {@link #PROBE_PATHS}），
     * 结论是这两处由 Dart 侧自行取数并绘制，<b>Java 层拦不到</b>。
     * 保留这三项是因为它们语义明确、置 0 无副作用，可覆盖走原生渲染的其它入口。</p>
     */
    private static final String[][] CONFIG_PATCHES = {
            // 广告 SDK 初始化总开关
            {"\"isInitAd\":1", "\"isInitAd\":0"},
            // 各处会员/去广告入口开关，字段名与位置一一对应。
            // 实测使传输页横幅从 3 条降到 1 条。
            {"\"button_upload\":1", "\"button_upload\":0"},
            {"\"button_download\":1", "\"button_download\":0"},
            {"\"button_return_file\":1", "\"button_return_file\":0"},
            {"\"button_user_center\":1", "\"button_user_center\":0"},
            {"\"button_quit\":1", "\"button_quit\":0"},
            {"\"button_splash_screen\":1", "\"button_splash_screen\":0"},
            // 「免广告」入口开放标记。此前不敢动，怕被当成伪造已购买状态；
            // 但通道测绘显示 Dart 侧把它与 button_* 一起塞进 storageAdFreeData，
            // 说明它与 button_* 同类，只控制入口是否展示，不代表实际权益。
            {"\"remove_ads_effect\":1", "\"remove_ads_effect\":0"},
            // 剩余一条横幅的候选开关：连续包月与会员商品 id
            {"\"continuousPay\":1", "\"continuousPay\":0"},
            {"\"loadVipBuyId\":154", "\"loadVipBuyId\":0"},
            {"\"loadBuyEntryMode\":2", "\"loadBuyEntryMode\":0"},
    };

    /**
     * 把指定接口的响应体原样打进日志，用于确定 JSON 结构。
     *
     * <p>写过滤规则前必须先看到真实结构：靠猜字段名改响应，要么改不动，要么把应用改崩。
     * {@link #PROBE_PATHS} 里列的是还没吃透的接口。</p>
     *
     * <p>读 body 必须用 {@code peekBody(long)}——它复制一份而不消费原流。直接调
     * {@code body().string()} 会把流读空，应用随后拿到空响应，这是改网络层最容易踩的坑。</p>
     *
     * <p>每个 path 只打一次：响应体可能很大。</p>
     */
    private void installBodyProbe(Context ctx) {
        ctx.feature(FEAT_BODY_PROBE, () -> {
            Class<?> builderCls = Reflect.findClass(ctx.classLoader(), "okhttp3.Response$Builder");
            if (builderCls == null) {
                throw new ClassNotFoundException("okhttp3.Response$Builder (not loaded)");
            }
            Method build = Reflect.method(builderCls, "build");
            final Set<String> dumped = java.util.Collections.synchronizedSet(new HashSet<>(16));

            ctx.hooks.intercept(FEAT_BODY_PROBE, build, chain -> {
                Object response = chain.proceed();
                if (response == null) {
                    return response;
                }
                try {
                    Object request = Reflect.call(response, "request");
                    Object url = request == null ? null : Reflect.call(request, "url");
                    if (url == null) {
                        return response;
                    }
                    String full = url.toString();
                    String matched = null;
                    for (String p : PROBE_PATHS) {
                        if (full.contains(p)) {
                            matched = p;
                            break;
                        }
                    }
                    // body 为 null 的中间 Response 必须跳过：拦截器链每层都会 build 一个，
                    // 其中不少还没有 body。若在此之前就记入 dumped，唯一的机会就被浪费掉了。
                    if (matched == null || Reflect.call(response, "body") == null) {
                        return response;
                    }
                    Method peek = Reflect.method(response.getClass(), "peekBody", long.class);
                    Object copy = peek.invoke(response, 262144L);
                    String text = copy == null
                            ? null : decodeBody((byte[]) Reflect.call(copy, "bytes"));
                    if (text == null || !dumped.add(matched)) {
                        return response;
                    }
                    ctx.log.info("body " + matched + ": " + text);
                } catch (Throwable t) {
                    // 打 cause：反射调用的失败都被 InvocationTargetException 包了一层
                    Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                            ? t.getCause() : t;
                    ctx.log.warn("body probe failed: " + (cause == null ? t : cause));
                }
                return response;
            });
        });
    }

    /**
     * 清空应用自家广告接口的返回内容。
     *
     * <p><b>这是首页轮播、右下角浮标、传输页会员条的唯一可行拦法。</b>主界面由 Flutter 绘制，
     * 控件不是 Android View（实测切底部 tab 时 {@code addView} 无任何新记录，APK 内含
     * {@code libapp.so}），按类名或资源名隐藏对它们完全无效。只能从数据源下手。</p>
     *
     * <p>接口 {@code api.123278.com/api/v2/advert_resource/get} 的真实响应：</p>
     * <pre>
     * {"code":0,"message":"ok","data":{"list":{"2001":[{"advert_id":...,"advert_position":2001,
     *   "image_url":"...","jump_url":"{\"path\":\"/vip/center/page\"}",...}]}}}
     * </pre>
     *
     * <p>{@code list} 是「广告位号 → 广告数组」的字典，所以把 {@code list} 置空对象即可让所有
     * 位置都没有广告，而不必逐个位置枚举——位置号会随运营调整，枚举等于埋雷。
     * 保留 {@code code:0} 是关键：改成错误码会让应用走失败分支，可能弹重试提示。</p>
     *
     * <p>hook 点选 {@code Response$Builder.build()} 并<b>改 builder 的 body 字段</b>，
     * 而不是拿到 Response 再 {@code newBuilder().build()}——后者会重新进入本 hook 造成递归。</p>
     *
     * <p>编码自适应：实测该接口回的是 gzip（应用自己加了 {@code Accept-Encoding}，
     * 所以 OkHttp 不做透明解压）。这里先读原始字节判断是否 gzip，替换内容<b>按原样编码</b>，
     * 避免下游解压层拿到明文而报错。</p>
     */
    private void installAdApiFilter(Context ctx) {
        ctx.feature(FEAT_AD_API, () -> {
            ClassLoader cl = ctx.classLoader();
            Class<?> builderCls = Reflect.findClass(cl, "okhttp3.Response$Builder");
            Class<?> bodyCls = Reflect.findClass(cl, "okhttp3.ResponseBody");
            Class<?> mediaCls = Reflect.findClass(cl, "okhttp3.MediaType");
            if (builderCls == null || bodyCls == null || mediaCls == null) {
                throw new ClassNotFoundException("okhttp3 Response$Builder/ResponseBody/MediaType");
            }
            Method build = Reflect.method(builderCls, "build");
            Method create = Reflect.method(bodyCls, "create", mediaCls, byte[].class);
            java.lang.reflect.Field bodyField = Reflect.field(builderCls, "body");
            java.lang.reflect.Field requestField = Reflect.field(builderCls, "request");

            ctx.hooks.intercept(FEAT_AD_API, build, chain -> {
                try {
                    Object builder = chain.getThisObject();
                    if (builder == null) {
                        return chain.proceed();
                    }
                    Object oldBody = bodyField.get(builder);
                    if (oldBody == null) {
                        return chain.proceed(); // 拦截器链中的中间 builder，多数没有 body
                    }
                    Object request = requestField.get(builder);
                    Object url = request == null ? null : Reflect.call(request, "url");
                    if (url == null) {
                        return chain.proceed();
                    }
                    String full = url.toString();
                    boolean isAdApi = full.contains(AD_API_PATH);
                    boolean isConfig = full.contains(CONFIG_API_PATH);
                    if (!isAdApi && !isConfig) {
                        return chain.proceed();
                    }

                    // 读原始字节只为判断编码方式；这份 body 随后就被替换，消费掉无妨
                    byte[] raw = (byte[]) Reflect.call(oldBody, "bytes");
                    boolean gzipped = raw != null && raw.length > 2
                            && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b;

                    String newText;
                    if (isAdApi) {
                        // 广告接口整体替换：内容全是广告，没有需要保留的部分
                        newText = EMPTY_AD_JSON;
                    } else {
                        // 配置接口只能<b>定点改字段</b>：同一份响应里还有登录态、接口地址、
                        // 客服链接等应用正常运行所必需的内容，整体替换会直接把应用弄坏
                        String text = decodeBody(raw);
                        if (text == null) {
                            return chain.proceed();
                        }
                        newText = patchConfig(text);
                        if (newText.equals(text)) {
                            return chain.proceed(); // 没有可改的字段，保持原样
                        }
                    }

                    byte[] payload = gzipped ? gzip(newText)
                            : newText.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    Object mediaType = Reflect.call(oldBody, "contentType");
                    Object newBody = create.invoke(null, mediaType, payload);
                    bodyField.set(builder, newBody);
                    ctx.log.hit((isAdApi ? "emptied ad api" : "patched config")
                            + " (" + (raw == null ? 0 : raw.length) + " -> " + payload.length
                            + " bytes, gzip=" + gzipped + ")");
                } catch (Throwable t) {
                    // 绝不能让替换失败连带影响正常请求
                    ctx.log.error("ad api filter failed", t);
                }
                return chain.proceed();
            });
        });
    }

    /** 按 {@link #CONFIG_PATCHES} 逐条替换配置字段。 */
    private static String patchConfig(String text) {
        String out = text;
        for (String[] pair : CONFIG_PATCHES) {
            out = out.replace(pair[0], pair[1]);
        }
        return out;
    }

    /** 用 gzip 压一段文本，用于保持与原响应相同的编码。 */
    private static byte[] gzip(String text) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256);
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /**
     * 记录经过 OkHttp 的请求 URL，判断广告数据是否走 Java 网络层。
     *
     * <p>为什么这一步是关键：主界面由 Flutter 绘制，控件不是 Android View，
     * 按类名或资源名隐藏对它们无效（实测切 tab 时 {@code addView} 无任何新记录）。
     * 用户反馈的三处广告都在这一层，所以唯一的着力点是<b>数据来源</b>。</p>
     *
     * <p>而 {@code net stack present} 已确认 OkHttp 与 Flutter 同时存在——Flutter 应用
     * 既可能用 Dart 的 {@code HttpClient}（走 native socket，Java 完全碰不到），
     * 也可能通过平台通道交给 Java 的 OkHttp。这个探针就是来区分这两种情况的：
     * 只要日志里出现业务接口 URL，就说明能在响应层动手。</p>
     *
     * <p>只记录 path、按 path 去重、不碰 body：URL 里常带 token 与设备标识，
     * 完整记下来既是隐私问题也会淹掉日志；而判断「能不能拦」只需要 path。</p>
     */
    private void installUrlProbe(Context ctx) {
        ctx.feature(FEAT_URL_PROBE, () -> {
            Class<?> clientCls = Reflect.findClass(ctx.classLoader(), "okhttp3.OkHttpClient");
            if (clientCls == null) {
                throw new ClassNotFoundException("okhttp3.OkHttpClient (not loaded)");
            }
            Method newCall = Reflect.methodByArity(clientCls, "newCall", 1);
            final Set<String> seen = java.util.Collections.synchronizedSet(new HashSet<>(128));

            ctx.hooks.intercept(FEAT_URL_PROBE, newCall, chain -> {
                try {
                    Object request = chain.getArg(0);
                    if (request != null) {
                        // request.url() 返回 HttpUrl，toString() 是完整 URL
                        Object url = Reflect.call(request, "url");
                        if (url != null) {
                            String path = pathOf(url.toString());
                            if (path != null && seen.add(path)) {
                                ctx.log.info("http path: " + path);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 探针失败不能影响请求本身
                }
                return chain.proceed();
            });
        });
    }

    /** 从完整 URL 里取出 host+path，丢掉 query——query 里常有 token 与设备标识。 */
    private static String pathOf(String url) {
        int q = url.indexOf('?');
        String noQuery = q < 0 ? url : url.substring(0, q);
        // 去掉协议前缀，日志更短更好读
        int scheme = noQuery.indexOf("://");
        return scheme < 0 ? noQuery : noQuery.substring(scheme + 3);
    }

    /**
     * 把响应字节解成可读文本，必要时先解压。
     *
     * <p>实测该接口返回的是 <b>gzip</b>：直接调 {@code ResponseBody.string()} 拿到的是乱码。
     * OkHttp 只对自己加的 {@code Accept-Encoding: gzip} 做透明解压，而这个应用是<b>自己</b>
     * 加的请求头，所以 body 到手仍是压缩态。</p>
     *
     * <p>用魔数 {@code 0x1f 0x8b} 判断而不是读 {@code Content-Encoding} 头：中间层可能已经
     * 改过头字段，而魔数不会骗人。</p>
     */
    private static String decodeBody(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length * 4);
                try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                return out.toString("UTF-8");
            }
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 探测应用用的是哪套 HTTP 栈，只记录不改行为。
     *
     * <p>为什么必须先做这一步：实测主界面是 <b>Flutter</b> 绘制的（首页有
     * {@code view id=0x1 cls=FlutterView}，APK 内含 {@code libapp.so}，且切换底部 tab
     * 时 {@code addView} 一条新记录都没有）。Flutter 的控件不是 Android View，
     * 画在同一张 canvas 上，所以<b>按类名或资源名隐藏对它们完全无效</b>——
     * 用户反馈的首页轮播、右下角浮标、传输页会员条都在这一层。</p>
     *
     * <p>剩下的着力点只有数据层：广告内容来自网络响应。但方向取决于网络栈——
     * 若走 Java 的 OkHttp/HttpURLConnection，可以在响应层过滤；若走 Dart 的
     * {@code dart:io HttpClient}（native socket），Java hook 根本碰不到，
     * 就得改从 Flutter 的平台通道或直接放弃这三处。</p>
     *
     * <p>所以这里先把事实探明再决定，而不是先写一堆规则再发现方向错了。</p>
     */
    private void installNetStackProbe(Context ctx) {
        ctx.feature(FEAT_NET_PROBE, () -> {
            String[] candidates = {
                    // Java 侧：可在响应层过滤
                    "okhttp3.OkHttpClient",
                    "okhttp3.Interceptor",
                    "com.android.okhttp.OkHttpClient",
                    "retrofit2.Retrofit",
                    "com.squareup.okhttp.OkHttpClient",
                    // Flutter 平台通道：若存在，说明 Dart 侧可能把请求转交 Java
                    "io.flutter.embedding.engine.FlutterEngine",
                    "io.flutter.plugin.common.MethodChannel",
                    "io.flutter.view.FlutterView",
            };
            StringBuilder present = new StringBuilder();
            for (String cls : candidates) {
                if (Reflect.hasClass(ctx.classLoader(), cls)) {
                    if (present.length() > 0) {
                        present.append(", ");
                    }
                    present.append(cls);
                }
            }
            ctx.log.info("net stack present: " + (present.length() == 0 ? "(none)" : present));

            // AnyThink 的 Flutter 桥。找它是因为通道测绘显示 Dart 侧通过 anythink_sdk 通道
            // 发起 loadBannerAd / loadNativeAd / loadInterstitialAd / loadRewardedVideo，
            // 而这些调用的 Java 落点就在这批 Manager 里。在 Java 侧拦它们比拦通道消息安全：
            // 通道消息如果不放行，Dart 侧的 await 可能永远等不到回复而卡死。
            String[] bridges = {
                    "com.anythink.flutter.banner.ATBannerManager",
                    "com.anythink.flutter.nativead.ATNativeManager",
                    "com.anythink.flutter.interstitial.ATInterstitialManager",
                    "com.anythink.flutter.rewardvideo.ATRewardVideoManager",
                    "com.anythink.flutter.splash.ATSplashManager",
                    "com.anythink.flutter.utils.MsgUtil",
                    "com.anythink.flutter.AnythinkSdkPlugin",
            };
            StringBuilder bridge = new StringBuilder();
            for (String cls : bridges) {
                if (Reflect.hasClass(ctx.classLoader(), cls)) {
                    if (bridge.length() > 0) {
                        bridge.append(", ");
                    }
                    bridge.append(cls.substring(cls.lastIndexOf('.') + 1));
                }
            }
            ctx.log.info("anythink flutter bridge: " + (bridge.length() == 0 ? "(none)" : bridge));
        });
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
