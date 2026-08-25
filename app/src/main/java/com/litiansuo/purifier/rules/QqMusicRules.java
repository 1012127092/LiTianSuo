package com.litiansuo.purifier.rules;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litiansuo.purifier.hook.Reflect;

/**
 * QQ 音乐（com.tencent.qqmusic）去广告规则。
 *
 * <p>与 123 云盘完全相反的处境，所以策略也完全不同：</p>
 * <ul>
 *   <li><b>无加固</b>——25 个 dex 全是明文业务码，静态反编译可用。所以这里的 hook 点
 *       都是<b>看过反编译源码后选的决策点</b>，不是靠真机探针猜的；</li>
 *   <li><b>原生 View + Hippy</b>，不是 Flutter。这意味着 {@code addView} 隐藏那条路
 *       在这个应用里真的有效（123 云盘上它对主界面完全无效）；</li>
 *   <li>广告类名<b>未混淆</b>：三家广告体系 {@code com.qq.e}（广点通）、
 *       {@code com.tencent.ams}（腾讯 AMS）、{@code com.tencentmusic.ad}（TME 自家）
 *       的包名全是明文，业务侧的广告位甚至把位置写进了包名
 *       （{@code business/ad/player}、{@code business/ad/topbarad} …）。</li>
 * </ul>
 *
 * <h2>刻意不动的部分</h2>
 *
 * <p>{@code business/ad/freemode}（免费听模式）、{@code ad/reward}（激励视频）、
 * {@code ad/vipearningmode}（会员赚取模式）这三块<b>不拦</b>：它们是「用户主动点了才出现、
 * 看广告换权益」的功能，拦掉等于剥夺用户的选择。同理 {@code TMEAds.init} 不掐——
 * 那三个功能都走它，掐了会一起坏。</p>
 *
 * <p>{@code com.qq.e}（广点通）与 {@code TangramAdManager} 则是纯外部广告联盟，
 * 与任何会员权益无关，可以从初始化就掐断。</p>
 */
final class QqMusicRules implements RuleSet {

    /**
     * 强引用根，防止 hook 相关对象被 GC。
     *
     * <p>与 {@link Pan123Rules} 同因：拦截器 lambda 及其捕获的 ctx 只被框架侧弱引用，
     * 不持有强引用的话注册完就可能被回收，症状是「装上了但一个钩子都不响」。</p>
     */
    private static final java.util.List<Object> ROOTS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 拦截广告 Activity 启动：开屏 WebView、广点通落地页、激励视频页。 */
    private static final String FEAT_AD_ACTIVITY = "ad-activity";
    /** 广告 View 加入布局时隐藏：顶部横幅、播放页广告条。 */
    private static final String FEAT_AD_VIEW = "ad-view";
    /** 播放页广告与暂停彩蛋的总闸：让「是否展示」判定恒为 false。 */
    private static final String FEAT_PLAYER_AD = "player-ad";
    /** 插屏广告：不让那个 DialogFragment 弹出来。 */
    private static final String FEAT_INTERSTITIAL = "interstitial-ad";
    /** 广点通初始化拦截（不含 TME 自家 SDK，那个牵扯免费听权益）。 */
    private static final String FEAT_GDT_INIT = "gdt-init";

    /**
     * 需要拦下的广告 Activity 全名。
     *
     * <p>全部取自 manifest 的明文声明，逐个说明为什么在这里：</p>
     * <ul>
     *   <li>{@code DynamicSplashActivity} —— 冷启动开屏。反编译确认它是个
     *       {@code WebViewActivity} 子类，靠 {@code auto_close_time} /
     *       {@code show_skip_btn} 这些 extra 驱动，是货真价实的开屏广告页；</li>
     *   <li>{@code HotLaunchSplashActivity} / {@code HotLaunchLargeScreenSplashActivity}
     *       —— 热启动开屏（切回前台时那一下），比冷启开屏更烦；</li>
     *   <li>{@code GDTLandingPageWebViewActivity} —— 广点通落地页，点到广告才会开，
     *       拦掉可防误触跳转；</li>
     *   <li>{@code AdLandingPageActivity} —— 腾讯 AMS 系落地页，同上。</li>
     * </ul>
     *
     * <p><b>不含</b> {@code AppStarterActivity}：那是应用真正的启动页，拦了打不开应用。</p>
     */
    private static final String[] AD_ACTIVITIES = {
            "com.tencent.qqmusic.activity.DynamicSplashActivity",
            "com.tencent.qqmusic.business.ad.splash.hotlaunch.HotLaunchSplashActivity",
            "com.tencent.qqmusic.business.ad.splash.hotlaunch.HotLaunchLargeScreenSplashActivity",
            "com.tencent.qqmusic.activity.GDTLandingPageWebViewActivity",
            "com.tencent.tads.splash.AdLandingPageActivity",
    };

    /**
     * 广告 SDK 与广告业务包的类名前缀，命中即认为是广告控件。
     *
     * <p>前三条是三家广告体系的根包，未混淆且跨版本稳定。第四条是应用自家的广告业务包——
     * QQ 音乐把广告位置直接写进了包名，这比任何启发式判断都准。</p>
     */
    private static final String[] AD_PREFIXES = {
            // 广点通（优量汇）
            "com.qq.e.",
            // 腾讯 AMS 广告引擎（fusion 竞价、mosaic 动态模板、dsdk 引擎）
            "com.tencent.ams.",
            // TME 自家广告 SDK
            "com.tencentmusic.ad.",
            // 应用自家广告业务包
            "com.tencent.qqmusic.business.ad.",
    };

    /**
     * 从 {@link #AD_PREFIXES} 里<b>豁免</b>的子包。
     *
     * <p>这些是「用户主动换权益」的功能，不是打扰型广告：</p>
     * <ul>
     *   <li>{@code freemode} / {@code radarfreemode} —— 免费听模式；</li>
     *   <li>{@code reward} —— 激励视频，用户点了才播；</li>
     *   <li>{@code vipearningmode} —— 会员赚取模式；</li>
     *   <li>{@code debug} —— 广告调试面板，与展示无关。</li>
     * </ul>
     *
     * <p>豁免必须写成显式清单而不是靠前缀精细化：广告业务包有 20 多个子包，
     * 列出「不拦哪些」比列出「拦哪些」短得多，也不会因为应用新增广告位而漏掉。</p>
     */
    private static final String[] KEEP_PREFIXES = {
            "com.tencent.qqmusic.business.ad.freemode.",
            "com.tencent.qqmusic.business.ad.radarfreemode.",
            "com.tencent.qqmusic.business.ad.reward.",
            "com.tencent.qqmusic.business.ad.vipearningmode.",
            "com.tencent.qqmusic.business.ad.debug.",
            "com.tencent.qqmusic.business.ad.topbarad.freemode.",
    };

    /** 该类名是否属于要拦的广告。 */
    private static boolean isAdClass(String name) {
        if (name == null) {
            return false;
        }
        // 豁免优先判：freemode 等子包同时也匹配 business.ad 前缀，顺序反了就会误杀
        for (String k : KEEP_PREFIXES) {
            if (name.startsWith(k)) {
                return false;
            }
        }
        for (String p : AD_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void installEarly(Context ctx) {
        ROOTS.add(ctx);
        ROOTS.add(this);

        installAdActivityBlock(ctx);
        installAdViewHide(ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>QQ 音乐没有壳，理论上 early 阶段业务类就已经可以加载了。但仍然把依赖业务类的
     * hook 放在 late：{@code onPackageLoaded} 时应用的 classloader 可能还没完全就绪，
     * 而 late 阶段是在 {@code Application.onCreate} 之前触发的，既保证类可用，
     * 又早于任何广告 SDK 初始化。</p>
     */
    @Override
    public void installLate(Context ctx) {
        installPlayerAdBlock(ctx);
        installInterstitialBlock(ctx);
        installGdtInitBlock(ctx);
    }

    /**
     * 在 {@code Instrumentation.execStartActivity} 处拦下广告 Activity。
     *
     * <p>选这个点的理由与 123 云盘一致：它是所有 Activity 启动的收敛处，
     * 不管从 Activity、Service 还是 Application context 发起都要过这里。</p>
     *
     * <p>判定用<b>精确全名清单</b>而不是前缀匹配。开屏页
     * {@code com.tencent.qqmusic.activity.DynamicSplashActivity} 与应用主界面同在
     * {@code activity} 包下，用前缀会把整个应用拦死。</p>
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
                    if (arg instanceof Intent) {
                        String target = componentOf((Intent) arg);
                        if (isAdActivity(target)) {
                            ctx.log.hit("blocked ad activity: " + target);
                            // 返回 null：调用方按「没有返回结果」处理，不会崩
                            return null;
                        }
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

    private static String componentOf(Intent intent) {
        return intent.getComponent() == null ? null : intent.getComponent().getClassName();
    }

    /**
     * 是否是要拦的广告页。
     *
     * <p>先查精确清单，再查广告 SDK 前缀（广点通那 10 个 {@code com.qq.e.tg.*ADActivity}
     * 不必逐个列名）。没有显式组件的隐式 Intent 一律放过——广告页都是显式启动的，
     * 在这里瞎猜会误杀分享、拨号等正常跳转。</p>
     */
    private static boolean isAdActivity(String className) {
        if (className == null) {
            return false;
        }
        for (String a : AD_ACTIVITIES) {
            if (className.equals(a)) {
                return true;
            }
        }
        return isAdClass(className);
    }

    /**
     * 广告 View 被加入布局时隐藏它。
     *
     * <p>这条规则在 QQ 音乐上比在 123 云盘上有用得多：这个应用的主界面是<b>原生 View</b>，
     * 顶部横幅（{@code topbarad}）、播放页广告条（{@code PlaySongTimeAdBar}）都是真的
     * Android View，会经过 {@code addView}。</p>
     *
     * <p>热路径纪律：这个方法在滑动列表时调用极频繁，所以回调里只做一次字符串前缀比较，
     * 结果按类对象缓存。不读配置、不查资源、不走 binder。</p>
     *
     * <p>设为 {@code GONE} 而不是拒绝添加：GONE 不占布局空间，视觉上等同消失，
     * 同时 SDK 后续对该 View 的引用仍然有效，不会因为拿不到 parent 而抛异常。</p>
     */
    private void installAdViewHide(Context ctx) {
        ctx.feature(FEAT_AD_VIEW, () -> {
            Method addView = Reflect.method(ViewGroup.class, "addView",
                    View.class, int.class, ViewGroup.LayoutParams.class);

            // 类 -> 是否广告 View。用类对象做键，避免每次都做字符串前缀匹配。
            final Map<Class<?>, Boolean> cache = new ConcurrentHashMap<>();

            ctx.hooks.intercept(FEAT_AD_VIEW, addView, chain -> {
                Object child = chain.getArg(0);
                if (child instanceof View) {
                    View v = (View) child;
                    Class<?> c = v.getClass();
                    Boolean isAd = cache.get(c);
                    if (isAd == null) {
                        isAd = isAdClass(c.getName());
                        cache.put(c, isAd);
                    }
                    if (isAd) {
                        v.setVisibility(View.GONE);
                        ctx.log.hit("hid ad view: " + c.getName());
                    }
                }
                return chain.proceed();
            });
        });
    }

    /**
     * 掐掉播放页广告与暂停彩蛋的展示判定。
     *
     * <p><b>这是本规则里最值钱的一个 hook。</b>反编译
     * {@code com.tencent.qqmusic.business.ad.player.PlayerAdControl} 后发现它有个方法：</p>
     * <pre>
     * public final boolean e(SongInfo song, AdType adType) {
     *     ...
     *     Log.h("EasterEggPlayerAdControl", "show ad new logic");
     *     return true;                       // 无条件放行
     * }
     * </pre>
     *
     * <p>{@code AdType} 只有两个枚举值 {@code playerAD} 与 {@code easterEggAD}，
     * 正好对应「播放页广告」与「暂停彩蛋广告」。这个方法就是两者共用的展示总闸，
     * 让它恒返回 {@code false} 等于两个广告位一起关掉——比在 UI 层追控件干净得多。</p>
     *
     * <p><b>不硬编码混淆后的方法名 {@code e}</b>：类名 {@code PlayerAdControl} 是明文的，
     * 但方法名会随每次混淆变化。所以按<b>签名特征</b>定位：返回 {@code boolean}、两个参数、
     * 第二个参数是枚举类型。这个组合在该类里唯一，且不随混淆改变。</p>
     */
    private void installPlayerAdBlock(Context ctx) {
        ctx.feature(FEAT_PLAYER_AD, () -> {
            Class<?> cls = Reflect.findClass(ctx.classLoader(),
                    "com.tencent.qqmusic.business.ad.player.PlayerAdControl");
            if (cls == null) {
                throw new ClassNotFoundException(
                        "com.tencent.qqmusic.business.ad.player.PlayerAdControl");
            }
            Method gate = findBooleanEnumGate(cls);
            if (gate == null) {
                throw new NoSuchMethodException(
                        "PlayerAdControl has no boolean(?, enum) method (obfuscation changed?)");
            }
            ctx.hooks.intercept(FEAT_PLAYER_AD, gate, chain -> {
                ctx.log.hit("player ad gate -> false (" + gate.getName() + ")");
                return Boolean.FALSE;
            });
            ctx.log.info("player ad gate hooked: " + gate.getName()
                    + "(" + gate.getParameterTypes()[0].getSimpleName()
                    + ", " + gate.getParameterTypes()[1].getSimpleName() + ")");
        });
    }

    /**
     * 找「返回 boolean、两个参数、第二个参数是枚举」的方法。
     *
     * <p>按签名而非名字定位，这样混淆改名不影响。找到多个就返回 null 让整项降级——
     * 猜错会 hook 到无关方法，那种故障极难排查，宁可这一项失效。</p>
     */
    private static Method findBooleanEnumGate(Class<?> cls) {
        Method found = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getReturnType() != boolean.class || m.getParameterCount() != 2) {
                continue;
            }
            if (!m.getParameterTypes()[1].isEnum()) {
                continue;
            }
            if (found != null) {
                return null; // 特征不唯一，不赌
            }
            found = m;
        }
        if (found != null) {
            found.setAccessible(true);
        }
        return found;
    }

    /**
     * 不让插屏广告弹出来。
     *
     * <p>插屏走的是 {@code business.ad.interstitial.InterstitialAdDialogFragment}，
     * 类名明文。hook 点选 {@code DialogFragment.show}——它是框架类，签名稳定，
     * 而且是所有弹出路径的必经处；比去猜业务侧哪个混淆方法负责触发可靠得多。</p>
     *
     * <p>按 {@code this} 的类名过滤，只拦广告那个 Fragment。应用里其它对话框
     * （登录、分享、确认框）都走同一个方法，不加过滤会把整个应用的弹窗全干掉。</p>
     *
     * <p>{@code show} 有两个重载：{@code show(FragmentManager, String)} 返回 void，
     * {@code showNow} 也是 void，而 {@code show(FragmentTransaction, String)} 返回 int。
     * 所以按返回类型分别给 null 与 -1——返回类型不匹配会当场抛 ClassCastException。</p>
     */
    private void installInterstitialBlock(Context ctx) {
        ctx.feature(FEAT_INTERSTITIAL, () -> {
            Class<?> dialogFragment = Reflect.findClassAny(ctx.classLoader(),
                    "androidx.fragment.app.DialogFragment",
                    "android.app.DialogFragment");
            if (dialogFragment == null) {
                throw new ClassNotFoundException("DialogFragment (androidx or platform)");
            }
            int n = 0;
            for (Method m : Reflect.methodsNamed(dialogFragment, "show")) {
                final boolean isVoid = m.getReturnType() == void.class;
                final boolean isInt = m.getReturnType() == int.class;
                if (!isVoid && !isInt) {
                    continue; // 未知返回类型不碰，避免类型不匹配当场崩
                }
                ctx.hooks.intercept(FEAT_INTERSTITIAL + "/" + m.getParameterCount(), m, chain -> {
                    Object self = chain.getThisObject();
                    if (self != null && isAdClass(self.getClass().getName())) {
                        ctx.log.hit("blocked ad dialog: " + self.getClass().getName());
                        // -1 是 FragmentTransaction.commit 的「无效事务 id」，调用方能容忍
                        return isVoid ? null : Integer.valueOf(-1);
                    }
                    return chain.proceed();
                });
                n++;
            }
            if (n == 0) {
                throw new NoSuchMethodException("DialogFragment#show has no void/int overload");
            }
            ctx.log.info("interstitial block installed on " + n + " overload(s)");
        });
    }

    /**
     * 掐掉广点通的初始化。
     *
     * <p>{@code GDTADManager.initWith(Context, String)} 返回 boolean，返回 false 表示
     * 初始化失败——这是 SDK 自己就会遇到并处理的情况，比让它抛异常安全。
     * {@code TangramAdManager.init(Context, String, TangramManagerListener)} 是 void，
     * 直接空实现。</p>
     *
     * <p><b>只掐广点通，不碰 {@code TMEAds.init}</b>：TME 自家 SDK 同时承载免费听模式、
     * 激励视频与会员赚取模式，掐了会一起坏。广点通是纯外部广告联盟，与权益无关。</p>
     *
     * <p>每家单独成一项（{@code gdt-init/<名字>}），一家的签名变了不影响另一家。</p>
     */
    private void installGdtInitBlock(Context ctx) {
        blockInit(ctx, "com.qq.e.comm.managers.GDTADManager", "initWith");
        blockInit(ctx, "com.qq.e.tg.tangram.TangramAdManager", "init");
    }

    /**
     * 把某个类的初始化方法按返回类型掐掉。
     *
     * <p>void 返回 null、boolean 返回 false、其它返回类型不动——猜一个假值可能让 SDK
     * 进入未定义状态，那比广告没拦住更糟。</p>
     */
    private void blockInit(Context ctx, String clsName, String methodName) {
        final String shortName = clsName.substring(clsName.lastIndexOf('.') + 1);
        final String featureId = FEAT_GDT_INIT + "/" + shortName;
        ctx.feature(featureId, () -> {
            Class<?> cls = Reflect.findClass(ctx.classLoader(), clsName);
            if (cls == null) {
                throw new ClassNotFoundException(clsName);
            }
            int n = 0;
            for (Method m : Reflect.methodsNamed(cls, methodName)) {
                Class<?> ret = m.getReturnType();
                final Object fake = ret == void.class ? null
                        : ret == boolean.class ? Boolean.FALSE : m;
                if (fake == m) {
                    continue; // 返回类型不认识，不赌
                }
                ctx.hooks.intercept(featureId + "/" + m.getParameterCount(), m, chain -> {
                    ctx.log.hit(shortName + "#" + methodName + " -> blocked");
                    return fake;
                });
                n++;
            }
            if (n == 0) {
                throw new NoSuchMethodException(
                        clsName + "#" + methodName + " has no void/boolean overload");
            }
            ctx.log.info("blocked " + shortName + " init (" + n + " method(s))");
        });
    }
}
