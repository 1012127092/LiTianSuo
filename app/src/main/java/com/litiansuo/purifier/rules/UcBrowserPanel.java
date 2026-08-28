package com.litiansuo.purifier.rules;

import android.content.Context;

import com.litiansuo.purifier.hook.LocalPrefs;
import com.litiansuo.purifier.hook.XLog;
import com.litiansuo.purifier.rules.panel.PanelDialog;

/**
 * 注入到 UC 浏览器「设置」页的「李田所」入口与设置面板。
 *
 * <p>入口：在设置页底部版本号那行「UC浏览器 V19.0.0.1536」连点 7 次弹出面板
 * （{@link UcBrowserRules} 里按文字匹配挂连点监听）。</p>
 *
 * <p>所有面板均通过 {@link PanelDialog} 统一样式渲染，禁止直接使用
 * {@code new AlertDialog()}。</p>
 */
final class UcBrowserPanel {

    private UcBrowserPanel() {
    }

    // ------------------------------------------------------------ 本地开关键名

    /** 推送保活：拦截推送/保活相关 Service 启动。 */
    static final String K_PUSH = "uc.block.push";
    /** 信息流广告：隐藏首页/频道信息流里嵌入的广告卡（Noah/afp/newafp/jilivideo 等）。 */
    static final String K_FEEDAD = "uc.block.feedad";
    /** 小说内广告：隐藏 UC 小说页内插入的广告 View。 */
    static final String K_NOVELAD = "uc.block.novelad";
    /** 弹窗广告：拦截 com.uc.application.ad 下其他弹窗 Activity。 */
    static final String K_POPUP = "uc.block.popup";
    /**
     * 统计/上报 Service 拦截：UC 自家数据上报、UBox 埋点、穿山甲 Provider 模板缓存。
     * 主人当纯网盘用，这类后台发包是隐私 + 流量浪费。默认开。
     */
    static final String K_STAT = "uc.block.stat";
    /**
     * 游戏中心入口拦截：拦截 MiniGameActivity 启动（UC 内置小游戏）。默认开。
     */
    static final String K_MINIGAME = "uc.block.minigame";
    /**
     * 启动加速：拦截开屏 Activity、开机自启 Service，减少首屏渲染时间。默认开。
     */
    static final String K_LAUNCH = "uc.block.launch";
    /**
     * 隐藏底栏「首页」tab。默认开。
     */
    static final String K_HIDE_HOME = "uc.tab.hide.home";
    /**
     * 隐藏底栏「短剧」tab。默认开。
     */
    static final String K_HIDE_SHORT = "uc.tab.hide.short";
    /**
     * 「我的」页面多入口净化：隐藏芭芭农场/福利猪领元宝/我的书架/我的直播/我的游戏
     * /菜单栏常用AI应用/UC松鼠大战等非网盘入口。默认开。
     */
    static final String K_MY_PAGE = "uc.block.my_page";
    /** 全栈活动 dump：把 UC 所有 Activity/Service/View 打点到 logcat。默认关。 */
    static final String K_DUMP = "uc.debug.dump";
    /** 网盘设为主页：启动后自动切换到网盘 tab。默认开。 */
    static final String K_NETDISK_HOME = "uc.netdisk.home";
    /** 个性推荐弹窗拦截：隐藏"个性推荐获得更丰富内容"等引导浮层。默认开。 */
    static final String K_POPUP_BLOCK = "uc.block.popup";
    /** CrashRecovery 拦截：禁用网盘页面崩溃恢复，解决来回闪烁。默认开。 */
    static final String K_CRASH_RECOVERY = "uc.block.crash_recovery";

    /** 「后台与推送」二级勾选项。 */
    private static final String[][] BACKGROUND_ITEMS = {
            {K_PUSH, "推送保活拦截",
                    "拦截 UC 自家及小米/华为/OPPO/vivo/魅族/荣耀推送与保活服务，"
                            + "减少后台唤醒和推送通知。勾选后重启 UC 生效"},
    };

    /** 「广告净化」二级勾选项。默认全部开启。 */
    private static final String[][] AD_ITEMS = {
            {K_FEEDAD, "信息流广告",
                    "隐藏首页/频道信息流里嵌入的广告卡（Noah/afp/newafp/即刻视频/段子）。"
                            + "勾选后即时生效"},
            {K_NOVELAD, "小说内广告",
                    "隐藏 UC 小说阅读页/书架里插入的广告 View。勾选后即时生效"},
            {K_POPUP, "广告弹窗",
                    "拦截 UC 自家广告 SDK 的弹窗 Activity。勾选后即时生效"},
            {K_STAT, "统计上报拦截",
                    "拦截 UC 自家统计 Service、UBox 埋点、穿山甲广告 Provider。"
                            + "减少后台发包，保护隐私。勾选后即时生效"},
            {K_MINIGAME, "游戏中心入口拦截",
                    "拦截 UC 内置小游戏 Activity 启动。勾选后即时生效"},
    };

    /** 「界面简化」二级勾选项。默认全部开启（主人只要网盘）。 */
    private static final String[][] UI_ITEMS = {
            {K_HIDE_HOME, "隐藏底栏「首页」",
                    "隐藏底部导航栏的「首页」tab。勾选后即时生效"},
            {K_HIDE_SHORT, "隐藏底栏「短剧」",
                    "隐藏底部导航栏的「短剧」tab。勾选后即时生效"},
            {K_MY_PAGE, "我的页面净化",
                    "隐藏「我的」页里的芭芭农场/福利猪领元宝/我的书架/我的直播/我的游戏，"
                            + "以及菜单栏里的常用AI应用/UC松鼠大战等非网盘入口。勾选后即时生效"},
            {K_NETDISK_HOME, "网盘设为主页",
                    "启动 UC 后自动切换到网盘页面，不显示首页信息流。勾选后重启 UC 生效"},
            {K_POPUP_BLOCK, "个性推荐弹窗拦截",
                    "隐藏网盘页面\"个性推荐获得更丰富内容\"等引导浮层。勾选后即时生效"},
            {K_CRASH_RECOVERY, "网盘页面防闪",
                    "禁用 UC 崩溃恢复逻辑，解决网盘页面有时来回闪烁的问题。勾选后重启 UC 生效"},
            {K_DUMP, "全栈活动 dump（调试）",
                    "把 UC 所有 Activity/Service/View 打点到 logcat（tag=LiTianSuo）。"
                            + "日志量很大，仅调试时开启。勾选后重启 UC 生效"},
    };

    // ------------------------------------------------------------ 面板

    /** 第一级：「李田所」面板。 */
    static void showPanel(Context ctx, LocalPrefs prefs, XLog log) {
        try {
            PanelDialog.MenuItem[] items = {
                    new PanelDialog.MenuItem("后台与推送",
                            "拦截推送/保活服务，减少后台唤醒，需重启 UC 生效",
                            v -> showBackground(v.getContext(), prefs, log)),
                    new PanelDialog.MenuItem("广告净化",
                            "隐藏 UC 浏览器各处的广告卡/弹窗（信息流/小说/广告弹窗），即时生效",
                            v -> showAdPurify(v.getContext(), prefs, log)),
                    new PanelDialog.MenuItem("界面与启动",
                            "底栏 tab 隐藏、启动加速、减少卡顿发热。即时生效",
                            v -> showUiPurify(v.getContext(), prefs, log)),
            };
            PanelDialog.showLevelOne(ctx, prefs, log, "李田所", items);
            if (log != null) log.hit("uc panel shown");
        } catch (Throwable t) {
            if (log != null) log.error("failed to show uc panel", t);
        }
    }

    /** 第二级：「后台与推送」勾选列表。 */
    private static void showBackground(Context ctx, LocalPrefs prefs, XLog log) {
        int on = 0;
        for (String[] it : BACKGROUND_ITEMS) {
            if (prefs != null && prefs.get(it[0], false)) on++;
        }
        PanelDialog.CheckItem[] ci = new PanelDialog.CheckItem[BACKGROUND_ITEMS.length];
        for (int i = 0; i < BACKGROUND_ITEMS.length; i++) {
            ci[i] = new PanelDialog.CheckItem(
                    BACKGROUND_ITEMS[i][0], BACKGROUND_ITEMS[i][1], BACKGROUND_ITEMS[i][2]);
        }
        PanelDialog.showLevelTwo(ctx, prefs, log,
                "后台与推送（已开 " + on + " / 共 " + BACKGROUND_ITEMS.length + "）",
                "勾选后拦截对应推送/保活服务。改动需重启 UC 生效。",
                ci);
    }

    /** 第二级：「广告净化」勾选列表。 */
    private static void showAdPurify(Context ctx, LocalPrefs prefs, XLog log) {
        int on = 0;
        for (String[] it : AD_ITEMS) {
            if (prefs != null && prefs.get(it[0], false)) on++;
        }
        PanelDialog.CheckItem[] ci = new PanelDialog.CheckItem[AD_ITEMS.length];
        for (int i = 0; i < AD_ITEMS.length; i++) {
            ci[i] = new PanelDialog.CheckItem(
                    AD_ITEMS[i][0], AD_ITEMS[i][1], AD_ITEMS[i][2]);
        }
        PanelDialog.showLevelTwo(ctx, prefs, log,
                "广告净化（已开 " + on + " / 共 " + AD_ITEMS.length + "）",
                "勾选后即时生效。信息流/小说广告是隐藏广告 View 视图，"
                        + "广告弹窗是拦截启动的 Activity。",
                ci);
    }

    /** 第二级：「界面与启动」勾选列表。 */
    private static void showUiPurify(Context ctx, LocalPrefs prefs, XLog log) {
        // 合并 K_LAUNCH + UI_ITEMS 两组
        String[][] all = new String[UI_ITEMS.length + 1][];
        all[0] = new String[]{K_LAUNCH, "启动加速",
                "拦截开屏 Activity、开机自启 Service，减少首屏渲染时间。勾选后即时生效"};
        for (int i = 0; i < UI_ITEMS.length; i++) {
            all[i + 1] = UI_ITEMS[i];
        }
        int on = 0;
        for (String[] it : all) {
            if (prefs != null && prefs.get(it[0], false)) on++;
        }
        PanelDialog.CheckItem[] ci = new PanelDialog.CheckItem[all.length];
        for (int i = 0; i < all.length; i++) {
            ci[i] = new PanelDialog.CheckItem(all[i][0], all[i][1], all[i][2]);
        }
        PanelDialog.showLevelTwo(ctx, prefs, log,
                "界面与启动（已开 " + on + " / 共 " + all.length + "）",
                "勾选后即时生效。重启 UC 后启动加速效果最明显。",
                ci);
    }
}
