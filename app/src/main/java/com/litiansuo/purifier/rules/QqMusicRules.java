package com.litiansuo.purifier.rules;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litiansuo.purifier.hook.Reflect;
import com.litiansuo.purifier.hook.XLog;

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
    private static long MODULE_LOAD_TIME = System.currentTimeMillis();

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
    /** 在「关于 QQ 音乐」连点版本号时插入「李田所」入口。 */
    private static final String FEAT_ABOUT_ENTRY = "about-entry";
    /** 面板里勾选的额外拦截项（付费墙等）。 */
    private static final String FEAT_EXTRA_BLOCK = "extra-block";

    /** 面板开关的存储。late 阶段通过 Application context 打开。 */
    private com.litiansuo.purifier.hook.LocalPrefs prefs;

    /**
     * 面板条目 -> 该条目要额外拦的类名前缀。
     *
     * <p>这些前缀<b>不在</b> {@link #AD_PREFIXES} 里，或者被
     * {@link #KEEP_PREFIXES} 显式豁免了。只有用户在面板里勾了对应条目，
     * 它们才参与判定。</p>
     */
    private static final String[][] EXTRA_PREFIXES = {
            {QqMusicPanel.K_PAYWALL,
                    "com.tencent.qqmusic.business.playernew.vipguide."},
            {QqMusicPanel.K_FREEMODE,
                    "com.tencent.qqmusic.business.ad.freemode.",
                    "com.tencent.qqmusic.business.ad.radarfreemode.",
                    "com.tencent.qqmusic.business.ad.topbarad.freemode."},
            {QqMusicPanel.K_REWARD,
                    "com.tencent.qqmusic.business.ad.reward."},
            {QqMusicPanel.K_VIPEARN,
                    "com.tencent.qqmusic.business.ad.vipearningmode."},
            {QqMusicPanel.K_RECVIP,
                    "com.tencent.qqmusic.recommend.vip."},
            {QqMusicPanel.K_MINIBAR_TIP,
                    "com.tencent.qqmusic.minibarviptips."},
            {QqMusicPanel.K_TOPVIPBAR,
                    "com.tencent.qqmusic.fragment.folderalbum.vip.TopVipAdBar"},
            {QqMusicPanel.K_QPLAY,
                    "com.tencent.qqmusicplayerprocess.qplayauto.QPlayAutoService"},
            {QqMusicPanel.K_SECURITY,
                    "tmsdk.common.KcBaseService"},
            {QqMusicPanel.K_LIVE,
                    "com.tme.mlive.framework.ui.LivePagerActivity",
                    "com.tme.mlive.framework.service.LiveService"},
            {QqMusicPanel.K_WEBVIEW,
                    "com.tencent.qqmusic.activity.WebViewActivity"},
            {QqMusicPanel.K_AIAGENT,
                    "com.tencent.qqmusic.business.aiagent.kuikly.AiAgentKuiklyActivity"},
            {QqMusicPanel.K_GMS,
                    "com.google.android.gms.chimera.GmsBoundBrokerService"},
            {QqMusicPanel.K_WEBVIEWSVR,
                    "org.chromium.android_webview.services.MetricsBridgeService",
                    "org.chromium.android_webview.services.VariationsSeedServer"},
    };

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
        openPrefs(ctx);
        installPlayerAdBlock(ctx);
        installInterstitialBlock(ctx);
        installGdtInitBlock(ctx);
        installAboutEntry(ctx);
        installComponentBlock(ctx);
        installStartupOptimization(ctx);
        installKuiklyDialogBlock(ctx);
        installPopupBlock(ctx);
        installAutoSignIn(ctx);
    }

    /**
     * 广告弹窗拦截：hook {@code Dialog.show()}，检查弹窗内文本是否含广告关键词，
     * 命中则 dismiss。拦截三类弹窗：
     * <ul>
     *   <li>「签到领金币」——首页签到提醒弹窗；</li>
     *   <li>「浏览广告再送」——「我的」页面 VIP 免费听推广弹窗；</li>
     *   <li>「今日灵感」——签到页 H5 弹窗，引导看广告。</li>
     * </ul>
     *
     * <p>用关键词而非类名匹配：这些弹窗的业务类不在反编译产物中（可能在 Hippy/Kuikly
     * 动态加载），类名不稳定。文本关键词跨版本稳定，且误判风险低——正常功能不会同时
     * 含「签到领金币」或「浏览广告再送」这样的文案。</p>
     *
     * <p>受面板「广告弹窗拦截」({@code K_POPUP_BLOCK}) 开关控制，默认关。</p>
     */
    private void installPopupBlock(Context ctx) {
        ctx.feature("popup-block", () -> {
            com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
            if (p == null || !p.get(QqMusicPanel.K_POPUP_BLOCK, false)) {
                ctx.log.info("popup block: switch off, skipping");
                return;
            }
            try {
                Class<?> dialogCls = Class.forName("android.app.Dialog");
                Method showMethod = null;
                for (Method m : dialogCls.getDeclaredMethods()) {
                    if ("show".equals(m.getName()) && m.getParameterCount() == 0) {
                        showMethod = m;
                        break;
                    }
                }
                if (showMethod == null) {
                    throw new NoSuchMethodException("Dialog.show()");
                }
                ctx.hooks.intercept("popup-block/show", showMethod, chain -> {
                    Object r = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof android.app.Dialog) {
                            final android.app.Dialog dialog = (android.app.Dialog) self;
                            // 延迟 300ms 让弹窗内容渲染完毕再检查文本
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed(() -> {
                                try {
                                    if (dialog.isShowing()) {
                                        String text = collectDialogText(dialog);
                                        if (text != null) {
                                            for (String kw : POPUP_KEYWORDS) {
                                                if (text.contains(kw)) {
                                                    ctx.log.hitThrottled(
                                                            "popup:" + kw,
                                                            "blocked popup [" + kw + "]");
                                                    dialog.dismiss();
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }, 300);
                        }
                    } catch (Throwable ignored) {}
                    return r;
                });
                ctx.log.info("popup block installed (Dialog.show)");
            } catch (Throwable t) {
                ctx.log.error("popup block failed", t);
            }
        });
    }

    /** 弹窗广告关键词：命中任一即 dismiss。 */
    private static final String[] POPUP_KEYWORDS = {
            "签到领金币",
            "浏览广告再送",
            "今日灵感",
    };

    /** 递归收集 Dialog 内所有 TextView 的文本，用于关键词匹配。 */
    private static String collectDialogText(android.app.Dialog dialog) {
        try {
            android.view.Window win = dialog.getWindow();
            if (win == null) return null;
            android.view.View decor = win.getDecorView();
            if (decor == null) return null;
            StringBuilder sb = new StringBuilder();
            collectViewText(decor, sb);
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectViewText(android.view.View v, StringBuilder sb) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null) {
                sb.append(t).append('\n');
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectViewText(g.getChildAt(i), sb);
            }
        }
    }

    /** 自动签到开关键（面板）。 */
    private static final String K_AUTO_SIGNIN = QqMusicPanel.K_AUTO_SIGNIN;
    /** 上次成功签到的日期戳（yyyyMMdd）存储键。 */
    private static final String PREF_LAST_SIGN_DAY = "auto.signin.lastday.v4";
    /** 上次成功签到时的账号 uin，用于检测切换账号后清除旧签到记录。 */
    private static final String PREF_LAST_SIGN_UIN = "auto.signin.lastuin.v1";
    /** 金币中心签到 H5 页面。用隐藏 WebView 加载它触发签到。 */
    private static final String SIGN_URL =
            "https://i2.y.qq.com/n3/coin_center/pages/client_v1/sign.html?_hidehd=1&_hdct=1&_miniplayer=1&ADTAG=auto_lts";

    /**
     * 自动签到：每次 QQ 音乐启动后，延迟随机几秒，在后台用一个<b>不加入界面</b>的
     * WebView 静默加载金币中心签到页，让页面自己的 JS 完成日签。每天只做一次。
     *
     * <p>为什么用隐藏 WebView 而不是直接打签到接口：签到接口带复杂签名（sign/加密参数），
     * 由 H5 页面内的 JS 现算，直接照搬 URL 会因签名过期/缺参失败。用页面原生 JS 触发，
     * 签名、登录态、cookie 全部复用 App 自己的，最稳。WebView 不 attach 到任何布局，
     * 用户完全看不到。</p>
     *
     * <p>受面板「每日自动签到」({@code K_AUTO_SIGNIN}) 开关控制，默认关。</p>
     */
    private void installAutoSignIn(Context ctx) {
        ctx.feature("auto-signin", () -> {
            // 挂在 Application onCreate 之后：hook ActivityThread 拿不到合适时机，
            // 改为 hook 首个 Activity 的 onResume（此时 UI 线程、cookie、登录态都就绪）。
            Class<?> actCls = Class.forName("android.app.Activity");
            Method onResume = null;
            for (Method m : actCls.getDeclaredMethods()) {
                if ("onResume".equals(m.getName()) && m.getParameterCount() == 0) {
                    onResume = m;
                    break;
                }
            }
            if (onResume == null) {
                throw new NoSuchMethodException("Activity.onResume");
            }
            final boolean[] scheduled = {false};
            ctx.hooks.intercept("auto-signin/trigger", onResume, chain -> {
                Object r = chain.proceed();
                try {
                    if (!scheduled[0]) {
                        Object self = chain.getThisObject();
                        if (self instanceof android.app.Activity) {
                            String actName = self.getClass().getName();
                            ctx.log.info("auto-signin: onResume " + actName);
                            // 只在主界面 AppStarterActivity 触发一次，避免每个 Activity 都跑
                            if (actName.contains("AppStarterActivity")) {
                                scheduled[0] = true;
                                scheduleAutoSignIn((android.app.Activity) self, ctx);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            ctx.log.info("auto sign-in installed");
        });
    }

    /**
     * 在随机延迟后于后台静默签到。判定「今天是否已签」用本地日期戳，避免重复。
     */
    private void scheduleAutoSignIn(android.app.Activity activity, Context ctx) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null) {
            ctx.log.warn("auto sign-in: prefs not available");
            return;
        }
        if (!p.get(K_AUTO_SIGNIN, false)) {
            ctx.log.info("auto sign-in: switch off, skipping");
            return;
        }
        // 检测是否由 AlarmManager 后台拉起
        final boolean isBackground = activity.getIntent() != null
                && activity.getIntent().getBooleanExtra("lts_auto_signin", false);
        if (isBackground) {
            ctx.log.info("auto sign-in: background mode (alarm triggered)");
            try { activity.overridePendingTransition(0, 0); } catch (Throwable ignored) {}
        }
        // 注册 AlarmManager 定时后台签到（每次启动都注册，确保定时有效）
        scheduleAlarmSignIn(activity, ctx);
        // 检查是否切换了账号：如果 uin 变了，清除旧签到记录
        String currentUin = getCurrentUin();
        String storedUin = p.getString(PREF_LAST_SIGN_UIN, "");
        ctx.log.info("auto sign-in: uin check cur=" + currentUin + " stored=" + storedUin);
        if (!currentUin.isEmpty()
                && (storedUin.isEmpty() || !storedUin.equals(currentUin))) {
            p.setString(PREF_LAST_SIGN_UIN, currentUin);
            p.setLong(PREF_LAST_SIGN_DAY, 0L);
            ctx.log.info("auto sign-in: account changed or first uin ("
                    + storedUin + " -> " + currentUin + "), clearing sign record");
        }
        // 今天已经签过就跳过
        String today = todayStamp();
        long last = p.getLong(PREF_LAST_SIGN_DAY, 0L);
        if (String.valueOf(last).equals(today)) {
            ctx.log.info("auto sign-in: already done today (" + today + ")");
            if (isBackground) {
                try { activity.finish(); activity.overridePendingTransition(0, 0); } catch (Throwable ignored) {}
            } else {
                toast(activity, "李田所：今日已签到");
            }
            return;
        }
        // 后台模式 2 秒后执行，前台模式随机 5~15 秒（错开启动高峰、拟人化）
        final int delayMs = isBackground ? 2000 : (5000 + new java.util.Random().nextInt(10000));
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        if (!isBackground) {
            toast(activity, "李田所：" + (delayMs / 1000) + "秒后自动签到");
        }
        handler.postDelayed(() -> {
            try {
                doSilentSignIn(activity, ctx, p, today, isBackground);
            } catch (Throwable t) {
                ctx.log.error("auto sign-in failed", t);
                if (!isBackground) toast(activity, "李田所：签到失败 " + t.getMessage());
                if (isBackground) try { activity.finish(); } catch (Throwable ignored) {}
            }
        }, delayMs);
        ctx.log.info("auto sign-in scheduled in " + delayMs + "ms" + (isBackground ? " (bg)" : ""));
    }

    /**
     * 注册 AlarmManager 定时后台签到。每次 QQ 音乐启动时调用，先取消旧 alarm 再注册新的。
     * 触发时间：明天 9:00 + 随机 0-59 分钟（避免固定时间被系统优化或集中）。
     * 触发动作：启动 QQ 音乐主 Activity，带 extra {@code lts_auto_signin=true}，
     * hook 检测到后无界面签到并 finish。
     */
    private void scheduleAlarmSignIn(android.app.Activity activity, Context ctx) {
        try {
            android.content.Context appCtx = activity.getApplicationContext();
            android.app.AlarmManager am = (android.app.AlarmManager)
                    appCtx.getSystemService(android.content.Context.ALARM_SERVICE);
            if (am == null) return;
            // 明天 9:00 + 随机 0-59 分钟
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 9);
            cal.set(java.util.Calendar.MINUTE, new java.util.Random().nextInt(60));
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long triggerAt = cal.getTimeInMillis();
            // 启动 QQ 音乐主 Activity，带签到标识
            android.content.Intent intent = appCtx.getPackageManager()
                    .getLaunchIntentForPackage("com.tencent.qqmusic");
            if (intent == null) return;
            intent.putExtra("lts_auto_signin", true);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            int flag = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flag |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(appCtx, 0, intent, flag);
            am.cancel(pi);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            ctx.log.info("auto sign-in: alarm scheduled for "
                    + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(triggerAt)));
        } catch (Throwable t) {
            ctx.log.warn("auto sign-in: alarm schedule failed: " + t.getMessage());
        }
    }

    /**
     * 获取当前登录 uin，用于检测切换账号。返回空串表示获取失败。
     *
     * <p>双途径获取：</p>
     * <ol>
     *   <li>CookieManager：检查多个 QQ 域名下的 uin cookie；</li>
     *   <li>SharedPreferences：遍历 qqmusic 的 shared_prefs 目录，找含 "uin" 的键。
     *       QQ音乐可能不用 WebView CookieManager 存登录态，但会在本地 prefs 里存 uin。</li>
     * </ol>
     */
    private String getCurrentUin() {
        // 1. CookieManager
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            for (String domain : new String[]{
                    "https://y.qq.com", "https://i2.y.qq.com", "https://u.y.qq.com",
                    "https://i.y.qq.com", "https://u6.y.qq.com", "https://qq.com",
            }) {
                String cookie = cm.getCookie(domain);
                if (cookie == null) continue;
                for (String part : cookie.split(";")) {
                    part = part.trim();
                    if (part.startsWith("uin=")) {
                        String v = part.substring(4);
                        if (!v.isEmpty()) return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 2. SharedPreferences：遍历 shared_prefs 目录找含 uin 的键
        try {
            android.app.Application app = currentApplication();
            if (app != null) {
                java.io.File prefsDir =
                        new java.io.File(app.getFilesDir().getParent(), "shared_prefs");
                if (prefsDir.isDirectory()) {
                    java.io.File[] files = prefsDir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            String name = f.getName();
                            if (!name.endsWith(".xml")) continue;
                            name = name.substring(0, name.length() - 4);
                            try {
                                android.content.SharedPreferences sp =
                                        app.getSharedPreferences(name, 0);
                                java.util.Map<String, ?> all = sp.getAll();
                                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                                    String key = entry.getKey();
                                    if (key.equals("uin") || key.equals("uid")
                                            || key.equals("qqmusic_uin")
                                            || key.equals("strUin")) {
                                        Object val = entry.getValue();
                                        if (val != null) {
                                            String s = val.toString();
                                            if (!s.isEmpty() && s.length() < 20) return s;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 真正执行：创建一个不加入布局的 WebView，加载签到页，等它的 JS 跑完签到。
     * 加载完成后延迟 8 秒销毁 WebView（给接口请求留足时间），并写入今日日期戳。
     *
     * <p><b>闪退防护</b>：签到在延迟 5~15 秒后执行，用户可能已退出应用。
     * 用已销毁的 Activity 创建 WebView 会触发 native 崩溃（不经过 Java 异常机制，
     * 外层 try-catch 兜不住），所以必须先检查 {@code isFinishing}/{@code isDestroyed}，
     * 且 WebView 构造本身也包一层 try-catch。</p>
     */
    private void doSilentSignIn(android.app.Activity activity, Context ctx,
            com.litiansuo.purifier.hook.LocalPrefs p, String today, boolean isBackground) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            ctx.log.info("auto sign-in: activity destroyed, skipping");
            return;
        }
        if (!isBackground) toast(activity, "李田所：正在签到...");
        ctx.log.info("auto sign-in: creating WebView for " + SIGN_URL);
        final android.webkit.WebView web;
        try {
            web = new android.webkit.WebView(activity);
        } catch (Throwable t) {
            ctx.log.error("auto sign-in: WebView creation failed", t);
            if (!isBackground) toast(activity, "李田所：签到失败（WebView创建失败）");
            return;
        }
        // 只有页面真正加载成功且 JS 报告签到完成才标记
        final java.util.concurrent.atomic.AtomicBoolean signDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // onPageFinished 会被重定向/刷新触发多次，只处理第一次
        final java.util.concurrent.atomic.AtomicBoolean pageHandled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        android.webkit.WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // UA 含 qqmusic 标识，页面才认得这是 QQ 音乐内嵌环境（否则 musicReady 报错）
        try {
            ws.setUserAgentString(ws.getUserAgentString() + " qqmusic/13.0.0");
        } catch (Throwable ignored) {
        }
        // 确保 cookie 同步：QQ 音乐的登录态在 CookieManager 里
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptThirdPartyCookies(web, true);
            cm.flush();
        } catch (Throwable ignored) {
        }
        final Context ctxRef = ctx;
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                ctxRef.log.info("auto sign-in: page loaded " + url);
                if (!pageHandled.compareAndSet(false, true)) {
                    return;
                }
                if (!isBackground) toast(activity, "李田所：签到页已加载，等待按钮刷新...");
                // 页面异步加载签到状态，onPageFinished 时按钮可能还是"提醒我签到"，
                // 延迟 5 秒等异步刷新完再找"签到+"按钮
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isBackground) toast(activity, "李田所：正在执行签到...");
                // 精确查找签到按钮：优先找 class 含 sign-checkin__btn 且文本含"签到+"的元素，
                // 再找 innerText 恰好是"签到+"的叶子元素，最后找 class 含 btn-prefix 的元素。
                // 找到后向上找 class 含 btn(不含 prefix/remind) 的父元素一并点击，模拟完整事件序列。
                String js = "(function(){"
                    + "if(!window.__ltsHook){window.__ltsHook=true;window.__ltsReqs=[];"
                    + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return oo.apply(this,arguments);};"
                    + "XMLHttpRequest.prototype.send=function(b){var s=this;this.addEventListener('load',function(){try{window.__ltsReqs.push((s.__u||'')+' ['+s.status+']');}catch(x){}});return os.apply(this,arguments);};"
                    + "var of=window.fetch;if(of){window.fetch=function(){var u=arguments[0];if(typeof u==='object'&&u)u=u.url;var p=of.apply(this,arguments);if(p&&p.then){p.then(function(r){try{window.__ltsReqs.push('fetch:'+(u||'')+' ['+r.status+']');}catch(x){}});}return p;};}}"
                    + "var t=document.title||'';"
                    + "var bt=document.body?document.body.innerText.substring(0,200):'no-body';"
                    + "var clicked='';var btn=null;var cands=[];"
                    + "var cb=document.querySelectorAll('[class*=\"sign-checkin__btn\"]');"
                    + "for(var i=0;i<cb.length;i++){var e=cb[i];var tx=(e.innerText||'').trim();"
                    + "if(tx.indexOf('签到+')>=0){btn=e;clicked='checkin-btn:'+e.tagName+'.'+e.className+' txt:'+tx.substring(0,20);break;}}"
                    + "if(!btn){var all=document.querySelectorAll('div,span,button,a,p,i,em');"
                    + "for(var i=0;i<all.length;i++){var e=all[i];var tx=(e.innerText||'').trim();"
                    + "if(tx==='签到+'||tx==='签到'){btn=e;clicked='exact:'+e.tagName+'.'+e.className+' txt:'+tx;break;}}}"
                    + "if(!btn){var bp=document.querySelectorAll('[class*=btn-prefix],[class*=btnPrefix]');"
                    + "for(var i=0;i<bp.length;i++){var e=bp[i];var tx=(e.innerText||'').trim();"
                    + "if(tx.indexOf('签到')>=0){btn=e;clicked='prefix:'+e.tagName+'.'+e.className+' txt:'+tx;break;}}}"
                    + "if(!btn){var cs=document.querySelectorAll('[class*=sign],[class*=checkin]');"
                    + "for(var i=0;i<cs.length;i++){var e=cs[i];var tx=(e.innerText||'').trim();"
                    + "if(tx==='签到+'||tx==='签到'){btn=e;clicked='signcls:'+e.tagName+'.'+e.className+' txt:'+tx;break;}}}"
                    + "if(!btn){var all2=document.querySelectorAll('div,span,button,a,p,i,em');"
                    + "for(var i=0;i<all2.length&&cands.length<10;i++){var e=all2[i];var tx=(e.innerText||'').trim();"
                    + "if(tx.indexOf('签到')>=0&&tx.length<25){cands.push(e.tagName+'.'+e.className+':'+tx.replace(/\\n/g,'|'));}}}"
                    + "var vueInfo=[];var btnInfo={};"
                    + "if(btn){try{var gcs=getComputedStyle(btn);btnInfo={pe:gcs.pointerEvents,op:gcs.opacity,dis:gcs.display,vis:gcs.visibility,disabled:btn.disabled||false,onclick:!!btn.onclick};}catch(x){}"
                    + "var vel=btn;for(var vd=0;vd<8&&vel;vd++){var vks=Object.getOwnPropertyNames(vel);"
                    + "for(var vki=0;vki<vks.length;vki++){var vk=vks[vki];if(vk.indexOf('vue')<0&&vk.indexOf('Vue')<0&&vk.indexOf('__v')<0)continue;"
                    + "try{var vv=vel[vk];if(vv&&typeof vv==='object'){var vfns=[];var vctxs=[vv,vv.ctx,vv.setupState,vv.proxy];"
                    + "for(var vci=0;vci<vctxs.length;vci++){var vc=vctxs[vci];if(!vc)continue;"
                    + "for(var vm in vc){try{if(typeof vc[vm]==='function'&&vm.length<25&&(vm.toLowerCase().indexOf('sign')>=0||vm.toLowerCase().indexOf('checkin')>=0||vm.toLowerCase().indexOf('click')>=0||vm.toLowerCase().indexOf('tap')>=0||vm.toLowerCase().indexOf('handle')>=0))vfns.push((vci>0?'c'+vci+'.':'')+vm);}catch(x){}}}"
                    + "if(vfns.length>0)vueInfo.push({d:vd,k:vk,fns:vfns});}}catch(x){}}vel=vel.parentElement;}}"
                    + "function fire(el){try{el.click();}catch(x){}"
                    + "try{el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));}catch(x){}"
                    + "try{el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));}catch(x){}"
                    + "try{el.dispatchEvent(new MouseEvent('click',{bubbles:true}));}catch(x){}"
                    + "try{el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));}catch(x){}"
                    + "try{el.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));}catch(x){}"
                    + "try{var t2=new Touch({identifier:0,target:el});"
                    + "el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,touches:[t2]}));"
                    + "el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,touches:[]}));}catch(x){}}"
                    + "if(btn){var tgt=btn;var p=btn.parentElement;"
                    + "while(p){var pc=p.className||'';if(pc.indexOf('btn')>=0&&pc.indexOf('prefix')<0&&pc.indexOf('Prefix')<0&&pc.indexOf('remind')<0){tgt=p;clicked+=' ->parent:'+p.tagName+'.'+pc;break;}p=p.parentElement;}"
                    + "fire(tgt);if(tgt!==btn){fire(btn);}}"
                    + "return JSON.stringify({title:t,clicked:clicked,cands:cands,vue:vueInfo,bi:btnInfo,text:bt});"
                    + "})()";
                view.evaluateJavascript(js, value -> {
                    ctxRef.log.info("auto sign-in: JS probe " + value);
                });
                // 5 秒后检查签到结果：点击后页面需要时间调接口+刷新
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        view.evaluateJavascript(
                            "(function(){var t=document.body?document.body.innerText:'';"
                            + "var hasSignPlus=t.indexOf('签到+')>=0;"
                            + "var m=t.match(/(已签到|签到成功|连续签到|今日已签|明天签到|明日再来)/);"
                            + "return JSON.stringify({match:m?m[0]:'',signPlus:hasSignPlus,reqs:(window.__ltsReqs||[]).slice(-10),snippet:t.substring(0,100)});})()",
                            v2 -> {
                                ctxRef.log.info("auto sign-in: JS result " + v2);
                                if (v2 != null && (v2.contains("已签到") || v2.contains("签到成功")
                                        || v2.contains("连续签到") || v2.contains("今日已签")
                                        || v2.contains("明天签到") || v2.contains("明日再来"))) {
                                    signDone.set(true);
                                    if (!isBackground) toast(activity, "李田所：签到成功");
                                } else if (v2 != null && v2.contains("AwardPrize") && v2.contains("[200]")) {
                                    signDone.set(true);
                                    if (!isBackground) toast(activity, "李田所：签到成功（AwardPrize 200）");
                                } else if (v2 != null && v2.contains("提醒我签到")) {
                                    signDone.set(true);
                                    if (!isBackground) toast(activity, "李田所：签到成功（已显示提醒按钮）");
                                } else if (v2 != null && v2.contains("\"signPlus\":false")) {
                                    signDone.set(true);
                                    if (!isBackground) toast(activity, "李田所：签到成功（签到按钮已消失）");
                                }
                            });
                    } catch (Throwable ignored) {
                    }
                }, 5000);
                }, 5000);
            }
            @Override
            public void onReceivedError(android.webkit.WebView view, int errorCode,
                    String description, String failingUrl) {
                ctxRef.log.warn("auto sign-in: error " + errorCode + " " + description
                        + " url=" + failingUrl);
            }
        });
        // 捕获页面 console 日志，便于调试签到 JS
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                ctxRef.log.info("auto sign-in: console[" + cm.messageLevel() + "] "
                        + cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }
        });
        web.loadUrl(SIGN_URL);
        ctx.log.hitThrottled("auto-signin", "auto sign-in: loading sign page");
        // 20 秒后收尾：页面加载 ~10 秒 + JS 探测 5 秒 + 签到接口执行留量
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Throwable ignored) {
            }
            try {
                if (signDone.get()) {
                    p.setLong(PREF_LAST_SIGN_DAY, Long.parseLong(today));
                    String uin = getCurrentUin();
                    if (!uin.isEmpty()) p.setString(PREF_LAST_SIGN_UIN, uin);
                    ctx.log.info("auto sign-in: done, marked " + today + " uin=" + uin);
                    if (!isBackground) toast(activity, "李田所：今日签到完成");
                } else {
                    ctx.log.warn("auto sign-in: sign not confirmed, not marking today");
                    if (!isBackground) toast(activity, "李田所：签到未确认，明天重试");
                }
            } catch (Throwable ignored) {
            }
            // 后台模式：签到完成后立即 finish Activity，用户几乎无感知
            if (isBackground) {
                try { activity.finish(); activity.overridePendingTransition(0, 0); } catch (Throwable ignored) {}
            }
        }, 35000);
    }

    /** 在主线程安全地显示 Toast。 */
    private static void toast(android.app.Activity activity, String msg) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 当天日期戳 yyyyMMdd（本地时区）。 */
    private static String todayStamp() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int y = c.get(java.util.Calendar.YEAR);
        int mo = c.get(java.util.Calendar.MONTH) + 1;
        int d = c.get(java.util.Calendar.DAY_OF_MONTH);
        return String.format(java.util.Locale.US, "%04d%02d%02d", y, mo, d);
    }

    /**
     * 精确拦截 Kuikly 半屏弹窗（试听付费墙等）。
     *
     * <p>QQ 音乐把「正在试听 完整播放需开通VIP」这类半屏付费墙做成了 Kuikly 页面，
     * 走 {@code com.tme.qqmusic.knative.kuikly.container.KuiklyRenderFragment}。这个
     * Fragment 是<b>所有</b> Kuikly 页面的通用容器（会员中心、音乐馆等都用它），
     * 所以不能按类名拦——那会把正常功能一起干掉。</p>
     *
     * <p>真正的区分靠 {@code arguments} 里的 {@code Key.KuiklyRouterInfo} JSON 中的
     * {@code page_name} 字段。付费墙的 {@code page_name} 恒为
     * {@code dialog_song_popup}（明文、跨版本稳定）。只在命中黑名单时才 dismiss。</p>
     *
     * <p>受面板「试听付费墙」({@code K_PAYWALL}) 开关控制。</p>
     */
    private void installKuiklyDialogBlock(Context ctx) {
        ctx.feature("kuikly-dialog-block", () -> {
            Class<?> fmClass = Reflect.findClassAny(ctx.classLoader(),
                    "androidx.fragment.app.Fragment",
                    "android.app.Fragment");
            if (fmClass == null) {
                throw new ClassNotFoundException("Fragment (for kuikly dialog block)");
            }
            // hook Fragment.onStart：所有 Fragment 都会走，内部按类名 + page_name 精确过滤。
            int n = 0;
            for (Method m : fmClass.getDeclaredMethods()) {
                if (("onStart".equals(m.getName()) || "onResume".equals(m.getName()))
                        && m.getParameterCount() == 0) {
                    ctx.hooks.intercept("kuikly-dialog-block/" + m.getName(), m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (KUIKLY_CONTAINER.equals(self.getClass().getName())
                                    && isKuiklyPageBlocked(self)) {
                                Object r = chain.proceed();
                                dismissFragment(self);
                                ctx.log.hitThrottled("kuikly-block",
                                        "blocked kuikly dialog: dialog_song_popup");
                                return r;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    n++;
                }
            }
            if (n > 0) {
                ctx.log.info("kuikly dialog block installed (" + n + " hooks)");
            }
        });
    }

    /** Kuikly 通用容器 Fragment 全名。 */
    private static final String KUIKLY_CONTAINER =
            "com.tme.qqmusic.knative.kuikly.container.KuiklyRenderFragment";

    /** 要拦掉的 Kuikly page_name 黑名单（明文、跨版本稳定）。 */
    private static final String[] KUIKLY_BLOCKED_PAGES = {
            "dialog_song_popup",   // 试听付费墙：正在试听 完整播放需开通VIP
    };

    /**
     * 判断某个 KuiklyRenderFragment 实例是不是要拦的付费墙页面。
     *
     * <p>读它的 {@code arguments} Bundle 里 {@code Key.KuiklyRouterInfo} 字符串，
     * 里面是一段 JSON，直接做子串匹配找 {@code page_name}——不引 JSON 库、零依赖、够快。</p>
     */
    private boolean isKuiklyPageBlocked(Object fragment) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        // 面板「试听付费墙弹窗（精确拦截）」开关；没勾就不拦。
        if (p == null || !p.get(QqMusicPanel.K_SONGPOPUP, false)) {
            return false;
        }
        try {
            java.lang.reflect.Method getArgs = fragment.getClass().getMethod("getArguments");
            Object bundle = getArgs.invoke(fragment);
            if (!(bundle instanceof android.os.Bundle)) {
                return false;
            }
            Object info = ((android.os.Bundle) bundle).get("Key.KuiklyRouterInfo");
            if (!(info instanceof String)) {
                return false;
            }
            String json = (String) info;
            for (String page : KUIKLY_BLOCKED_PAGES) {
                if (json.contains("\"page_name\":\"" + page + "\"")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 把一个 Fragment 关掉。按可靠性依次尝试：
     * DialogFragment 的 dismissAllowingStateLoss / dismiss，再退回用
     * FragmentManager 事务 remove。任意一个成功即返回。
     */
    private void dismissFragment(Object fragment) {
        // 1. DialogFragment.dismissAllowingStateLoss()
        try {
            java.lang.reflect.Method m =
                    fragment.getClass().getMethod("dismissAllowingStateLoss");
            m.invoke(fragment);
            return;
        } catch (Throwable ignored) {
        }
        // 2. DialogFragment.dismiss()
        try {
            java.lang.reflect.Method m = fragment.getClass().getMethod("dismiss");
            m.invoke(fragment);
            return;
        } catch (Throwable ignored) {
        }
        // 3. getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss()
        try {
            java.lang.reflect.Method getFm =
                    fragment.getClass().getMethod("getParentFragmentManager");
            Object fm = getFm.invoke(fragment);
            java.lang.reflect.Method beginTx = fm.getClass().getMethod("beginTransaction");
            Object tx = beginTx.invoke(fm);
            java.lang.reflect.Method remove = tx.getClass().getMethod(
                    "remove", Reflect.findClassAny(fragment.getClass().getClassLoader(),
                            "androidx.fragment.app.Fragment"));
            Object tx2 = remove.invoke(tx, fragment);
            java.lang.reflect.Method commit =
                    tx2.getClass().getMethod("commitAllowingStateLoss");
            commit.invoke(tx2);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 组件拦截：在 Activity 创建与 startService/bindService 收敛点，拦下用户在面板
     * 勾选的非核心组件（直播、AI、WebView 广告页、后台服务等）。
     *
     * <p>只拦 {@link #isExtraBlocked} 命中的类；未勾选的一律放过。Activity 拦截返回
     * {@code null} 让调用方按「没有结果」处理；服务拦截对 startService 返回 {@code null}、
     * 对 bindService 返回 {@code false}，都不会导致崩溃。</p>
     */
    private void installComponentBlock(Context ctx) {
        ctx.feature("component-block", () -> {
            // 1. Activity 创建：Instrumentation.newActivity(ClassLoader, String, Intent)
            try {
                Class<?> inst = Class.forName("android.app.Instrumentation");
                Method newAct = null;
                for (Method m : inst.getDeclaredMethods()) {
                    if ("newActivity".equals(m.getName())
                            && m.getParameterTypes().length == 3
                            && m.getParameterTypes()[0] == ClassLoader.class
                            && m.getParameterTypes()[1] == String.class) {
                        newAct = m;
                        break;
                    }
                }
                if (newAct != null) {
                    ctx.hooks.intercept("component-block/activity", newAct, chain -> {
                        try {
                            String name = (String) chain.getArg(1);
                            if (name != null && isExtraBlocked(name)) {
                                ctx.log.hitThrottled("block-act:" + name,
                                        "blocked activity: " + name);
                                return null;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    ctx.log.info("component block (activity) installed");
                }
            } catch (Throwable t) {
                ctx.log.error("component block activity failed", t);
            }
            // 2. 服务启动：ContextWrapper.startService(Intent) / bindService(Intent, ...)
            try {
                Class<?> cw = android.content.ContextWrapper.class;
                java.util.List<Method> svcMethods = new java.util.ArrayList<>();
                for (Method m : cw.getDeclaredMethods()) {
                    if (("startService".equals(m.getName()) || "bindService".equals(m.getName()))
                            && m.getParameterTypes().length >= 1
                            && m.getParameterTypes()[0] == android.content.Intent.class) {
                        svcMethods.add(m);
                    }
                }
                for (Method m : svcMethods) {
                    final boolean isStart = "startService".equals(m.getName());
                    ctx.hooks.intercept("component-block/" + m.getName(), m, chain -> {
                        try {
                            Object arg = chain.getArg(0);
                            if (arg instanceof android.content.Intent) {
                                android.content.ComponentName cn =
                                        ((android.content.Intent) arg).getComponent();
                                if (cn != null && isExtraBlocked(cn.getClassName())) {
                                    ctx.log.hitThrottled("block-svc:" + cn.getClassName(),
                                            "blocked service: " + cn.getClassName());
                                    return isStart ? null : Boolean.FALSE;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                }
                ctx.log.info("component block (service) installed on "
                        + svcMethods.size() + " method(s)");
            } catch (Throwable t) {
                ctx.log.error("component block service failed", t);
            }
        });
    }

    /**
     * 启动优化：拦截非核心 ContentProvider 初始化、SharedPreferences 频繁写入、
     * WebView 预加载，减少冷启动耗时。
     */
    private void installStartupOptimization(Context ctx) {
        ctx.feature("startup-opt", () -> {
            // 1. 拦截非核心 ContentProvider.onCreate
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                for (Method m : activityThread.getDeclaredMethods()) {
                    if ("installProvider".equals(m.getName())
                            && m.getParameterCount() >= 2) {
                        ctx.hooks.intercept("startup-opt/provider", m, chain -> {
                            try {
                                com.litiansuo.purifier.hook.LocalPrefs p = prefs;
                                if (p == null || !p.get(QqMusicPanel.K_STARTUP_PROVIDER, false)) {
                                    return chain.proceed();
                                }
                                Object provider = chain.getArg(1);
                                if (provider != null) {
                                    String name = provider.getClass().getName();
                                    if (name.contains("gdt") || name.contains("ams")
                                            || name.contains("tmsdk") || name.contains("chimera")
                                            || name.contains("chromium")) {
                                        ctx.log.info("BLOCKED provider: " + name);
                                        return null;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return chain.proceed();
                        });
                        ctx.log.info("startup-opt (provider) installed");
                        break;
                    }
                }
            } catch (Throwable t) {
                ctx.log.error("startup-opt provider failed", t);
            }
            // 2. 拦截启动阶段的 SharedPreferences.commit（同步写阻塞主线程）
            try {
                Class<?> editorImpl = Class.forName("android.app.SharedPreferencesImpl$EditorImpl");
                for (Method m : editorImpl.getDeclaredMethods()) {
                    if ("commit".equals(m.getName()) && m.getParameterCount() == 0) {
                        ctx.hooks.intercept("startup-opt/commit", m, chain -> {
                            com.litiansuo.purifier.hook.LocalPrefs p = prefs;
                            if (p == null || !p.get(QqMusicPanel.K_STARTUP_SP, false)) {
                                return chain.proceed();
                            }
                            long elapsed = System.currentTimeMillis() - MODULE_LOAD_TIME;
                            if (elapsed < 3000) {
                                ctx.log.info("SP.commit -> apply (elapsed=" + elapsed + "ms)");
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        });
                        ctx.log.info("startup-opt (commit) installed");
                        break;
                    }
                }
            } catch (Throwable t) {
                ctx.log.error("startup-opt commit failed", t);
            }
            // 3. 拦截 WebView 预加载
            try {
                Class<?> webView = Class.forName("android.webkit.WebView");
                for (Method m : webView.getDeclaredMethods()) {
                    if ("getInstance".equals(m.getName()) || "getDefaultViewModelStoreOwner".equals(m.getName())) {
                        ctx.hooks.intercept("startup-opt/webview", m, chain -> {
                            com.litiansuo.purifier.hook.LocalPrefs p = prefs;
                            if (p == null || !p.get(QqMusicPanel.K_STARTUP_WEBVIEW, false)) {
                                return chain.proceed();
                            }
                            long elapsed = System.currentTimeMillis() - MODULE_LOAD_TIME;
                            if (elapsed < 5000) {
                                ctx.log.info("BLOCKED webview pre-init (elapsed=" + elapsed + "ms)");
                                return null;
                            }
                            return chain.proceed();
                        });
                        ctx.log.info("startup-opt (webview) installed");
                        break;
                    }
                }
            } catch (Throwable t) {
                ctx.log.error("startup-opt webview failed", t);
            }
        });
    }

    /**
     * 打开面板开关的存储。
     *
     * <p>必须在 late 阶段：需要 Application context 才能拿到目标应用的私有 prefs 目录。
     * 失败不抛——面板开关是增强项，读不到时其它规则照常工作。</p>
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

    /**
     * 类名是否被用户在面板里勾选的条目命中。
     *
     * <p>{@code prefs} 为 null（读不到本地存储）时一律返回 false：
     * 面板开关全是默认关的增强项，读不到就按「没勾」处理，不能反过来擅自开启。</p>
     */
    private boolean isExtraBlocked(String name) {
        com.litiansuo.purifier.hook.LocalPrefs p = this.prefs;
        if (p == null || name == null) {
            return false;
        }
        for (String[] group : EXTRA_PREFIXES) {
            if (!p.get(group[0], false)) {
                continue;
            }
            for (int i = 1; i < group.length; i++) {
                if (name.startsWith(group[i])) {
                    return true;
                }
            }
        }
        return false;
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
                        ctx.log.hitThrottled("view:" + c.getName(), "hid ad view: " + c.getName());
                    } else if (isExtraBlocked(c.getName())) {
                        v.setVisibility(View.GONE);
                        ctx.log.hitThrottled("extra:" + c.getName(), "hid (panel) " + c.getName());
                    }
                }
                return chain.proceed();
            });

            // 补充：有些提示条（如 MinibarTopTipView）常驻可见、只靠 setText 换文案轮播，
            // 从不走 setVisibility(VISIBLE)，也未必每次都重新 addView，靠上面两条抓不住。
            // 再 hook View.onAttachedToWindow：只要被拦类的实例一挂上窗口就立刻 GONE，
            // 从根上让整块提示条消失，与它显示什么文字无关。
            try {
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
                                if (isAdClass(name) || isExtraBlocked(name)) {
                                    v.setVisibility(View.GONE);
                                    ctx.log.hitThrottled("attached:" + name,
                                            "hid (attached) " + name);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return r;
                    });
                    ctx.log.info("attached-to-window hide installed");
                }
            } catch (Throwable t) {
                ctx.log.error("attached-to-window hide failed", t);
            }

            // 终极保险：禁止被拦类「翻身」。有些提示条（MinibarTopTipView）被 GONE 后，
            // QQ 音乐会在 layout 阶段又调 setVisibility(VISIBLE) 把它设回来。这里 hook
            // View.setVisibility：只要 self 是被拦类、且想设成 VISIBLE，就直接吞掉这次调用
            // （return 不 proceed），它永远变不回可见。吞掉 VISIBLE 不会触发递归。
            try {
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
                                    if (isAdClass(name) || isExtraBlocked(name)) {
                                        // 吞掉这次「设为可见」，改设为 GONE 保持隐藏
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
                    ctx.log.info("lock-gone installed");
                }
            } catch (Throwable t) {
                ctx.log.error("lock-gone failed", t);
            }
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
                    String cn = self == null ? null : self.getClass().getName();
                    // 固定规则命中，或用户在面板里勾了对应条目（如试听付费墙）
                    if (cn != null && (isAdClass(cn) || isExtraBlocked(cn))) {
                        ctx.log.hitThrottled("dialog:" + cn, "blocked ad dialog: " + cn);
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
     *
     * <h3>为什么这里必须用节流日志</h3>
     *
     * <p>首轮实测 {@code initWith} 十分钟被拦 <b>1950 次</b>，日志涨到 308 KB。
     * 原因在反编译源码里很清楚：{@code initWith} 靠实例字段（{@code Boolean f368a}）
     * 判断「已初始化」并直接返回 true，而我们返回 false 时并没有置那个字段，
     * 所以调用方每次都认为初始化失败、下次继续重试。</p>
     *
     * <p><b>没有改成返回 true + 反射置那个字段</b>：那会让 SDK 在
     * {@code PM}、{@code APPStatus}、{@code SM} 全为 null 的状态下自认已就绪，
     * 后续任何 {@code getPM().getPOFactory()} 都会 NPE。让一个广告 SDK 反复重试是
     * 浪费，让它在半初始化状态下运行是<b>把目标应用推向崩溃</b>，两者不是一个量级。</p>
     *
     * <p>所以这里只治日志：命中按 10 的量级记（第 1、10、100… 次），并在首次命中时
     * 记一次调用栈，用来定性到底是谁在重试。栈只取一次——取栈本身不便宜。</p>
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
                final String hitKey = featureId + "/" + m.getParameterCount();
                ctx.hooks.intercept(hitKey, m, chain -> {
                    long times = ctx.log.hitThrottled(
                            hitKey, shortName + "#" + methodName + " -> blocked");
                    if (times == 1) {
                        // 只在首次记一次调用栈：用来定性重试来源，之后不再付这个开销
                        ctx.log.info(shortName + "#" + methodName + " caller: "
                                + XLog.callerSummary(6));
                    }
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

    // ------------------------------------------------------------ 李田所入口

    /**
     * 在「关于 QQ 音乐」连点版本号时插入「李田所」入口。
     *
     * <p>入口插在「QQ 音乐小诊所」所在的那一行下面。连点 6 次版本号后
     * 诊断入口会显示出来，此时我们插入面板入口。</p>
     */
    private void installAboutEntry(Context ctx) {
        ctx.feature(FEAT_ABOUT_ENTRY, () -> {
            Class<?> cls = Reflect.findClass(ctx.classLoader(),
                    "com.tencent.qqmusic.fragment.morefeatures.AboutFragment");
            if (cls == null) {
                throw new ClassNotFoundException(
                        "com.tencent.qqmusic.fragment.morefeatures.AboutFragment");
            }
            Method onClick = Reflect.method(cls, "onClick", View.class);
            ctx.hooks.intercept(FEAT_ABOUT_ENTRY, onClick, chain -> {
                Object arg = chain.getArg(0);
                View v = (arg instanceof View) ? (View) arg : null;
                if (v == null) {
                    return chain.proceed();
                }
                int id = v.getId();
                // 诊断:每次 onClick 触发都记录 view id,确认 hook 生效
                ctx.log.info("ABOUT onClick: id=" + id + " class=" + v.getClass().getSimpleName());
                // ---------------------------------------------------------
                // 1. 点击 QQ音乐 logo (2131297430) 或 版本号 (2131309413)
                //    累计 7 次 → 弹出李田所设置面板
                // ---------------------------------------------------------
                if (id == 2131297430 || id == 2131309413) {
                    Object result = chain.proceed();
                    try {
                        int c = LOGO_CLICK_COUNT.incrementAndGet();
                        ctx.log.info("LOGO clicks so far: " + c);
                        if (c >= 7) {
                            LOGO_CLICK_COUNT.set(0);
                            // 用 v 的 Context 弹面板,在同一 activity 里
                            QqMusicPanel.showPanel(v.getContext(), prefs, ctx.log);
                            ctx.log.hit("lts panel shown by logo 7 clicks");
                        }
                    } catch (Throwable t) {
                        ctx.log.error("logo click handler failed", t);
                    }
                    return result;
                }
                // ---------------------------------------------------------
                // 2. 点击版本号 (2131309413) — 原样放行(诊断入口由 app 自己管理)
                // ---------------------------------------------------------
                return chain.proceed();
            });
            ctx.log.info("about entry hook installed");
            // 离开页面(onDestroyView)时重置计数,保证下次进入重新点 7 次
            Method onDestroyView = Reflect.method(cls, "onDestroyView");
            if (onDestroyView != null) {
                ctx.hooks.intercept(FEAT_ABOUT_ENTRY + "/reset-destroy", onDestroyView, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (LOGO_CLICK_COUNT.get() > 0) {
                            LOGO_CLICK_COUNT.set(0);
                            ctx.log.info("about entry counter reset on destroyView");
                        }
                    } catch (Throwable t) {
                        ctx.log.error("about entry reset failed", t);
                    }
                    return result;
                });
            }
            // 进入页面(onResume)也重置,避免上次残留值导致"点 6 下就出来"
            Method onResume = Reflect.method(cls, "onResume");
            if (onResume != null) {
                ctx.hooks.intercept(FEAT_ABOUT_ENTRY + "/reset-resume", onResume, chain -> {
                    Object result = chain.proceed();
                    try {
                        LOGO_CLICK_COUNT.set(0);
                        ctx.log.info("about entry counter reset on resume");
                    } catch (Throwable t) {
                        ctx.log.error("about entry reset failed", t);
                    }
                    return result;
                });
            }
        });
    }

    /** 连续点击 logo 的计数器(类级别,跨 fragment 实例) */
    private static final java.util.concurrent.atomic.AtomicInteger LOGO_CLICK_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);


}
