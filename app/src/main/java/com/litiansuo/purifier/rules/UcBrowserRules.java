package com.litiansuo.purifier.rules;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import com.litiansuo.purifier.hook.Reflect;

/**
 * UC 浏览器（com.UCMobile）去广告规则。
 *
 * <p>与 QQ 音乐同属「无加固、明文可反编译」阵营，25 个 dex 全是明文业务码，
 * 所以 hook 点都是看过反编译源码后选的决策点，不是靠真机探针猜的。</p>
 *
 * <h2>广告体系</h2>
 * <p>UC 是广告重灾区，接了一整排第三方广告联盟 + 阿里自家 Noah：</p>
 * <ul>
 *   <li><b>Noah</b>（{@code com.noah}）—— UC 自家开屏/banner 广告底座，也是阿里系；</li>
 *   <li><b>穿山甲</b>（{@code com.bytedance.sdk.openadsdk}）；</li>
 *   <li><b>百度</b>（{@code com.baidu.mobads}）；</li>
 *   <li><b>Tanx</b>（{@code com.alimm.tanx}）、<b>广点通</b>（{@code com.qq.e}）、
 *       <b>华为</b>（{@code com.huawei.openalliance.ad}）。</li>
 * </ul>
 *
 * <h2>本轮做的两件事（最高价值、零副作用）</h2>
 * <ol>
 *   <li><b>开屏广告</b>：UC 的开屏不是 Activity，而是一个挂在主界面窗口上的
 *       {@code com.uc.browser.splashscreen.SplashWindow}（继承 FrameLayout）。
 *       同时广告内容由 {@code com.noah.api.SplashAd.showSplashAd(ViewGroup)} 渲染。
 *       两头都掐：不渲染广告内容 + 窗口一挂上就 GONE；</li>
 *   <li><b>第三方广告 Activity</b>：穿山甲/百度/Tanx/华为/广点通/Noah 的落地页、
 *       激励视频页，全在 {@code Instrumentation.execStartActivity} 收敛点按精确类名
 *       与 SDK 前缀拦下。</li>
 * </ol>
 *
 * <h2>刻意不动的部分</h2>
 * <p>UC 自家的激励视频 {@code com.uc.browser.advertisement.jilivideo}（用户主动点
 * 「看广告得奖励」）、以及抖音直播插件 {@code openliveplugin} 不拦——前者是用户选择，
 * 后者不是打扰型广告。穿山甲的 {@code com.byazt.*} 下载桩也不拦，以免误伤正常下载。</p>
 */
final class UcBrowserRules implements RuleSet {

    /**
     * 强引用根，防止 hook 相关对象被 GC。
     *
     * <p>与其它规则集同因：拦截器 lambda 及其捕获的 ctx 只被框架侧弱引用，
     * 不持有强引用的话注册完就可能被回收，症状是「装上了但一个钩子都不响」。</p>
     */
    private static final java.util.List<Object> ROOTS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 拦截广告 Activity 启动：各 SDK 落地页、激励视频页。 */
    private static final String FEAT_AD_ACTIVITY = "ad-activity";
    /** 开屏广告拦截：SplashWindow 窗口 + Noah 开屏渲染。 */
    private static final String FEAT_SPLASH = "splash-ad";
    /** 「李田所 · UC」面板连点入口：挂在设置页底部版本号那行。 */
    private static final String FEAT_ABOUT_ENTRY = "about-entry";
    /** 推送保活拦截：受面板开关控制，默认关。 */
    private static final String FEAT_PUSH = "push-block";
    /** 信息流/小说内广告 View 隐藏：受 K_FEEDAD/K_NOVELAD 面板开关控制。 */
    private static final String FEAT_AD_VIEW = "ad-view";
    /**
     * UC 全栈活动 dump：把 UC 启动 + 使用过程中所有 Activity / Service / Receiver /
     * Provider / View / WebView / Fragment / Dialog 全部打点到 logcat。默认开。
     *
     * <p>用途：主人想弄清楚 UC 浏览器里都有哪些组件、哪些 View、哪些广告位 —— 走一遍
     * UC 后看 logcat 就能列出真实运行路径，不靠反编译猜。</p>
     */
    private static final String FEAT_DUMP = "uc-dump";
    /**
     * 统计/上报 Service 拦截：UC 自家数据上报、UBox 埋点、穿山甲 Provider 模板缓存。
     * 受 K_STAT 开关控制，默认开。
     */
    private static final String FEAT_STAT = "stat-block";
    /**
     * 底栏 tab 隐藏：藏掉前 N 个 tab（默认 2 个 = 首页 + 短剧），受 K_HOME/K_SHORT 控制。
     */
    private static final String FEAT_TAB_HIDE = "tab-hide";
    /**
     * 启动加速：拦截开屏 Activity、开机自启 Service、UC 启动时的额外 P 拉起。
     * 主人当纯网盘用，去掉首屏拉起广告 SDK 那一套，加快主界面显示。
     */
    private static final String FEAT_LAUNCH = "launch-block";
    /** 网盘设为主页：启动后自动切换到网盘 tab。 */
    private static final String FEAT_NETDISK_HOME = "netdisk-home";
    /** 个性推荐弹窗拦截：隐藏"个性推荐获得更丰富内容"等引导浮层。 */
    private static final String FEAT_POPUP_BLOCK = "popup-block";
    /** CrashRecovery 拦截：禁用网盘页面崩溃恢复保存/恢复，解决页面来回闪烁。 */
    private static final String FEAT_CRASH_RECOVERY = "crash-recovery-block";

    /** 目标应用私有开关存储（面板勾选写这里）。late 阶段打开。 */
    private com.litiansuo.purifier.hook.LocalPrefs prefs;

    /** 版本号那行的文字前缀：设置窗口底部固定 footer「UC浏览器 V19.0.0.1536」。 */
    private static final String VERSION_ROW_PREFIX = "UC浏览器";
    /** 连点计数器（跨 TextView 实例，类级别）。 */
    private static final java.util.concurrent.atomic.AtomicInteger VER_CLICKS =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 上次点击时间戳，用于超时重置（连点要连续，隔太久重新计）。 */
    private static volatile long verLastClickAt = 0L;
    /** 触发面板所需连点次数。 */
    private static final int VER_CLICK_TARGET = 7;
    /** 两次点击超过这个间隔就重置计数（毫秒）。 */
    private static final long VER_CLICK_WINDOW_MS = 1500L;

    /**
     * 推送/保活 Service 类名前缀。命中即在主进程侧拦下其 {@code startService}/{@code bindService}。
     *
     * <p>类名全部来自 manifest 明文声明，分厂商列出：一家改名不影响其它家。
     * UC 自家 push（{@code com.uc.base.push}）+ 各手机厂商推送 SDK + 保活/常驻服务。</p>
     */
    private static final String[] PUSH_PREFIXES = {
            // UC 自家推送
            "com.uc.base.push.",
            // 保活/常驻（前台助手、oomadj）
            "com.uc.base.system.oomadj.",
            // 厂商推送 SDK
            "com.xiaomi.mipush.", "com.xiaomi.push.",
            "com.huawei.hms.support.api.push.", "com.huawei.hms.support.api.push.service.",
            "com.uc.base.push.huawei.", "com.uc.base.push.honor.",
            "com.heytap.mcssdk.",              // OPPO
            "com.vivo.push.", "com.vivo.ic.dm.util.KeepAlive",
            "com.meizu.cloud.pushsdk.",
            "com.lib.push.",
            // ACCS（阿里推送通道）
            "com.taobao.accs.", "org.android.agoo.", "com.uc.base.push.accs.",
    };

    /** UC 开屏窗口类（明文，继承 FrameLayout，挂在主界面窗口上）。 */
    private static final String SPLASH_WINDOW =
            "com.uc.browser.splashscreen.SplashWindow";
    /** Noah 开屏广告 API 类。 */
    private static final String NOAH_SPLASH_AD = "com.noah.api.SplashAd";

    /**
     * 要拦的第三方广告 Activity 精确类名清单。
     *
     * <p>只列纯广告页：落地页（Landing/Browser/WebPage）、激励/全屏视频
     * （Reward/FullScreen）、插屏（TableScreen/Interstitial）。不含直播插件、
     * 下载桩、UC 自家用户主动触发的激励视频。</p>
     */
    private static final String[] AD_ACTIVITIES = {
            // 穿山甲（官方包，非 com.byazt 混淆桩）
            "com.bytedance.sdk.openadsdk.activity.FakeRewardVideoActivity",
            "com.bytedance.sdk.openadsdk.core.activity.base.TTNativePageActivity",
            "com.bytedance.sdk.openadsdk.core.activity.base.TTSevenScreenWebPageActivity",
            "com.bytedance.sdk.openadsdk.core.activity.base.TTVideoScrollWebPageActivity",
            "com.bytedance.sdk.openadsdk.core.activity.base.TTVideoWebPageActivity",
            "com.bytedance.sdk.openadsdk.core.activity.base.TTWebPageActivity",
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTFullScreenVideoActivity",
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTFullScreenVideoLandscapeActivity",
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTRewardVideoActivity",
            "com.bytedance.sdk.openadsdk.core.component.reward.activity.TTRewardVideoLandscapeActivity",
            // 百度
            "com.baidu.mobads.sdk.api.MobRewardVideoActivity",
            "com.baidu.mobads.sdk.api.AppActivity",
            // Tanx（阿里妈妈）
            "com.alimm.tanx.core.ad.ad.template.rendering.reward.RewardPortraitActivity",
            "com.alimm.tanx.core.ad.ad.template.rendering.reward.RewardVideoPortraitActivity",
            "com.alimm.tanx.core.ad.ad.template.rendering.table.screen.TableScreenPortraitActivity",
            "com.alimm.tanx.core.ad.browser.TanxBrowserActivity",
            "com.alimm.tanx.ui.ad.express.reward.RewardVideoPortraitActivity",
            // 华为 openalliance
            "com.huawei.openalliance.ad.activity.PPSBridgeActivity",
            "com.huawei.openalliance.ad.activity.PPSLauncherActivity",
            // Noah（阿里）落地页/激励视频
            // HCBaseActivity 是汇川广告基类 Activity（HCCommonActivity/HCRewardVideoActivity 的父类），
            // 反编译确认它通过 IActivityBridge 分发各种广告 Activity 的生命周期
            "com.noah.adn.huichuan.view.HCBaseActivity",
            "com.noah.adn.huichuan.view.HCCommonActivity",
            "com.noah.adn.huichuan.view.rewardvideo.HCRewardVideoActivity",
            "com.noah.adn.huichuan.webview.BrowserActivity",
            "com.noah.dev.NoahDialogActivity",
            "com.noah.sdk.business.rewardfeed.feed.RewardFeedActivity",
            "com.noah.sdk.business.rewardvideo.RewardVideoOneMoreGuideActivity",
            "com.noah.webview.SdkBrowserActivity",
            // 广点通（优量汇）
            "com.qq.e.ads.ADActivity",
            "com.qq.e.ads.DialogActivity",
            "com.qq.e.ads.LandscapeADActivity",
            "com.qq.e.ads.PortraitADActivity",
            "com.qq.e.ads.RewardvideoLandscapeADActivity",
            "com.qq.e.ads.RewardvideoPortraitADActivity",
    };

    /**
     * 广告 SDK 根包前缀。命中即认为是广告页，用于兜住上面清单没枚举全的重载/新增页。
     *
     * <p>只列纯广告联盟根包，且这些包<b>只</b>含广告相关类，前缀匹配不会误伤业务。</p>
     */
    private static final String[] AD_ACTIVITY_PREFIXES = {
            "com.qq.e.ads.",
            "com.alimm.tanx.core.ad.",
            "com.alimm.tanx.ui.ad.",
            "com.baidu.mobads.sdk.api.Mob",
    };

    /** 该 Activity 类名是否应被拦下。隐式 Intent（无组件名）一律放过。 */
    private static boolean isAdActivity(String className) {
        if (className == null) {
            return false;
        }
        for (String a : AD_ACTIVITIES) {
            if (className.equals(a)) {
                return true;
            }
        }
        for (String p : AD_ACTIVITY_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void installEarly(Context ctx) {
        ROOTS.add(ctx);
        ROOTS.add(this);
        // execStartActivity 是框架类收敛点，early 阶段就能 hook（不依赖业务 dex）。
        installAdActivityBlock(ctx);
    }

    @Override
    public void installLate(Context ctx) {
        // 面板开关存储：需要 Application context 才能拿到私有 prefs 目录。
        openPrefs(ctx);
        // SplashWindow / Noah SplashAd 是业务/SDK 类，需真实 dex 加载完成，放 late。
        installSplashBlock(ctx);
        // 设置页底部版本号连点入口 → 弹「李田所 · UC」面板。
        installAboutEntry(ctx);
        // 推送保活拦截（受面板开关控制，默认关）。
        installPushBlock(ctx);
        // 统计上报拦截（受 K_STAT 面板开关控制，默认开）。
        installStatBlock(ctx);
        // 信息流/小说内广告 View 隐藏（受 K_FEEDAD/K_NOVELAD 面板开关控制）。
        installAdViewHide(ctx);
        // 底栏 tab 隐藏（首页/短剧）
        installTabHide(ctx);
        // 启动加速（开屏/开机 Service 拦截）
        installLaunchBlock(ctx);
        // UC 全栈活动 dump：默认开，挨个 Activity/Service/View 走 logcat。
        installDump(ctx);
        // 网盘设为主页：启动后自动切换到网盘 tab。
        installAutoNetDisk(ctx);
        // 个性推荐弹窗拦截
        installPopupBlock(ctx);
        // CrashRecovery 拦截：禁用网盘页面崩溃恢复，解决来回闪烁。
        installCrashRecoveryBlock(ctx);
    }

    // ----------------------------------------------------------- 面板开关存储


    /**
     * 打开面板开关的本地存储。失败不抛——面板是增强项，读不到时其它规则照常工作。
     */
    private void openPrefs(Context ctx) {
        try {
            android.app.Application app = currentApplication();
            if (app == null) {
                ctx.log.warn("no application context; panel switches unavailable");
                return;
            }
            this.prefs = com.litiansuo.purifier.hook.LocalPrefs.open(app, ctx.log);
        } catch (Throwable t) {
            ctx.log.error("failed to open panel switches", t);
        }
    }

    private static android.app.Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof android.app.Application ? (android.app.Application) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------- 广告 Activity 拦截

    /**
     * 在 {@code Instrumentation.execStartActivity} 处拦下广告 Activity。
     *
     * <p>它是所有 Activity 启动的收敛处，不管从 Activity、Service 还是 Application
     * context 发起都要过这里。返回 {@code null} 让调用方按「没有返回结果」处理，不会崩。</p>
     *
     * <p>拦截判定分两段：</p>
     * <ol>
     *   <li>{@link #isAdActivity}：命中受 K_POPUP 面板开关控制；</li>
     *   <li>{@link #isMinigameActivity}：命中受 K_MINIGAME 面板开关控制。</li>
     * </ol>
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
                        if (target != null) {
                            if (isAdActivity(target)) {
                                ctx.log.hitThrottled("ad-act:" + target,
                                        "blocked ad activity: " + target);
                                return null;
                            }
                            if (isMinigameActivity(target)) {
                                ctx.log.hitThrottled("minigame:" + target,
                                        "blocked minigame: " + target);
                                return null;
                            }
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

    // ----------------------------------------------------------- 开屏广告拦截

    /**
     * 开屏广告双层拦截。
     *
     * <p>反编译确认 UC 的开屏走两条链：</p>
     * <ol>
     *   <li>广告内容由 Noah 渲染——{@code SplashAd.showSplashAd(ViewGroup)} /
     *       {@code showTopViewAd(...)} 把广告塞进容器。掐掉这两个方法（不 proceed），
     *       广告内容就不会出现；</li>
     *   <li>承载窗口是 {@code SplashWindow}（继承 FrameLayout），由
     *       {@code SplashWindowController} 用 {@code new SplashWindow(...)} 创建并挂到
     *       主界面窗口。hook 它的 {@code onAttachedToWindow}：窗口一挂上就
     *       {@code setVisibility(GONE)}，即便有残留也看不见，主界面立刻露出。</li>
     * </ol>
     *
     * <p>两头都做是有意的冗余：只掐内容会留一个空白窗口占屏；只 GONE 窗口则 SDK 可能
     * 仍在后台拉取/上报广告。合起来既省流量又干净。</p>
     */
    private void installSplashBlock(Context ctx) {
        ctx.feature(FEAT_SPLASH, () -> {
            ClassLoader cl = ctx.classLoader();
            boolean any = false;

            // 1. 掐 Noah 开屏渲染：showSplashAd / showTopViewAd 直接吞掉。
            Class<?> splashAd = Reflect.findClass(cl, NOAH_SPLASH_AD);
            if (splashAd != null) {
                int blocked = 0;
                for (Method m : splashAd.getDeclaredMethods()) {
                    String name = m.getName();
                    if (("showSplashAd".equals(name) || "showTopViewAd".equals(name))
                            && m.getParameterCount() >= 1
                            && android.view.ViewGroup.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        ctx.hooks.intercept(FEAT_SPLASH + "/noah/" + name + "/" + m.getParameterCount(),
                                m, chain -> {
                                    ctx.log.hitThrottled("splash-noah:" + name,
                                            "blocked Noah splash render: " + name);
                                    // 不 proceed：广告内容不渲染。void 方法，返回 null 安全。
                                    return null;
                                });
                        blocked++;
                    }
                }
                if (blocked > 0) {
                    any = true;
                    ctx.log.info("splash: Noah render block installed (" + blocked + " method(s))");
                }
            } else {
                ctx.log.warn("splash: " + NOAH_SPLASH_AD + " not found (Noah maybe lazy-loaded)");
            }

            // 2. SplashWindow 一挂上窗口就 GONE。
            Class<?> splashWindow = Reflect.findClass(cl, SPLASH_WINDOW);
            if (splashWindow != null) {
                Method onAttached = null;
                Class<?> c = splashWindow;
                // onAttachedToWindow 定义在 android.view.View 上；沿继承链找到它。
                while (c != null && onAttached == null) {
                    for (Method m : c.getDeclaredMethods()) {
                        if ("onAttachedToWindow".equals(m.getName())
                                && m.getParameterCount() == 0) {
                            onAttached = m;
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }
                if (onAttached != null) {
                    ctx.hooks.intercept(FEAT_SPLASH + "/window", onAttached, chain -> {
                        Object r = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof View) {
                                ((View) self).setVisibility(View.GONE);
                                ctx.log.hitThrottled("splash-window", "hid SplashWindow");
                            }
                        } catch (Throwable ignored) {
                        }
                        return r;
                    });
                    any = true;
                    ctx.log.info("splash: SplashWindow hide installed");
                }
            } else {
                ctx.log.warn("splash: " + SPLASH_WINDOW + " not found");
            }

            if (!any) {
                throw new NoSuchMethodException(
                        "neither Noah SplashAd nor SplashWindow could be hooked");
            }
        });
    }

    // ----------------------------------------------------------- 面板连点入口

    /**
     * 在设置页底部版本号那行「UC浏览器 V19.0.0.1536」上做连点入口。
     *
     * <p>UC 没有 QQ 音乐那样现成的诊断入口区域，而版本号 footer 又藏在混淆的 list 类里，
     * 硬找类名易随版本失效。所以改用<b>文字内容匹配</b>：hook 框架类
     * {@code TextView.setText(CharSequence)}，只在文字以「UC浏览器」开头时才处理
     * （其余瞬间放行，不影响性能），给那个 TextView 挂上点击监听——连点 7 次弹面板。</p>
     *
     * <p>这样完全绕开 UC 的混淆类名，只依赖稳定的版本号文案与 Android 框架 API，
     * 跨 UC 版本都能用。</p>
     *
     * <p>连点要「连续」：两次点击间隔超过 {@link #VER_CLICK_WINDOW_MS} 就重新计数，
     * 避免用户平时误触逐渐累积到 7。</p>
     */
    private void installAboutEntry(Context ctx) {
        ctx.feature(FEAT_ABOUT_ENTRY, () -> {
            Method setText = Reflect.method(
                    android.widget.TextView.class, "setText", CharSequence.class);
            ctx.hooks.intercept(FEAT_ABOUT_ENTRY, setText, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object arg = chain.getArg(0);
                    if (self instanceof android.widget.TextView && arg instanceof CharSequence) {
                        String s = arg.toString();
                        // 只认版本号那行；绝大多数 setText 在这里立即返回，热路径开销极小。
                        if (s.startsWith(VERSION_ROW_PREFIX) && s.contains("V")) {
                            attachVersionClick(ctx, (android.widget.TextView) self);
                        }
                    }
                } catch (Throwable t) {
                    // 入口是增强项，任何异常都不能影响 setText 本身
                    ctx.log.hitThrottled("about-entry-err", "about entry hook error: " + t);
                }
                return result;
            });
            ctx.log.info("about entry installed (version-row text match)");
        });
    }

    /**
     * 给版本号 TextView 挂连点监听。幂等：同一个 View 只挂一次。
     *
     * <p>用 {@link java.util.WeakHashMap} 记已挂过的 View 而不用 {@code setTag(int,Object)}：
     * 后者要求 key 是应用定义的资源 id，随便传 int 会抛 IllegalArgumentException。
     * WeakHashMap 不阻止 View 回收，无内存泄漏。</p>
     */
    private void attachVersionClick(Context ctx, android.widget.TextView tv) {
        // 已挂过就不重复（setText 可能被调多次）
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(tv)) {
                return;
            }
            ATTACHED.put(tv, Boolean.TRUE);
        }
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
            try {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - verLastClickAt > VER_CLICK_WINDOW_MS) {
                    VER_CLICKS.set(0); // 隔太久，重新计
                }
                verLastClickAt = now;
                int c = VER_CLICKS.incrementAndGet();
                ctx.log.info("version-row click " + c + "/" + VER_CLICK_TARGET);
                if (c >= VER_CLICK_TARGET) {
                    VER_CLICKS.set(0);
                    UcBrowserPanel.showPanel(v.getContext(), this.prefs, ctx.log);
                    ctx.log.hit("uc panel opened by version 7-clicks");
                }
            } catch (Throwable t) {
                ctx.log.error("version click handler failed", t);
            }
        });
    }

    /** 已挂过连点监听的版本号 TextView（弱引用，不阻止回收）。 */
    private static final java.util.WeakHashMap<android.widget.TextView, Boolean> ATTACHED =
            new java.util.WeakHashMap<>();

    // ----------------------------------------------------------- 推送保活拦截

    /**
     * 拦截推送/保活服务的启动。<b>受面板开关 {@code uc.block.push} 控制，默认关。</b>
     *
     * <p>hook {@code ContextWrapper.startService(Intent)} 与 {@code bindService(...)}：
     * 它们是所有服务启动的收敛点。目标 Service 类名命中 {@link #PUSH_PREFIXES} 且用户
     * 勾了开关时，{@code startService} 返回 null（等价「服务没起来」）、{@code bindService}
     * 返回 false（绑定失败）——都是调用方本就要处理的正常返回，不会崩。</p>
     *
     * <p><b>只在主进程生效的局限</b>：推送重头在 {@code :push}/{@code :channel} 子进程，
     * 本模块只 hook 主进程，所以这里拦的是主进程主动拉起推送/保活服务的部分。子进程侧的
     * 常驻拦截需要放宽注入进程，属后续项。即便如此，主进程这层已能挡掉相当一部分主动唤醒。</p>
     *
     * <p>该 feature 本身总是安装（hook 挂上），是否真正拦截由开关在<b>运行时</b>决定，
     * 这样用户在面板里勾选后无需重装模块即可生效（但已运行的推送需重启 UC）。</p>
     */
    private void installPushBlock(Context ctx) {
        ctx.feature(FEAT_PUSH, () -> {
            Class<?> ctxWrapper = android.content.ContextWrapper.class;

            // startService(Intent) -> 命中且开关开时返回 null
            Method startService = Reflect.method(ctxWrapper, "startService", Intent.class);
            ctx.hooks.intercept(FEAT_PUSH + "/start", startService, chain -> {
                Object arg = chain.getArg(0);
                if (arg instanceof Intent && isPushBlocked(componentOf((Intent) arg))) {
                    ctx.log.hitThrottled("push-start:" + componentOf((Intent) arg),
                            "blocked push startService: " + componentOf((Intent) arg));
                    return null;
                }
                return chain.proceed();
            });

            // bindService(Intent, ServiceConnection, int) -> 命中且开关开时返回 false
            int bound = 0;
            for (Method m : Reflect.methodsNamed(ctxWrapper, "bindService")) {
                final int intentIdx = indexOfIntent(m);
                if (intentIdx < 0 || m.getReturnType() != boolean.class) {
                    continue;
                }
                ctx.hooks.intercept(FEAT_PUSH + "/bind/" + m.getParameterCount(), m, chain -> {
                    Object arg = chain.getArg(intentIdx);
                    if (arg instanceof Intent && isPushBlocked(componentOf((Intent) arg))) {
                        ctx.log.hitThrottled("push-bind:" + componentOf((Intent) arg),
                                "blocked push bindService: " + componentOf((Intent) arg));
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
                bound++;
            }
            ctx.log.info("push block installed (start + " + bound + " bind overload(s)); "
                    + "runtime-gated by panel switch");
        });
    }

    /**
     * 目标 Service 是否应被拦——仅当面板开关开启且类名命中推送/保活前缀。
     *
     * <p>{@code prefs} 为 null 或开关未勾一律返回 false：这是默认关的增强项，
     * 读不到就当没开，绝不擅自拦截，以免误伤正常功能。</p>
     */
    private boolean isPushBlocked(String className) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null || className == null) {
            return false;
        }
        if (!p.get(UcBrowserPanel.K_PUSH, false)) {
            return false;
        }
        for (String prefix : PUSH_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------- 统计/游戏/Provider 拦截

    /** 游戏中心 Activity 类名前缀（主人不要游戏）。 */
    private static final String[] MINIGAME_ACTIVITY_PREFIXES = {
            "com.uc.minigame.activity.",
            "com.uc.minigame.",
    };

    /** 该 Activity 类名是否属于游戏中心入口。 */
    private boolean isMinigameActivity(String className) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null || className == null) return false;
        if (!p.get(UcBrowserPanel.K_MINIGAME, true)) return false;
        for (String pre : MINIGAME_ACTIVITY_PREFIXES) {
            if (className.startsWith(pre)) return true;
        }
        return false;
    }

    /**
     * 统计/上报类 Service 类名前缀（K_STAT 控制）。
     * 主人当纯网盘用，这些后台发包都禁。
     */
    private static final String[] STAT_SERVICE_PREFIXES = {
            "com.uc.browser.statis.",
            "com.uc.ubox.",
            "com.uc.application.stat.",
            "com.uc.base.stat.",
            "com.uc.framework.stat.",
    };

    /** 该 Service 类名是否属于统计/上报类（K_STAT 控制）。 */
    private boolean isStatService(String className) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null || className == null) return false;
        if (!p.get(UcBrowserPanel.K_STAT, true)) return false;
        for (String pre : STAT_SERVICE_PREFIXES) {
            if (className.startsWith(pre)) return true;
        }
        return false;
    }

    /**
     * 穿山甲广告 Provider 的 URI 前缀（K_STAT 控制）—— 命中直接返回空 Cursor，
     * 广告拿不到模板数据自然不会渲染。{@code layerType=1} 是单进程广告。
     */
    private static final String[] STAT_PROVIDER_PREFIXES = {
            "content://com.UCMobile.TTMultiProvider/",
    };

    /**
     * 装 K_STAT 阻断：Service 启动/绑定拦 + 穿山甲 Provider query 拦。
     */
    private void installStatBlock(Context ctx) {
        ctx.feature(FEAT_STAT, () -> {
            Class<?> ctxWrapper = android.content.ContextWrapper.class;

            // startService
            Method startService = Reflect.method(ctxWrapper, "startService", Intent.class);
            if (startService != null) {
                ctx.hooks.intercept(FEAT_STAT + "/start", startService, chain -> {
                    Object arg = chain.getArg(0);
                    if (arg instanceof Intent) {
                        String comp = componentOf((Intent) arg);
                        if (isStatService(comp)) {
                            ctx.log.hitThrottled("stat-start:" + comp,
                                    "blocked stat startService: " + comp);
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
            // bindService
            int bound = 0;
            for (Method m : Reflect.methodsNamed(ctxWrapper, "bindService")) {
                final int intentIdx = indexOfIntent(m);
                if (intentIdx < 0 || m.getReturnType() != boolean.class) continue;
                ctx.hooks.intercept(FEAT_STAT + "/bind/" + m.getParameterCount(), m, chain -> {
                    Object arg = chain.getArg(intentIdx);
                    if (arg instanceof Intent) {
                        String comp = componentOf((Intent) arg);
                        if (isStatService(comp)) {
                            ctx.log.hitThrottled("stat-bind:" + comp,
                                    "blocked stat bindService: " + comp);
                            return Boolean.FALSE;
                        }
                    }
                    return chain.proceed();
                });
                bound++;
            }

            // ContentResolver.query：命中穿山甲 Provider 直接返回空 Cursor
            // 用 methodsNamed 找所有 query 重载（不同 Android 版本签名不同）
            try {
                Class<?> cr = android.content.ContentResolver.class;
                int queryN = 0;
                for (Method m : Reflect.methodsNamed(cr, "query")) {
                    if (m.getParameterTypes().length == 0) continue;
                    if (!android.net.Uri.class.equals(m.getParameterTypes()[0])) continue;
                    ctx.hooks.intercept(FEAT_STAT + "/cp/query/" + m.getParameterCount(), m, chain -> {
                        try {
                            com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                            if (p != null && p.get(UcBrowserPanel.K_STAT, true)) {
                                Object uri = chain.getArg(0);
                                if (uri != null) {
                                    String s = uri.toString();
                                    for (String pre : STAT_PROVIDER_PREFIXES) {
                                        if (s.startsWith(pre)) {
                                            ctx.log.hitThrottled("stat-cp:" + s,
                                                    "blocked tt provider query: " + s);
                                            return null;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    queryN++;
                }
                if (queryN > 0) {
                    ctx.log.info("stat: ContentResolver.query hooked (" + queryN + " overload(s))");
                }
            } catch (Throwable t) {
                ctx.log.error("stat block ContentResolver hook failed", t);
            }

            ctx.log.info("stat block installed (start + " + bound + " bind + tt provider); "
                    + "runtime-gated by K_STAT");
        });
    }

    // ----------------------------------------------------------- 信息流/小说广告 View 隐藏

    /**
     * UC 自家信息流/小说广告 View 的类名前缀。
     *
     * <p>这些包路径下的所有 View（extends View/ViewGroup 子类）命中后会被 GONE。
     * 包含：</p>
     * <ul>
     *   <li>Noah 信息流广告卡片（com.uc.application.ad.noah.*）</li>
     *   <li>UC 浏览器自家广告框架（com.uc.browser.advertisement.*）</li>
     *   <li>信息流广告 View（com.uc.application.infoflow.admaterials.*）</li>
     *   <li>UC 小说内广告（com.uc.application.novel.ad.* 与 novel.views 下 ad 相关）</li>
     * </ul>
     *
     * <p><b>关键过滤</b>：{@code .application.ad.} 前缀要用带点号的精确边界——不然
     * 会误伤 {@code com.uc.application.ad.noah}（正确）和 {@code com.uc.application.adapter}（错误）。
     * 这里我们用 {@code startsWith}，所以前缀要写完整路径段。</p>
     */
    private static final String[] AD_VIEW_PREFIXES = {
            // Noah 信息流广告卡片（UC 浏览器内嵌的 Noah SDK 视图层）
            "com.uc.application.ad.noah.",
            // UC 浏览器自家广告框架
            "com.uc.browser.advertisement.",
            // 信息流广告素材 View（首页/频道里嵌入的广告卡）
            "com.uc.application.infoflow.admaterials.",
            // 小说内广告 View（书架 + 阅读页 + 小说激励视频福利）
            "com.uc.application.novel.ad.",
            // 即刻视频广告：含 AdWebActivity / PortraitVideoActivity 的 View 子层
            // （Activity 已被 FEAT_AD_ACTIVITY 拦，View 也一并隐藏）
            "com.uc.browser.advertisement.jilivideo.view.",
            "com.uc.browser.advertisement.jilivideo.component.",

            // ---- 主人要"当纯网盘用"：把所有非网盘的信息流/视频/小说/游戏/统计 View 全禁 ----

            // Noah SDK 整套（含广告 / 视频落地页 / WebView 业务）
            "com.noah.api.",
            "com.noah.sdk.",
            "com.noah.remote.",
            // UC 浏览器内嵌信息流（含趣头条段子）
            "com.uc.application.infoflow.",
            "com.uc.application.browserinfoflow.",
            // UC 内置视频播放（主人不要视频）
            "com.uc.apollo.",
            "com.uc.browser.media.",
            // UC 游戏中心（主人不要游戏）
            "com.uc.minigame.",
            // UC 自家埋点（UBox 卧龙埋点）
            "com.uc.ubox.",
            // UC 浏览器即时视频/小窗
            "com.uc.browser.business.videoflow.",
    };

    /**
     * 「我的」页面多入口净化：藏掉主人不要的"我的"页面里的所有非网盘入口
     * （受 K_MY_PAGE 控制，默认开）。
     *
     * <p>包含：</p>
     * <ul>
     *   <li>菜单栏「常用 AI 应用」+「UC 松鼠大战」+ 其他非网盘入口</li>
     *   <li>我的页面「芭芭农场 / 福利猪领元宝」养成入口</li>
     *   <li>我的页面「我的书架」</li>
     *   <li>我的页面「我的直播 / 我的游戏」</li>
     * </ul>
     *
     * <p><b>不能拦</b> {@code com.uc.framework.e1} 整套 —— 那是菜单 panel
     * 自身的渲染器（每一项都是一个 e1 子类），拦了菜单 panel 就打不开了。
     * 真正的"具体入口"是 {@code com.uc.framework.ui.widget.panel.menupanel.MenuInfo$GridViewEx}，
     * 或 panel 里的 {@code menupanel.d} 等子 View —— 这些按精确类名拦。</p>
     */
    private static final String[] MY_PAGE_VIEW_PREFIXES = {
            // 侧滑菜单 panel item 容器
            "com.uc.framework.ui.widget.panel.menupanel.d",
            // minigame 容器（含「UC 松鼠大战」tab 入口）
            "com.uc.application.minigame.",
            // 小说全套：阅读页、书架、福利猪、番外等（主人不用小说）
            "com.uc.application.novel.",
            // 「我的」页所有子 View：包括「我的书架」「我的直播」「我的游戏」「玩芭芭农场/福利猪领元宝」入口
            // usertab.* 是"我的"页的子 View 渲染器（包括卡片容器、标题、内容区等）
            // usertab.guide.* 是引导弹窗，不拦（可能用户需要看）
            "com.uc.browser.core.homepage.usertab.",
            // 语音/音频播放 UI（可能是直播/游戏/小说的封面控件）
            "com.uc.application.audio.",
            // 直播互动 UI（ULive 相关 ——「我的直播」入口）
            "com.uc.ulive.load.PlayerBase",
    };

    /**
     * 菜单栏 / 我的页里精确要拦的 View 类名（不是前缀，是完整类名）。
     * 来自 dump 抓到的具体"入口 item"类。
     */
    private static final String[] MY_PAGE_VIEW_EXACT = {
            // 侧滑菜单 item 渲染器的"具体入口"子类
            "com.uc.framework.e1$b",
            "com.uc.framework.e1$f",
            "com.uc.framework.e1$h",
            "com.uc.framework.e1$i",
    };

    /**
     * 网盘核心白名单：这些类名永远不能被拦，拦了就废了。
     *
     * <p>owner 把它作为「不能动的关键 View」清单，主人在面板里能看到。
     * 维护原则：<b>宁可漏拦广告，也别误伤网盘功能</b>。</p>
     */
    private static final String[] CLOUDDRIVE_KEEP = {
            // 网盘主页（Flutter 渲染的也要包含）
            "com.uc.business.clouddrive.",
            // 账户中心（登录用，主人下载需要登录态）
            "com.uc.browser.business.account.",
            // WebView 容器
            "com.uc.browser.webwindow.",
            "com.uc.sdk_glue.webkit.",
            // Flutter 渲染容器（先不拦，等主人在 Flutter 页里操作后判断是否网盘相关）
            "com.uc.application.flutter.FlutterWindow",
            // 一些基础系统框架（auto.theme 主题、基础控件）
            "com.uc.framework.auto.theme.",
            "com.uc.framework.ui.widget.base.",
            "com.uc.framework.ui.widget.TabPager",
            "com.uc.framework.ui.widget.QuickTextView",
            "com.uc.framework.ui.widget.TextView",
            "com.uc.framework.ui.widget.p",
            "com.uc.framework.ui.widget.v",
            "com.uc.framework.ui.widget.a0",
            "com.uc.framework.ui.widget.i0",
            // 设置相关（字号等）
            "com.uc.browser.core.setting.",
            // 侧滑菜单 panel 自身（不能拦整 panel，拦了就打不开菜单）
            "com.uc.framework.ui.widget.panel.menupanel.",
            // 菜单 item 渲染器基类 e1（具体的 $b/$f/$h/$i 走精确匹配）
            "com.uc.framework.e1",
    };

    /**
     * UC 小说相关的具体广告 View 类名（精确匹配）—— 已知由反编译确认的
     * "小说内广告"View 入口。
     */
    private static final String[] AD_VIEW_EXACT = {
            // 书架推荐广告 View（反编译确认：BookShelfRecommendNoahAdItemView extends FrameLayout，
            // 内部用 Noah NativeAdView 渲染广告）
            "com.uc.application.novel.views.v2021.bookshelf.ad.BookShelfRecommendNoahAdItemView",
            // Noah 已知广告卡容器
            "com.uc.application.ad.noah.infoflow.nativead.NoahCouponsLayout",
            "com.uc.application.ad.noah.infoflow.nativead.NoahLiveBarLayout",
            // 信息流关键广告 View（前缀已覆盖，精确匹配提高日志可读性）
            "com.uc.application.infoflow.widget.ucvfull.noahgame.VfFullAdGameView",
            "com.uc.application.infoflow.widget.video.ad.InfoFlowAdBarrageWidget",
            "com.uc.application.infoflow.widget.generalcard.ad.InfoFlowAdStaticDanmakuItemView",
            "com.uc.application.infoflow.widget.bottomdivider.AdBottomDivider",
            "com.uc.application.infoflow.admaterials.novel.NovelAdMaterialFooterView",
    };

    /**
     * 该 View 类名是否属于 UC 浏览器内嵌的广告 View（受 K_FEEDAD/K_NOVELAD/K_MY_PAGE 开关控制）。
     *
     * <p><b>网盘白名单</b>：{@link #CLOUDDRIVE_KEEP} 内的类永远不拦。宁可漏拦广告
     * 也别误伤网盘功能。</p>
     */
    private boolean isAdView(String className) {
        if (className == null) return false;
        // 1) 网盘白名单：永远不拦
        for (String keep : CLOUDDRIVE_KEEP) {
            if (className.startsWith(keep)) return false;
        }
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null) return false;
        // 2) 信息流广告开关
        if (p.get(UcBrowserPanel.K_FEEDAD, false)) {
            for (String exact : AD_VIEW_EXACT) {
                if (className.equals(exact)) return true;
            }
            for (String prefix : AD_VIEW_PREFIXES) {
                if (className.startsWith(prefix)) return true;
            }
        }
        // 3) 小说内广告开关
        if (p.get(UcBrowserPanel.K_NOVELAD, false)) {
            if (className.startsWith("com.uc.application.novel.")
                    || className.startsWith("com.uc.application.novel.ad.")) {
                return true;
            }
        }
        // 4) 「我的」页面多入口净化（菜单栏 + 我的页面）
        if (p.get(UcBrowserPanel.K_MY_PAGE, true)) {
            for (String exact : MY_PAGE_VIEW_EXACT) {
                if (className.equals(exact)) return true;
            }
            for (String prefix : MY_PAGE_VIEW_PREFIXES) {
                if (className.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /**
     * 3 层广告 View 隐藏（与 QQ 音乐同套路）：
     *
     * <ol>
     *   <li><b>addView</b> — 广告 View 一旦被 addView 进来立刻 GONE；</li>
     *   <li><b>onAttachedToWindow</b> — 漏网之鱼：广告 View 一挂上窗口立刻 GONE；</li>
     *   <li><b>lock-gone</b> — 终极保险：被拦类若尝试 setVisibility(VISIBLE)，吞掉调用；</li>
     * </ol>
     *
     * <p>3 层足以覆盖 UC 的实际广告渲染路径（QQ 音乐需要 4 层是因为有边角场景，
     * UC 这里先用 3 层真机验证，不够再加）。</p>
     */
    private void installAdViewHide(Context ctx) {
        ctx.feature(FEAT_AD_VIEW, () -> {
            // 类 -> 是否广告 View（用类对象做键，避免每次做字符串前缀匹配）
            final java.util.Map<Class<?>, Boolean> cache = new java.util.concurrent.ConcurrentHashMap<>();

            // (1) addView hook
            Method addView = Reflect.method(ViewGroup.class, "addView",
                    View.class, int.class, ViewGroup.LayoutParams.class);
            ctx.hooks.intercept(FEAT_AD_VIEW + "/add", addView, chain -> {
                try {
                    Object child = chain.getArg(0);
                    if (child instanceof View) {
                        View v = (View) child;
                        Class<?> c = v.getClass();
                        Boolean isAd = cache.get(c);
                        if (isAd == null) {
                            isAd = isAdView(c.getName());
                            cache.put(c, isAd);
                        }
                        if (isAd) {
                            v.setVisibility(View.GONE);
                            ctx.log.hitThrottled("view:" + c.getName(),
                                    "hid ad view: " + c.getName());
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });

            // (2) onAttachedToWindow hook
            Method onAttached = null;
            for (Method m : View.class.getDeclaredMethods()) {
                if ("onAttachedToWindow".equals(m.getName()) && m.getParameterCount() == 0) {
                    onAttached = m;
                    break;
                }
            }
            if (onAttached != null) {
                ctx.hooks.intercept(FEAT_AD_VIEW + "/attached", onAttached, chain -> {
                    Object r = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            View v = (View) self;
                            String name = v.getClass().getName();
                            if (isAdView(name)) {
                                v.setVisibility(View.GONE);
                                ctx.log.hitThrottled("attached:" + name,
                                        "hid (attached) " + name);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
            }

            // (3) lock-gone：被拦类尝试 setVisibility(VISIBLE) 时吞掉
            Method setVis = null;
            for (Method m : View.class.getDeclaredMethods()) {
                if ("setVisibility".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class) {
                    setVis = m;
                    break;
                }
            }
            if (setVis != null) {
                ctx.hooks.intercept(FEAT_AD_VIEW + "/lockgone", setVis, chain -> {
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof Integer && ((Integer) arg) == View.VISIBLE) {
                            Object self = chain.getThisObject();
                            if (self instanceof View) {
                                String name = self.getClass().getName();
                                if (isAdView(name)) {
                                    ((View) self).setVisibility(View.GONE);
                                    ctx.log.hitThrottled("lockgone:" + name,
                                            "locked GONE " + name);
                                    return null;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
            ctx.log.info("ad-view hide installed (add + attached + lock-gone); "
                    + "runtime-gated by K_FEEDAD / K_NOVELAD");
        });
    }

    // ----------------------------------------------------------- UC 全栈活动 dump

    /**
     * View dump 过滤：只对可能跟广告/UC 业务相关的包名前缀打点。其它 View 全部沉默，
     * 避免 logcat 被系统级 View（status bar、input view 等）冲爆。
     */
    private static final String[] VIEW_DUMP_PREFIXES = {
            "com.uc.", "com.noah.", "com.qq.e.", "com.baidu.", "com.bytedance.",
            "com.alimm.", "com.huawei.hms.ads.", "com.uc.browser.advertisement.",
            "com.uc.application.ad.", "com.uc.application.infoflow.",
            "com.uc.application.novel.", "com.uc.base.push.", "com.uc.minigame.",
            "com.uc.business.", "com.uc.framework.", "com.uc.plugin.",
            "com.uc.widget.", "com.uc.view.",
    };

    /**
     * 是否要打点这个 View 类。
     */
    private static boolean shouldDumpView(String className) {
        if (className == null) return false;
        for (String p : VIEW_DUMP_PREFIXES) {
            if (className.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * View dump 节流：同一 View 类名 5 秒内只打首条 + 汇总。
     * 否则会冲爆 logcat（UC 一次刷新能 addView 几百个）。
     *
     * <p>返回值为：</p>
     * <ul>
     *   <li>{@code null}：本次不打点；</li>
     *   <li>普通类名：本次打首条；</li>
     *   <li>{@code "原类名 [xN]"}：本次打汇总（窗口结束）。</li>
     * </ul>
     */
    private static final java.util.Map<String, long[]> VIEW_DUMP_THROTTLE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long VIEW_DUMP_WINDOW_MS = 5000;

    private static String throttleViewDump(String className) {
        long now = android.os.SystemClock.elapsedRealtime();
        long[] st = VIEW_DUMP_THROTTLE.computeIfAbsent(className, k -> new long[]{0, 0});
        // st[0] = 窗口开始时间；st[1] = 窗口内累计次数
        if (st[0] == 0) {
            st[0] = now;
            st[1] = 1;
            return className; // 首条
        }
        st[1]++;
        if (now - st[0] >= VIEW_DUMP_WINDOW_MS) {
            long cnt = st[1];
            st[0] = now;
            st[1] = 1;
            return className + " [汇总 x" + cnt + "]";
        }
        return null; // 窗口内、还没到汇总时机
    }

    /**
     * 把 Intent 的关键字段压成可读字符串。
     */
    private static String formatIntent(Intent intent) {
        if (intent == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        if (intent.getComponent() != null) {
            sb.append(intent.getComponent().getClassName());
        } else if (intent.getAction() != null) {
            sb.append("act=").append(intent.getAction());
        } else {
            sb.append("(no-component)");
        }
        if (intent.getDataString() != null) {
            String data = intent.getDataString();
            sb.append(" data=").append(data.length() > 80 ? data.substring(0, 80) + "…" : data);
        }
        if (intent.getType() != null) {
            sb.append(" type=").append(intent.getType());
        }
        return sb.toString();
    }

    /**
     * UC 全栈活动 dump 装具：把所有 Activity/Service/Receiver/Provider/View/WebView/
     * Fragment/Dialog 关键打点全装上，输出到 logcat（tag = LiTianSuo，prefix = [UCDump]）。
     *
     * <p>主人要走一遍 UC，看 logcat 一次能拿全所有组件类名。logcat 命令：</p>
     * <pre>
     *   adb logcat -s LiTianSuo:I  | grep UCDump
     * </pre>
     *
     * <p>设计取舍：</p>
     * <ul>
     *   <li>Activity/Service/Receiver/Provider 频次低 → 全部打点；</li>
     *   <li>View.addView 频次极高 → 按包名前缀过滤（只保留可能广告/UC 业务相关）；</li>
     *   <li>WebView.loadUrl 频次高（每次网页跳转都来一次）→ 不带过滤，每条都打但带节流；
     *       只记录 URL 的 host + path 前 60 字符，避免冲爆；</li>
     *   <li>logcat 节流：用 {@code hitThrottled} 同款机制，5s 窗口内同类只打一次。</li>
     * </ul>
     */
    private void installDump(Context ctx) {
        ctx.feature(FEAT_DUMP, () -> {
            com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
            if (p == null || !p.get(UcBrowserPanel.K_DUMP, false)) {
                ctx.log.info("dump: switch off, skipping");
                return;
            }
            try {
                // ---- 1) Activity 启动：所有 execStartActivity 重载全打点 ----
                int actN = 0;
                for (Method m : Reflect.methodsNamed(
                        android.app.Instrumentation.class, "execStartActivity")) {
                    final int intentIndex = indexOfIntent(m);
                    if (intentIndex < 0) continue;
                    ctx.hooks.intercept(FEAT_DUMP + "/act/" + m.getParameterCount(), m, chain -> {
                        try {
                            Object arg = chain.getArg(intentIndex);
                            if (arg instanceof Intent) {
                                Intent intent = (Intent) arg;
                                ctx.log.info("[UCDump] Activity " + formatIntent(intent));
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    actN++;
                }

                // ---- 2) Service 启动 ----
                Class<?> ctxWrapper = android.content.ContextWrapper.class;
                Method startService = Reflect.method(ctxWrapper, "startService", Intent.class);
                if (startService != null) {
                    ctx.hooks.intercept(FEAT_DUMP + "/start", startService, chain -> {
                        try {
                            Object arg = chain.getArg(0);
                            if (arg instanceof Intent) {
                                ctx.log.info("[UCDump] startService " + formatIntent((Intent) arg));
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
                // bindService 4 个重载
                int bindN = 0;
                for (Method m : Reflect.methodsNamed(ctxWrapper, "bindService")) {
                    final int intentIdx = indexOfIntent(m);
                    if (intentIdx < 0) continue;
                    ctx.hooks.intercept(FEAT_DUMP + "/bind/" + m.getParameterCount(), m, chain -> {
                        try {
                            Object arg = chain.getArg(intentIdx);
                            if (arg instanceof Intent) {
                                ctx.log.info("[UCDump] bindService " + formatIntent((Intent) arg));
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    bindN++;
                }
                // stopService
                Method stopService = Reflect.method(ctxWrapper, "stopService", Intent.class);
                if (stopService != null) {
                    ctx.hooks.intercept(FEAT_DUMP + "/stop", stopService, chain -> {
                        try {
                            Object arg = chain.getArg(0);
                            if (arg instanceof Intent) {
                                ctx.log.info("[UCDump] stopService " + formatIntent((Intent) arg));
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }

                // ---- 3) BroadcastReceiver：register + sendBroadcast ----
                for (Method m : Reflect.methodsNamed(ctxWrapper, "registerReceiver")) {
                    ctx.hooks.intercept(FEAT_DUMP + "/reg/" + m.getParameterCount(), m, chain -> {
                        try {
                            // 第一参数是 BroadcastReceiver
                            Object recv = chain.getArg(0);
                            if (recv != null) {
                                ctx.log.info("[UCDump] registerReceiver " + recv.getClass().getName());
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
                for (Method m : Reflect.methodsNamed(ctxWrapper, "sendBroadcast")) {
                    final int intentIdx = indexOfIntent(m);
                    if (intentIdx < 0) continue;
                    ctx.hooks.intercept(FEAT_DUMP + "/bc/" + m.getParameterCount(), m, chain -> {
                        try {
                            Object arg = chain.getArg(intentIdx);
                            if (arg instanceof Intent) {
                                ctx.log.info("[UCDump] broadcast " + formatIntent((Intent) arg));
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }

                // ---- 4) ContentProvider：query/insert/update/delete ----
                Class<?> cr = android.content.ContentResolver.class;
                String[] cpMethods = {"query", "insert", "update", "delete"};
                for (String name : cpMethods) {
                    for (Method m : Reflect.methodsNamed(cr, name)) {
                        ctx.hooks.intercept(FEAT_DUMP + "/cp/" + name + "/" + m.getParameterCount(),
                                m, chain -> {
                            try {
                                Object uri = chain.getArg(0);
                                ctx.log.info("[UCDump] ContentResolver." + name
                                        + " " + (uri == null ? "?" : uri.toString()));
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                    }
                }

                // ---- 5) View.addView：按包名前缀过滤 ----
                Method addView = Reflect.method(ViewGroup.class, "addView",
                        View.class, int.class, ViewGroup.LayoutParams.class);
                if (addView != null) {
                    ctx.hooks.intercept(FEAT_DUMP + "/add", addView, chain -> {
                        try {
                            Object child = chain.getArg(0);
                            if (child instanceof View) {
                                String name = child.getClass().getName();
                                String throttled = shouldDumpView(name) ? throttleViewDump(name) : null;
                                if (throttled != null) {
                                    // 顺手看是不是已经被 ad-view 拦了
                                    boolean blocked = isAdView(name);
                                    ctx.log.info("[UCDump] addView" + (blocked ? "[BLOCKED]" : "")
                                            + " " + throttled);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }

                // ---- 6) WebView.loadUrl ----
                try {
                    Class<?> wv = Class.forName("android.webkit.WebView");
                    Method loadUrl = Reflect.method(wv, "loadUrl", String.class);
                    if (loadUrl != null) {
                        ctx.hooks.intercept(FEAT_DUMP + "/web/loadUrl", loadUrl, chain -> {
                            try {
                                Object url = chain.getArg(0);
                                if (url instanceof String) {
                                    String u = (String) url;
                                    ctx.log.info("[UCDump] WebView.loadUrl " + u);
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                    }
                    Method loadData = Reflect.method(wv, "loadData",
                            String.class, String.class, String.class);
                    if (loadData != null) {
                        ctx.hooks.intercept(FEAT_DUMP + "/web/loadData", loadData, chain -> {
                            try {
                                Object data = chain.getArg(0);
                                if (data instanceof String) {
                                    String d = (String) data;
                                    String preview = d.length() > 100
                                            ? d.substring(0, 100) + "…" : d;
                                    ctx.log.info("[UCDump] WebView.loadData " + preview);
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                    }
                    Method loadUrl2 = Reflect.method(wv, "loadUrl",
                            String.class, java.util.Map.class);
                    if (loadUrl2 != null) {
                        ctx.hooks.intercept(FEAT_DUMP + "/web/loadUrl2", loadUrl2, chain -> {
                            try {
                                Object url = chain.getArg(0);
                                if (url instanceof String) {
                                    ctx.log.info("[UCDump] WebView.loadUrl* " + url);
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                    }
                } catch (Throwable t) {
                    ctx.log.error("dump: WebView hooks failed", t);
                }

                // ---- 7) Dialog.show ----
                Class<?> dlg = android.app.Dialog.class;
                Method show = Reflect.method(dlg, "show");
                if (show != null) {
                    ctx.hooks.intercept(FEAT_DUMP + "/dialog", show, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof android.app.Dialog) {
                                android.app.Dialog d = (android.app.Dialog) self;
                                String owner = d.getOwnerActivity() != null
                                        ? d.getOwnerActivity().getClass().getName() : "?";
                                ctx.log.info("[UCDump] Dialog.show owner=" + owner
                                        + " cls=" + self.getClass().getName());
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }

                // ---- 8) AlertDialog.Builder 的 create() 也打点（用来发现系统弹窗）----
                try {
                    Class<?> adBuilder = Class.forName("android.app.AlertDialog$Builder");
                    Method create = Reflect.method(adBuilder, "create");
                    if (create != null) {
                        ctx.hooks.intercept(FEAT_DUMP + "/adb/create", create, chain -> {
                            try {
                                Object self = chain.getThisObject();
                                if (self != null) {
                                    ctx.log.info("[UCDump] AlertDialog.Builder.create "
                                            + self.getClass().getName());
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                    }
                } catch (Throwable ignored) {
                }

                ctx.log.info("[UCDump] dump installed: act=" + actN + " bind=" + bindN
                        + " (use: adb logcat -s LiTianSuo:I | grep UCDump)");
            } catch (Throwable t) {
                ctx.log.error("dump install failed", t);
            }
        });
    }

    // ----------------------------------------------------------- 底栏 tab 隐藏

    /**
     * 底栏 tab 隐藏：父 View 容器 -> 已添加的 InfoFlowToolBarItem 计数。
     * UC 6 个 tab 顺序固定：首页 / 短剧 / (网盘) / 搜索 / 我的 / 设置。
     * 主人要藏前 2 个（首页、短剧）。
     */
    private static final java.util.Map<android.view.View, java.util.concurrent.atomic.AtomicInteger>
            TAB_INDEX_COUNTER = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 底栏 item View 类名（精确匹配）。 */
    private static final String TOOLBAR_ITEM_CLS =
            "com.uc.browser.webwindow.newtoolbar.navigationbaritem.InfoFlowToolBarItem";
    /** 底栏父容器 View 类名（精确匹配）。 */
    private static final String TOOLBAR_PARENT_CLS =
            "com.uc.framework.ui.widget.a0";

    /**
     * 隐藏底栏 tab 入口：前 2 个 Item（前 2 次被 addView 进 WebWindowNavigationBar）GONE。
     * 受 K_HIDE_HOME / K_HIDE_SHORT 控制（默认开）；关掉开关就放行所有 tab。
     *
     * <p><b>为什么不 hook 构造器</b>：UC 用了 Qigsaw 拆分 dex，InfoFlowToolBarItem 类
     * 在 startup 阶段还没加载到主 ClassLoader，构造器 hook 抛 CNF。改 hook addView：
     * addView 的目标 View 一定是已加载的实例（addView 进来时该 View 已经 new 完了）。</p>
     */
    private void installTabHide(Context ctx) {
        ctx.feature(FEAT_TAB_HIDE, () -> {
            try {
                Method addView = Reflect.method(ViewGroup.class, "addView",
                        View.class, int.class, ViewGroup.LayoutParams.class);
                ctx.hooks.intercept(FEAT_TAB_HIDE + "/add", addView, chain -> {
                    try {
                        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                        if (p == null) return chain.proceed();
                        boolean hideHome = p.get(UcBrowserPanel.K_HIDE_HOME, true);
                        boolean hideShort = p.get(UcBrowserPanel.K_HIDE_SHORT, true);
                        if (!hideHome && !hideShort) return chain.proceed();

                        // addView 的 self = 父 ViewGroup
                        Object self = chain.getThisObject();
                        if (!(self instanceof View)) return chain.proceed();
                        String parentName = self.getClass().getName();
                        if (!TOOLBAR_PARENT_CLS.equals(parentName)) return chain.proceed();

                        Object child = chain.getArg(0);
                        if (!(child instanceof View)) return chain.proceed();
                        String childName = ((View) child).getClass().getName();
                        if (!TOOLBAR_ITEM_CLS.equals(childName)) return chain.proceed();

                        // 计算这是第几个 item（前 2 个 GONE）
                        ViewGroup parent = (ViewGroup) self;
                        java.util.concurrent.atomic.AtomicInteger cnt =
                                TAB_INDEX_COUNTER.computeIfAbsent(parent,
                                        k -> new java.util.concurrent.atomic.AtomicInteger());
                        int idx = cnt.getAndIncrement();
                        if ((idx == 0 && hideHome) || (idx == 1 && hideShort)) {
                            Object r = chain.proceed();
                            ((View) child).setVisibility(View.GONE);
                            ctx.log.hitThrottled("tab-hide:" + idx,
                                    "hid toolbar tab idx=" + idx);
                            return r;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                ctx.log.info("tab-hide installed (addView on WebWindowNavigationBar); "
                        + "runtime-gated by K_HIDE_HOME / K_HIDE_SHORT");
            } catch (Throwable t) {
                ctx.log.error("tab-hide install failed", t);
            }
        });
    }

    // ----------------------------------------------------------- 网盘设为主页


    /** 网盘设为主页：确保只自动切换一次（进程级）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean AUTO_NETDISK_DONE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 网盘设为主页：底栏 5 个 tab 全部 addView 完成后，延迟 1s 检查网盘 tab (idx 3)
     * 可见性，可见则 performClick 自动切换。用 AtomicBoolean 保证只切一次。
     *
     * <p>UC 有两个 a0 实例（一个在 GONE 的 WebWindow，一个在可见的 j1），
     * 靠 tab.getVisibility()==VISIBLE && getWidth()>0 区分可见实例。</p>
     *
     * <p>受 K_NETDISK_HOME 面板开关控制，默认开。</p>
     */
    private void installAutoNetDisk(Context ctx) {
        ctx.feature(FEAT_NETDISK_HOME, () -> {
            try {
                Method addView = Reflect.method(ViewGroup.class, "addView",
                        View.class, int.class, ViewGroup.LayoutParams.class);
                ctx.hooks.intercept(FEAT_NETDISK_HOME + "/add", addView, chain -> {
                    try {
                        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                        if (p == null) return chain.proceed();
                        if (!p.get(UcBrowserPanel.K_NETDISK_HOME, true)) return chain.proceed();
                        if (AUTO_NETDISK_DONE.get()) return chain.proceed();

                        Object self = chain.getThisObject();
                        if (!(self instanceof View)) return chain.proceed();
                        if (!TOOLBAR_PARENT_CLS.equals(self.getClass().getName())) return chain.proceed();

                        Object child = chain.getArg(0);
                        if (!(child instanceof View)) return chain.proceed();
                        if (!TOOLBAR_ITEM_CLS.equals(((View) child).getClass().getName())) return chain.proceed();

                        Object r = chain.proceed();
                        ViewGroup parent = (ViewGroup) self;
                        if (parent.getChildCount() < 5) return r;
                        if (AUTO_NETDISK_DONE.get()) return r;

                        final View netdiskTab = parent.getChildAt(3);
                        if (netdiskTab == null) return r;
                        final int[] netRetries = {0};
                        final Runnable[] netAttempt = new Runnable[1];
                        netAttempt[0] = () -> {
                            try {
                                if (AUTO_NETDISK_DONE.get()) return;
                                if (netdiskTab.getVisibility() != View.VISIBLE
                                        || netdiskTab.getWidth() <= 0) return;
                                // tab -> c.l0 (OnClickListener) -> f.i (vi2.b) -> vi2.b.c (HomePageCloudDriveWindow)
                                Class<?> cCls = netdiskTab.getClass().getSuperclass();
                                Object ocl = null;
                                for (java.lang.reflect.Field f : cCls.getDeclaredFields()) {
                                    f.setAccessible(true);
                                    if ("OnClickListener".equals(f.getType().getSimpleName()))
                                        ocl = f.get(netdiskTab);
                                }
                                if (ocl == null) { ctx.log.info("netdisk-home: ocl null"); return; }
                                Class<?> fCls = ocl.getClass();
                                while (fCls != null && !fCls.getName().equals(
                                        "com.uc.framework.ui.widget.toolbar.f"))
                                    fCls = fCls.getSuperclass();
                                if (fCls == null) { ctx.log.info("netdisk-home: fCls null"); return; }
                                Object vi2b = null;
                                for (java.lang.reflect.Field fg : fCls.getDeclaredFields()) {
                                    fg.setAccessible(true);
                                    Object v = fg.get(ocl);
                                    if (v != null && fg.getType().getName().contains("iy2")) { vi2b = v; break; }
                                }
                                if (vi2b == null) { ctx.log.info("netdisk-home: vi2b null"); return; }
                                Object cdWindow = null;
                                for (java.lang.reflect.Field bf : vi2b.getClass().getDeclaredFields()) {
                                    bf.setAccessible(true);
                                    Object v = bf.get(vi2b);
                                    if (v != null && v.getClass().getName().contains("CloudDrive")) { cdWindow = v; break; }
                                }
                                if (cdWindow == null) {
                                    ctx.log.info("netdisk-home: cdWindow null, retry " + netRetries[0]);
                                    if (netRetries[0]++ < 4)
                                        new android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed(netAttempt[0], 3000L);
                                    return;
                                }
                                Method getCB = cdWindow.getClass().getMethod("getUICallbacks");
                                Object controller = getCB.invoke(cdWindow);
                                ctx.log.info("netdisk-home: controller=" + controller);
                                if (controller == null) {
                                    if (netRetries[0]++ < 4)
                                        new android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed(netAttempt[0], 3000L);
                                    return;
                                }
                                Method l6 = controller.getClass().getDeclaredMethod("l6", String.class);
                                l6.setAccessible(true);
                                l6.invoke(controller, "");
                                AUTO_NETDISK_DONE.compareAndSet(false, true);
                                ctx.log.hitThrottled("netdisk-home", "opened cloud drive tab");
                            } catch (Throwable t) {
                                ctx.log.info("netdisk-home: exception: " + t);
                            }
                        };
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(netAttempt[0], 5000L);
                        return r;
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                ctx.log.info("netdisk-home installed (addView on a0); "
                        + "runtime-gated by K_NETDISK_HOME");
            } catch (Throwable t) {
                ctx.log.error("netdisk-home install failed", t);
            }
        });
    }

    // ----------------------------------------------------------- 个性推荐弹窗拦截

    /**
     * 拦截"个性推荐获得更丰富内容"等引导浮层。
     *
     * <p>hook {@code TextView.setText}，检测"个性推荐"/"更丰富内容"关键词，
     * 命中后向上遍历 parent 链隐藏弹窗根 View。</p>
     */
    private void installPopupBlock(Context ctx) {
        ctx.feature(FEAT_POPUP_BLOCK, () -> {
            try {
                int n = 0;
                for (Method m : Reflect.methodsNamed(
                        android.widget.TextView.class, "setText")) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0 || !CharSequence.class.isAssignableFrom(params[0]))
                        continue;
                    ctx.hooks.intercept(FEAT_POPUP_BLOCK + "/setText/" + m.getParameterCount(), m, chain -> {
                        try {
                            com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                            if (p == null || !p.get(UcBrowserPanel.K_POPUP_BLOCK, true))
                                return chain.proceed();
                            Object arg = chain.getArg(0);
                            if (arg instanceof CharSequence) {
                                String s = arg.toString();
                                if (s.contains("个性推荐") || s.contains("更丰富内容")) {
                                    android.widget.TextView tv =
                                            (android.widget.TextView) chain.getThisObject();
                                    tv.post(() -> {
                                        android.view.ViewParent vp = tv.getParent();
                                        for (int i = 0; i < 4 && vp instanceof android.view.View; i++) {
                                            android.view.View v = (android.view.View) vp;
                                            if (v.getWidth() > 0 && v.getHeight() > 0) {
                                                v.setVisibility(android.view.View.GONE);
                                                ctx.log.hitThrottled("popup-block",
                                                        "hid popup: " + v.getClass().getName());
                                                break;
                                            }
                                            vp = v.getParent();
                                        }
                                    });
                                }
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    n++;
                }
                ctx.log.info("popup-block installed (setText x" + n + "); "
                        + "runtime-gated by K_POPUP_BLOCK");
            } catch (Throwable t) {
                ctx.log.error("popup-block install failed", t);
            }
        });
    }

    // ----------------------------------------------------------- CrashRecovery 拦截

    /**
     * 禁用网盘页面的 CrashRecovery 保存/恢复逻辑，解决 Flutter 页面来回闪烁。
     *
     * <p>UC 的 CrashRecovery 在网盘页面切换时保存 {@code CLOUD_DRIVE_TAB} 状态，
     * 恢复时导致 Flutter 在 {@code /clouddrive/main} 和 {@code /usercenter/...} 之间
     * 来回切换，表现为屏幕闪几下。</p>
     *
     * <p>hook 四个方法：</p>
     * <ul>
     *   <li>{@code HomePageCloudDriveWindow.Zb} → null（禁用保存 recovery Bundle）</li>
     *   <li>{@code HomePageCloudDriveWindow.a0} → null（禁用 onSaveState）</li>
     *   <li>{@code com.uc.browser.webwindow.o.o0} → false（禁用恢复检查）</li>
     *   <li>{@code com.uc.browser.webwindow.o.q0} → false（禁用自动恢复）</li>
     * </ul>
     *
     * <p>受 K_CRASH_RECOVERY 面板开关控制，默认开。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean CRASH_RECOVERY_DONE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void installCrashRecoveryBlock(Context ctx) {
        ctx.feature(FEAT_CRASH_RECOVERY, () -> {
            try {
                final int[] retries = {0};
                final Runnable[] attempt = new Runnable[1];
                attempt[0] = () -> {
                    try {
                        if (CRASH_RECOVERY_DONE.get()) return;
                        android.app.Application app = currentApplication();
                        if (app == null) {
                            ctx.log.warn("crash-recovery: no app context");
                            return;
                        }
                        ClassLoader ucCl = app.getClassLoader();
                        int n = 0;
                        Class<?> cdWindowCls = Class.forName(
                                "com.uc.business.clouddrive.HomePageCloudDriveWindow", false, ucCl);
                        for (Method m : cdWindowCls.getDeclaredMethods()) {
                            if (!m.getName().equals("Zb")) continue;
                            ctx.hooks.intercept(FEAT_CRASH_RECOVERY + "/Zb", m, chain -> {
                                try {
                                    com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                                    if (p == null || !p.get(UcBrowserPanel.K_CRASH_RECOVERY, true))
                                        return chain.proceed();
                                    ctx.log.hitThrottled("crash-recovery", "blocked Zb save");
                                    return null;
                                } catch (Throwable ignored) { return chain.proceed(); }
                            });
                            n++;
                        }
                        for (Method m : cdWindowCls.getDeclaredMethods()) {
                            if (!m.getName().equals("a0") || m.getParameterCount() != 0) continue;
                            ctx.hooks.intercept(FEAT_CRASH_RECOVERY + "/a0", m, chain -> {
                                try {
                                    com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                                    if (p == null || !p.get(UcBrowserPanel.K_CRASH_RECOVERY, true))
                                        return chain.proceed();
                                    ctx.log.hitThrottled("crash-recovery", "blocked a0 save");
                                    return null;
                                } catch (Throwable ignored) { return chain.proceed(); }
                            });
                            n++;
                        }
                        Class<?> recoveryCls = Class.forName("com.uc.browser.webwindow.o", false, ucCl);
                        for (Method m : recoveryCls.getDeclaredMethods()) {
                            if (!m.getName().equals("o0") || m.getParameterCount() != 0) continue;
                            ctx.hooks.intercept(FEAT_CRASH_RECOVERY + "/o0", m, chain -> {
                                try {
                                    com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                                    if (p == null || !p.get(UcBrowserPanel.K_CRASH_RECOVERY, true))
                                        return chain.proceed();
                                    ctx.log.hitThrottled("crash-recovery", "blocked o0 restore");
                                    return Boolean.FALSE;
                                } catch (Throwable ignored) { return chain.proceed(); }
                            });
                            n++;
                        }
                        for (Method m : recoveryCls.getDeclaredMethods()) {
                            if (!m.getName().equals("q0") || m.getParameterCount() != 1) continue;
                            ctx.hooks.intercept(FEAT_CRASH_RECOVERY + "/q0", m, chain -> {
                                try {
                                    com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
                                    if (p == null || !p.get(UcBrowserPanel.K_CRASH_RECOVERY, true))
                                        return chain.proceed();
                                    ctx.log.hitThrottled("crash-recovery", "blocked q0 restore");
                                    return Boolean.FALSE;
                                } catch (Throwable ignored) { return chain.proceed(); }
                            });
                            n++;
                        }
                        CRASH_RECOVERY_DONE.compareAndSet(false, true);
                        ctx.log.info("crash-recovery-block installed (hooks=" + n
                                + "); runtime-gated by K_CRASH_RECOVERY");
                    } catch (ClassNotFoundException cnf) {
                        ctx.log.info("crash-recovery: class not loaded yet, retry " + retries[0]);
                        if (retries[0]++ < 8)
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(attempt[0], 3000L);
                    } catch (Throwable t) {
                        ctx.log.error("crash-recovery-block install failed", t);
                    }
                };
                attempt[0].run();
            } catch (Throwable t) {
                ctx.log.error("crash-recovery-block install failed", t);
            }
        });
    }

    // ----------------------------------------------------------- 启动加速

    /**
     * 启动加速拦截列表：开屏 Activity、开机自启 Service、不必要的预热 Service。
     * 主人当纯网盘用，启动时根本不应该让这些跑起来。
     */
    private static final String[] LAUNCH_BLOCK_PREFIXES = {
            // 开屏 Activity
            "com.uc.browser.advertisement.Splash",
            "com.uc.application.splash.",
            // UC 自家启动期 Service
            "com.uc.browser.statis.",
            "com.uc.ubox.",
            "com.uc.application.stat.",
            "com.uc.base.stat.",
            "com.uc.framework.stat.",
            // 第三方广告 SDK 启动 Service（穿山甲/百度/广点通/Noah/华为/阿里妈妈）
            "com.bytedance.sdk.openadsdk.",
            "com.baidu.mobads.sdk.",
            "com.qq.e.",
            "com.noah.",
            "com.huawei.openalliance.ad.",
            "com.alimm.tanx.",
            // 阿里推送+埋点 channel 进程（纯网盘不需要，长连接心跳发热）
            "com.taobao.accs.",
            "com.alibaba.analytics.",
            "org.android.agoo.",
            "com.uc.base.push.",
            // 媒体播放服务（纯网盘不需要音视频播放）
            "com.uc.browser.core.media.MediaPlayerService",
            // 快手 Kanas SDK（启动时尝试拉起但 not found）
            "com.kwai.kanas.",
    };

    /** 该类名（Service/Activity）是否属于启动加速应该拦的。 */
    private boolean isLaunchBlocked(String className) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null || className == null) return false;
        if (!p.get(UcBrowserPanel.K_LAUNCH, true)) return false;
        for (String pre : LAUNCH_BLOCK_PREFIXES) {
            if (className.startsWith(pre)) return true;
        }
        return false;
    }

    /**
     * 启动加速装具：拦截
     * <ul>
     *   <li>开屏 Activity（execStartActivity 全部重载）</li>
     *   <li>开机自启 Service（startService + 4 个 bindService 重载）</li>
     * </ul>
     *
     * <p>被拦下的 Service/Activity 返回 null（等同「没起来」），调用方本就要处理
     * 这种情况，不会崩。</p>
     */
    private void installLaunchBlock(Context ctx) {
        ctx.feature(FEAT_LAUNCH, () -> {
            try {
                // Activity 拦截
                int actN = 0;
                for (Method m : Reflect.methodsNamed(
                        android.app.Instrumentation.class, "execStartActivity")) {
                    final int intentIdx = indexOfIntent(m);
                    if (intentIdx < 0) continue;
                    ctx.hooks.intercept(FEAT_LAUNCH + "/act/" + m.getParameterCount(), m, chain -> {
                        try {
                            Object arg = chain.getArg(intentIdx);
                            if (arg instanceof Intent) {
                                String target = componentOf((Intent) arg);
                                if (isLaunchBlocked(target)) {
                                    ctx.log.hitThrottled("launch-act:" + target,
                                            "blocked launch activity: " + target);
                                    return null;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    actN++;
                }

                // Service 拦截（ContextWrapper + ContextImpl 都 hook；
                // ContextImpl override 了 startService/bindService，只 hook ContextWrapper 拦不住）
                java.util.List<Class<?>> ctxClasses = new java.util.ArrayList<>();
                ctxClasses.add(android.content.ContextWrapper.class);
                try { ctxClasses.add(Class.forName("android.app.ContextImpl")); } catch (Throwable ignored) {}
                int startN = 0;
                int bindN = 0;
                for (Class<?> cls : ctxClasses) {
                    String cTag = cls == android.content.ContextWrapper.class ? "" : "CI/";
                    for (Method m : Reflect.methodsNamed(cls, "startService")) {
                        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != Intent.class) continue;
                        ctx.hooks.intercept(FEAT_LAUNCH + "/" + cTag + "start", m, chain -> {
                            try {
                                Object arg = chain.getArg(0);
                                if (arg instanceof Intent) {
                                    String comp = componentOf((Intent) arg);
                                    if (isLaunchBlocked(comp)) {
                                        ctx.log.hitThrottled("launch-svc:" + comp,
                                                "blocked launch startService: " + comp);
                                        return null;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                        startN++;
                    }
                    for (Method m : Reflect.methodsNamed(cls, "bindService")) {
                        final int intentIdx = indexOfIntent(m);
                        if (intentIdx < 0 || m.getReturnType() != boolean.class) continue;
                        ctx.hooks.intercept(FEAT_LAUNCH + "/" + cTag + "bind/" + m.getParameterCount(), m, chain -> {
                            try {
                                Object arg = chain.getArg(intentIdx);
                                if (arg instanceof Intent) {
                                    String comp = componentOf((Intent) arg);
                                    if (isLaunchBlocked(comp)) {
                                        ctx.log.hitThrottled("launch-bind:" + comp,
                                                "blocked launch bindService: " + comp);
                                        return Boolean.FALSE;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                        bindN++;
                    }
                }

                ctx.log.info("launch-block installed (act=" + actN + " start=" + startN
                        + " bind=" + bindN + "); runtime-gated by K_LAUNCH");
            } catch (Throwable t) {
                ctx.log.error("launch-block install failed", t);
            }
        });
    }
}
