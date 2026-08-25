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
    /** 广告 SDK 初始化拦截（从源头掐断，广告请求不会发出）。 */
    private static final String FEAT_SDK_INIT = "sdk-init";
    /** 按资源名隐藏广告位：应对类名混淆但资源名明文的情况。 */
    private static final String FEAT_AD_VIEW_ID = "ad-view-id";
    /** 广告接口拦截：清空应用自家运营位的返回内容。 */
    private static final String FEAT_AD_API = "ad-api";
    /** AnyThink Flutter 桥拦截：掐掉 Dart 侧发起的广告加载请求。 */
    private static final String FEAT_AT_BRIDGE = "anythink-bridge";
    /** 自家广告缓存清理：清掉 Dart 侧存在 prefs 里的运营位内容与开关。 */
    private static final String FEAT_OWN_AD = "own-ad-cache";

    /**
     * Flutter prefs 里代表「显示广告/会员入口」的开关键名，一律置 0。
     *
     * <p>这批键在 HTTP 响应（{@code removeAdConfig}）与 Flutter prefs 里<b>各有一份</b>，
     * 而 Dart 读的是自己缓存的那一份。HTTP 侧置 0 已实测使传输页横幅由 3 条降到 1 条，
     * 语义确认无误，所以缓存侧同样置 0。</p>
     *
     * <p>只收开关，不收时长：{@code removeAdsTime} 之类置 0 等于「权益已过期」，
     * 是反效果，另见 {@link #AD_TIME_KEYS}。</p>
     */
    private static final Set<String> AD_OFF_KEYS = new HashSet<>(java.util.Arrays.asList(
            "button_splash_screen",
            "button_upload",
            "button_download",
            "button_quit",
            "button_user_center",
            "button_return_file"
    ));

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
     * <p>这批名字来自真机测绘（曾有一个 {@code survey} 功能把每个 {@code addView}
     * 的资源名打进日志，名单固化后已移除）。资源名不参与混淆，
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
            // 按资源名隐藏：混淆过的 SDK 弹窗（Sigmob 的 wm_* 那套）只能靠这条路
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
                    } else if (hideById) {
                        hideByResourceId(ctx, v);
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
     *
     * <p>顺序有意为之：先掐 Dart 侧的加载请求（{@code anythink-bridge}），
     * 再清缓存与改接口响应。反过来的话第一批广告已经在路上了。</p>
     */
    @Override
    public void installLate(Context ctx) {
        installAnyThinkBridgeBlock(ctx);
        installOwnAdCacheFilter(ctx);
        installAdApiFilter(ctx);
        installSdkInitBlock(ctx);
    }

    /**
     * 清掉 Dart 侧缓存的自家广告数据。
     *
     * <p><b>这是右下角浮标的真正数据来源。</b>键名测绘发现 Flutter 的
     * {@code FlutterSharedPreferences} 里存着一整套自家广告缓存：</p>
     * <pre>
     * flutter.ownAdInfo_ / flutter.ownAdInfo_&lt;uid&gt;   自家广告内容
     * flutter.lastOwnAdNo_&lt;uid&gt;_2001                 各位置最近一条广告号
     * flutter.lastShowAdType_&lt;uid&gt;_2001/2002/2005    各位置最近展示类型
     * flutter.lastOwnAdTime_&lt;uid&gt;_30004              各位置最近展示时间
     * </pre>
     *
     * <p>那些 2001 / 2002 / 30004 正是 {@code advert_resource/get} 响应里
     * {@code data.list} 的位置号——也就是说<b>接口被我们清空了，但 Dart 侧还留着
     * 上次的缓存，照样能把广告画出来</b>。这解释了为什么清空接口对浮标毫无效果。</p>
     *
     * <p>关键前提：Flutter 的 {@code shared_preferences} 最终落在 Java 层的
     * {@code SharedPreferencesImpl}，读取走 {@code getAll()}。所以这份 Dart 数据
     * 反而是 Java hook 能碰到的——比通道更靠底层。</p>
     *
     * <p>{@code getAll()} 返回的是内部 map 的副本（{@code new HashMap<>(mMap)}），
     * 改它不会污染真正的 prefs，磁盘文件也不会被改写。</p>
     *
     * <p><b>只删内容缓存，不动频次记录</b>：{@code lastOwnAd*} / {@code lastShowAdType*}
     * 是「这个位置上次展示了什么、什么时候」，删掉反而可能被理解成「从未展示过」
     * 而立刻补一次。内容没了广告自然画不出来，频次记录留着无害。</p>
     */
    private void installOwnAdCacheFilter(Context ctx) {
        ctx.feature(FEAT_OWN_AD, () -> {
            Class<?> prefsCls = Reflect.findClass(ctx.classLoader(),
                    "android.app.SharedPreferencesImpl");
            if (prefsCls == null) {
                throw new ClassNotFoundException("android.app.SharedPreferencesImpl");
            }
            Method getAll = Reflect.method(prefsCls, "getAll");
            Method getString = Reflect.method(prefsCls, "getString", String.class, String.class);

            ctx.hooks.intercept(FEAT_OWN_AD, getAll, chain -> {
                Object all = chain.proceed();
                if (!(all instanceof Map)) {
                    return all;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) all;
                int cleared = 0;
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    if (!isOwnAdCacheKey(e.getKey())) {
                        continue;
                    }
                    Object v = e.getValue();
                    // 保持原类型：Dart 按声明类型解码，类型不符会抛异常。
                    // 空字符串 / 空集合表示「没有广告」，比 null 安全——
                    // 缺键可能触发「首次运行」分支去重新拉取。
                    if (v instanceof String) {
                        if (((String) v).isEmpty()) {
                            continue;
                        }
                        e.setValue("");
                        cleared++;
                    } else if (v instanceof Set) {
                        if (((Set<?>) v).isEmpty()) {
                            continue;
                        }
                        e.setValue(new HashSet<String>());
                        cleared++;
                    }
                }
                int off = forceAdSwitchesOff(map);
                int urls = breakAdEndpoints(ctx, map);
                if (cleared > 0) {
                    ctx.log.hit("own-ad cache cleared: " + cleared + " key(s)");
                }
                if (off > 0) {
                    ctx.log.hit("prefs ad switches off: " + off + " key(s)");
                }
                if (urls > 0) {
                    ctx.log.hit("ad endpoints broken: " + urls);
                }
                return map;
            });

            // shared_preferences 新版会逐键读而不是整体 getAll，两条路都得堵
            ctx.hooks.intercept(FEAT_OWN_AD + "-get", getString, chain -> {
                Object key = chain.getArg(0);
                if (!(key instanceof String) || !isOwnAdCacheKey((String) key)) {
                    return chain.proceed();
                }
                ctx.log.hit("own-ad cache read blocked: " + key);
                return "";
            });
        });
    }

    /**
     * 判断一个 prefs 键是否是自家广告的<b>内容</b>缓存。
     *
     * <p>只认内容类键。频次记录（{@code lastOwnAdNo} / {@code lastOwnAdTime} /
     * {@code lastShowAdType}）刻意排除在外，理由见
     * {@link #installOwnAdCacheFilter}。</p>
     *
     * <p>用前缀匹配而不是完整键名：键名里带用户 id 与位置号
     * （{@code flutter.ownAdInfo_1811711495}），写死等于只对一个账号有效。</p>
     */
    private static boolean isOwnAdCacheKey(String key) {
        return key.startsWith("flutter.ownAdInfo");
    }

    /**
     * 把 Flutter prefs 里的去广告开关强制置成「已开启免广告」。
     *
     * <p>键名测绘的关键发现：{@code button_upload} / {@code button_download} /
     * {@code button_quit} / {@code button_user_center} / {@code button_return_file} /
     * {@code button_splash_screen} / {@code removeAdsEffect} / {@code removeAdsTime}
     * <b>在 Flutter prefs 里各有一份</b>。</p>
     *
     * <p>这解释了此前所有努力为何无效：我们改的是 HTTP 响应和通道参数，
     * 但 Dart 侧读的是<b>自己缓存的这一份</b>。首次启动时缓存已经写好，
     * 之后就不再理会接口返回什么。</p>
     *
     * <p>{@code button_*} 语义已由实测确认（HTTP 侧置 0 使传输页横幅 3 条降到 1 条），
     * 所以这里同样置 0；{@code removeAdsEffect} 保持开启、{@code removeAdsTime} 顶满，
     * 理由见 {@link #CONFIG_PATCHES}。</p>
     *
     * @return 实际改动的键数
     */
    private static int forceAdSwitchesOff(Map<String, Object> map) {
        int changed = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(FLUTTER_PREFIX)) {
                continue;
            }
            String name = key.substring(FLUTTER_PREFIX.length());
            Object v = e.getValue();
            if (AD_TIME_KEYS.contains(name)) {
                Object now = asSameType(v, AD_FREE_TIME);
                if (now != null && !now.equals(v)) {
                    e.setValue(now);
                    changed++;
                }
            } else if (AD_OFF_KEYS.contains(name)) {
                Object now = asSameType(v, 0L);
                if (now != null && !now.equals(v)) {
                    e.setValue(now);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 把一个数值按 {@code sample} 的实际类型转换。
     *
     * <p>prefs 里同一个语义的字段在不同版本可能存成 bool / int / long / String，
     * 而 Dart 按声明类型解码，类型不符会直接抛异常。所以只能照原样返回，
     * 遇到无法处理的类型返回 {@code null} 表示「不动它」。</p>
     */
    private static Object asSameType(Object sample, long value) {
        if (sample instanceof Boolean) {
            return value != 0;
        }
        if (sample instanceof Integer) {
            // long 超出 int 范围时截断没有意义，取 int 上限
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
        if (sample instanceof Long) {
            return value;
        }
        if (sample instanceof String) {
            return String.valueOf(value);
        }
        return null;
    }

    /** Flutter {@code shared_preferences} 给每个键加的前缀。 */
    private static final String FLUTTER_PREFIX = "flutter.";

    /**
     * 把 Dart 侧取广告的接口地址改成无效地址。
     *
     * <p><b>这是 Java 层唯一能碰到 Dart 网络请求的地方。</b>Dart 用
     * {@code dart:io HttpClient} 走 native socket，请求本身 Java 看不见；
     * 但请求用的<b>地址</b>来自 {@code flutter.interface_config}，
     * 而这份配置存在 prefs 里、经 Java 层的 {@code SharedPreferencesImpl} 读出。</p>
     *
     * <p>实测这份配置里有 200 多个接口 URL，其中与广告直接相关的两个：</p>
     * <pre>
     * adFreeConfig     /api/restful/goapi/v1/remove_ads/config   免广告配置
     * advertALiReport  /api/restful/goapi/v1/advert/ali/report   广告曝光上报
     * </pre>
     *
     * <p>浮标是「免广告」入口，{@code adFreeConfig} 正是它的数据来源。
     * 把地址改成保证连不通的形式，Dart 侧请求失败 → 拿不到配置 → 画不出浮标。</p>
     *
     * <p>用 {@code 127.0.0.1:1} 而不是空串或垃圾字符：空串可能让 Dart 抛
     * {@code FormatException} 而不是网络错误，落进未预料的分支；
     * 合法但连不上的地址会走正常的「请求失败」路径，是应用本来就会处理的情况。</p>
     *
     * <p><b>只改广告相关的，其余 200 多个业务接口一个都不动</b>——
     * 改错一个就是整个功能不可用。</p>
     *
     * @return 改动的地址数
     */
    private static int breakAdEndpoints(Context ctx, Map<String, Object> map) {
        Object raw = map.get("flutter.interface_config");
        if (!(raw instanceof String)) {
            return 0;
        }
        String json = (String) raw;
        String patched = json;
        int n = 0;
        for (String key : AD_ENDPOINT_KEYS) {
            String next = replaceJsonUrl(patched, key);
            if (!next.equals(patched)) {
                patched = next;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        map.put("flutter.interface_config", patched);
        return n;
    }

    /**
     * 把 JSON 里 {@code "key":"<url>"} 的地址部分换成死地址。
     *
     * <p>手写替换而不是解析 JSON：这份配置有 200 多个字段、结构未知，
     * 解析再序列化有改坏其它字段的风险，而这里只需要动一个值。</p>
     */
    private static String replaceJsonUrl(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return json;
        }
        int from = at + needle.length();
        int end = json.indexOf('"', from);
        if (end < 0) {
            return json;
        }
        if (json.startsWith(DEAD_URL, from)) {
            return json;
        }
        return json.substring(0, from) + DEAD_URL + json.substring(end);
    }

    /**
     * 广告相关的接口字段名，取自实测的 {@code interface_config}。
     *
     * <p>只留曝光上报：它纯粹是广告统计，断掉没有任何用户可见的功能损失。</p>
     *
     * <p><b>刻意不含 {@code adFreeConfig}</b>：试过，浮标不受影响
     * （日志确认 {@code ad endpoints broken: 2} 时浮标仍在），
     * 说明浮标数据不来自它；而它是「看广告换 24 小时免广告」那个功能的配置接口，
     * 断掉反而剥夺了用户真要用这功能时的选择。既无收益又有代价，不留。</p>
     */
    private static final String[] AD_ENDPOINT_KEYS = {
            "advertALiReport",
    };

    /**
     * 死地址：语法合法但保证连不上。
     *
     * <p>回环地址保证不会打到真实主机，端口 1 属于特权端口、普通应用连不上。
     * 斜杠保持 {@code interface_config} 原有的 {@code \/} 转义写法。</p>
     */
    private static final String DEAD_URL = "http:\\/\\/127.0.0.1:1\\/blocked";

    /**
     * 掐掉 Dart 侧发起的 AnyThink 广告请求。
     *
     * <p><b>这是弹窗、原生广告、横幅的真正来源。</b>通道测绘抓到 Dart 侧持续调用：</p>
     * <pre>
     * anythink_sdk loadNativeAd      placementID=b693bea4da4576 / b693bea67177ba
     * anythink_sdk loadBannerAd      placementID=b693bea9112a25 / b693937619055f
     * anythink_sdk loadInterstitialAd placementID=b687e0e80aa637 / b693937657d34f
     * anythink_sdk loadRewardedVideo  placementID=b6a2a1cea7a00b
     * </pre>
     *
     * <p>此前 {@code sdk-init} 拦的是 {@code ATSDK.init}，但 Dart 侧的加载请求走的是
     * Flutter 插件这条独立路径，不经过那些静态入口，所以照样发得出去。</p>
     *
     * <p>hook 点<b>不能</b>选 {@code AnythinkSdkPlugin.onMethodCall}——实测该类没有这个方法
     * （{@code NoSuchMethodException params=2}）。这个插件把通道处理器注册为
     * lambda 或内部类，方法名不稳定。</p>
     *
     * <p>改为拦 {@code MsgUtil} 之外的公共入口不可行，最终选<b>按方法名前缀过滤
     * {@code MethodCall} 构造函数</b>：{@link #installNativeBridgeFilter} 已经在这里，
     * 通道名与方法名都能拿到，识别出广告加载调用后<b>把方法名改掉</b>，
     * 让插件的分发逻辑落到「未知方法」分支，自然回 {@code notImplemented}。</p>
     *
     * <p>为什么改方法名而不是清空参数：参数结构 Dart 侧不检查，清空未必阻止加载；
     * 而方法名不匹配一定走不到加载逻辑。{@code notImplemented} 对 Dart 是个明确的
     * 「这个功能不存在」，比无限等待安全。</p>
     */
    private void installAnyThinkBridgeBlock(Context ctx) {
        ctx.feature(FEAT_AT_BRIDGE, () -> {
            Class<?> callCls = Reflect.findClass(ctx.classLoader(),
                    "io.flutter.plugin.common.MethodCall");
            if (callCls == null) {
                throw new ClassNotFoundException("io.flutter.plugin.common.MethodCall");
            }
            java.lang.reflect.Constructor<?> ctor =
                    Reflect.ctor(callCls, String.class, Object.class);
            java.lang.reflect.Field methodField = Reflect.field(callCls, "method");

            ctx.hooks.interceptCtor(FEAT_AT_BRIDGE, ctor, chain -> {
                Object created = chain.proceed();
                try {
                    Object name = chain.getArg(0);
                    if (name instanceof String && isAdLoadCall((String) name)) {
                        Object target = chain.getThisObject();
                        if (target != null) {
                            // method 是 final，但反射可写；构造刚结束、还没人读过它
                            methodField.set(target, "litiansuoBlocked_" + name);
                            ctx.log.hit("anythink " + name + " -> blocked");
                        }
                    }
                } catch (Throwable t) {
                    ctx.log.error("anythink bridge block failed", t);
                }
                return created;
            });
        });
    }

    /**
     * 判断一个通道方法名是否会真正拉取或展示广告。
     *
     * <p>按前缀判断而不是枚举：placement 类型会随版本增加
     * （{@code loadNativeAd} / {@code loadBannerAd} / {@code loadInterstitialAd}
     * / {@code loadRewardedVideo}...），枚举必然滞后。</p>
     *
     * <p>{@code Ad} / {@code Video} 后缀是必要的限定：光看 {@code load} 前缀会误伤
     * 应用自家桥上的 {@code loadXxx} 之类正常方法。</p>
     */
    private static boolean isAdLoadCall(String name) {
        boolean isLoad = name.startsWith("load") || name.startsWith("preload")
                || name.startsWith("showAd") || name.startsWith("show");
        if (!isLoad) {
            return false;
        }
        return name.endsWith("Ad") || name.endsWith("Video") || name.contains("Ad");
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
     * <p><b>{@code remove_ads_effect} 保持 1 不动</b>：它是免广告功能的开关，
     * 与 {@code RemoveAdsTime}（剩余时长）成对使用。此前把它置 0 是判断错误——
     * 关掉开关会让时长根本不被检查，正确做法是保持开关为 1、把时长顶满。</p>
     *
     * <p><b>已验证的效果</b>：{@code button_*} 全置 0 后，传输页三屏的会员横幅从 3 条降到 1 条
     * （下载页与离线下载页的消失，上传页的仍在）。这证明这批字段确实驱动那些位置。</p>
     *
     * <p><b>已验证无效</b>：{@code continuousPay} / {@code loadVipBuyId} /
     * {@code loadBuyEntryMode} 一并置 0，上传页那条横幅与右下角「免广告」浮标依然存在。
     * 结合「整个应用只有 3 个请求走 Java 层 OkHttp」这一实测事实
     * （{@code /getconfig-api/v1/getconfig}、{@code /app/config/get}、
     * {@code /advert_resource/get}，全在启动阶段），
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
            // 免广告权益：把剩余时长改成很大的值，等于「已领取且远未到期」。
            //
            // 这两个字段是一对：remove_ads_effect 是功能开关，RemoveAdsTime 是剩余时长。
            // 原始值 effect=1 + time=0 意思是「功能可用但没有时长」——也就是
            // 应用弹的「连续看 3 个广告可获得 24 小时免广告权益」要发的那个奖。
            //
            // 所以 effect 必须保持 1（此前把它置 0 是判断错误：关掉开关会让时长根本不被检查），
            // 只把时长顶满。取 2e9 而不是更大：若该字段是 int，超过 2^31-1 会溢出；
            // 2e9 作为时间戳约合 2033 年，作为秒数约合 63 年，两种语义都够用。
            {"\"RemoveAdsTime\":0", "\"RemoveAdsTime\":2000000000"},
            // 会员购买入口。实测对 Flutter 侧那两处无效，但语义明确、置 0 无副作用，
            // 保留以覆盖走原生渲染的其它入口。
            {"\"continuousPay\":1", "\"continuousPay\":0"},
            {"\"loadVipBuyId\":154", "\"loadVipBuyId\":0"},
            {"\"loadBuyEntryMode\":2", "\"loadBuyEntryMode\":0"},
    };

    /**
     * 桥参数里表示「免广告剩余时长」的键，改成很大的值而非 0。
     *
     * <p>与 {@link #AD_OFF_KEYS} 相反：那些是开关，置 0 表示关闭；
     * 这些是时长，必须顶满才表示「权益还在有效期内」。置 0 反而等于权益已过期。</p>
     */
    private static final Set<String> AD_TIME_KEYS = new HashSet<>(java.util.Arrays.asList(
            "removeAdsTime",
            "RemoveAdsTime"
    ));

    /** 免广告剩余时长的伪造值，取值理由见 {@link #CONFIG_PATCHES}。 */
    private static final long AD_FREE_TIME = 2000000000L;

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
     * 掐掉各家 SDK 的初始化入口。
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
