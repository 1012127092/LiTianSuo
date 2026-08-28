package com.litiansuo.purifier.rules;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.litiansuo.purifier.hook.LocalPrefs;
import com.litiansuo.purifier.hook.XLog;
import com.litiansuo.purifier.rules.panel.PanelDialog;

/**
 * 注入到 QQ 音乐「关于」页的「李田所」入口与设置面板。
 *
 * <p>入口插在「QQ音乐小诊所」那一行下面。用户在「关于」页连点版本号 6 次后，
 * 诊断入口（小诊所/日志上传/最近播放状态）会显示出来，此时我们把「李田所」行插进去。</p>
 */
final class QqMusicPanel {

    private QqMusicPanel() {
    }

    /** 入口行的 tag，用于判断是否已经插过。 */
    private static final String ROW_TAG = "lts-entry-row";

    /** 面板标题。 */
    private static final String TITLE = "李田所";

    // ------------------------------------------------------------ 本地开关键名

    /** 「屏蔽更多页面」条目的开关键名。默认关。 */
    static final String K_PAYWALL = "block.paywall";
    static final String K_FREEMODE = "block.freemode";
    static final String K_REWARD = "block.reward";
    static final String K_VIPEARN = "block.vipearning";
    static final String K_RECVIP = "block.recvip";
    static final String K_QPLAY = "block.qplay";
    static final String K_DEVICEID = "block.deviceid";
    static final String K_SECURITY = "block.security";
    static final String K_LIVE = "block.live";
    static final String K_WEBVIEW = "block.webview";
    static final String K_AIAGENT = "block.aiagent";
    static final String K_GMS = "block.gms";
    static final String K_WEBVIEWSVR = "block.webviewsvr";
    static final String K_STARTUP_PROVIDER = "block.startup.provider";
    static final String K_STARTUP_SP = "block.startup.sp";
    static final String K_STARTUP_WEBVIEW = "block.startup.webview";
    /** Kuikly 半屏付费墙弹窗（dialog_song_popup）精确拦截。默认关。 */
    static final String K_SONGPOPUP = "block.songpopup";
    /** 迷你播放器顶部提示条（DTS/会员推广）。默认关。 */
    static final String K_MINIBAR_TIP = "block.minibartip";
    /** 顶部 VIP 提示条（TopVipAdBar，「限免/VIP限时免费」提示）。默认关。 */
    static final String K_TOPVIPBAR = "block.topvipbar";
    /** 每日自动签到。默认关。 */
    static final String K_AUTO_SIGNIN = "auto.signin";
    /** 弹窗拦截（签到领金币、免费听推广、今日灵感等广告弹窗）。默认关。 */
    static final String K_POPUP_BLOCK = "block.popup";

    /** 条目定义：键名、标题、说明。 */
    private static final String[][] ITEMS = {
            {K_PAYWALL, "试听付费墙（半屏弹窗）",
                    "点播放时弹出的「试听中，开通会员畅听全曲」半屏面板"},
            {K_FREEMODE, "免费听模式入口",
                    "「看广告免费听 30 分钟」那一类入口。关掉后就没有换时长的途径了"},
            {K_REWARD, "激励视频",
                    "用户主动点「看广告得奖励」时播放的视频"},
            {K_VIPEARN, "会员赚取模式",
                    "做任务/看广告攒会员时长的页面"},
            {K_RECVIP, "会员歌曲推荐位",
                    "歌单里「含 N 首会员歌曲 开通会员畅听」这类推荐条"},
    };

    // ------------------------------------------------------------ 屏蔽组件活动

    /**
     * 「屏蔽组件活动」的可勾选项 = 不影响听歌的组件/活动开关。
     *
     * <p>键名复用 {@code K_*} 常量,对应的类名前缀拦截已在
     * {@code QqMusicRules.EXTRA_PREFIXES} 接好,勾选即生效。</p>
     */
    static final String[][] COMPONENT_ITEMS = {
            {K_POPUP_BLOCK, "广告弹窗拦截",
                    "拦截「签到领金币」「免费听推广」「今日灵感」等广告弹窗，按文本关键词匹配"},
            {K_SONGPOPUP, "试听付费墙弹窗（精确拦截）",
                    "点歌时弹的「正在试听 完整播放需开通VIP」半屏弹窗，只拦它不误伤其它页面"},
            {K_PAYWALL, "试听付费墙（旧·按类名）",
                    "老规则：按 vipguide 类名前缀拦付费墙相关视图"},
            {K_FREEMODE, "免费听模式入口",
                    "「看广告免费听 30 分钟」那一类入口"},
            {K_REWARD, "激励视频",
                    "用户主动点「看广告得奖励」时播放的视频"},
            {K_VIPEARN, "会员赚取模式",
                    "做任务/看广告攒会员时长的页面"},
            {K_RECVIP, "会员歌曲推荐位",
                    "歌单里「含 N 首会员歌曲 开通会员畅听」这类推荐条"},
            {K_MINIBAR_TIP, "迷你播放器提示条",
                    "小播放器上方推 DTS/会员的提示条（minibarviptips）"},
            {K_TOPVIPBAR, "顶部 VIP 提示条",
                    "歌单/专辑顶部「限免·VIP 限时免费收听中」提示条，纯提示，拦掉不影响播放"},
            {K_QPLAY, "QPlay 投放服务",
                    "投屏到其他设备，关掉后无法投屏"},
            {K_SECURITY, "腾讯安全 SDK",
                    "tmsdk 后台服务，减少后台开销"},
            {K_LIVE, "直播框架",
                    "直播相关服务和页面，关掉后直播功能不可用"},
            {K_WEBVIEW, "WebView 广告/落地页",
                    "WebViewActivity（广告/推广页容器）"},
            {K_AIAGENT, "AI Agent 页面",
                    "AI 智能助手页面"},
            {K_GMS, "Google Play Services",
                    "Google 服务后台绑定，减少后台开销"},
            {K_WEBVIEWSVR, "WebView 后台服务",
                    "WebView 变体种子和指标上报服务"},
    };

    /** 「启动速度优化」的开关。 */
    private static final String[][] STARTUP_ITEMS = {
            {K_STARTUP_PROVIDER, "拦截非核心 Provider",
                    "拦截广告/统计 SDK 的 ContentProvider 初始化，加速冷启动"},
            {K_STARTUP_SP, "SharedPreferences 异步化",
                    "启动前3秒把 commit 改 apply，减少主线程磁盘阻塞"},
            {K_STARTUP_WEBVIEW, "延迟 WebView 预加载",
                    "启动前5秒拦截 WebView 预初始化，按需加载"},
    };

    // ------------------------------------------------------------ 入口注入

    /**
     * 确保诊断入口区域里有我们那一行。
     *
     * <p>幂等：已经插过就直接返回。位置在<b>整组诊断入口的最后</b>。</p>
     *
     * @param anchor 样板行（已显示的诊断入口之一），我们抄它的样式
     * @return 是否本次新插入
     */
    static boolean ensureRow(View anchor, LocalPrefs prefs, XLog log) {
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) {
            return false;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();

        // 已经有了就不重复插
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (ROW_TAG.equals(parent.getChildAt(i).getTag())) {
                return false;
            }
        }

        Context ctx = anchor.getContext();
        // 记录 anchor 行的结构,用于克隆其左侧图标/间距
        int lastIdx = (anchor instanceof ViewGroup) ? ((ViewGroup) anchor).getChildCount() - 1 : -1;
        // 标题:直接抄 anchor 行第一个可见 TextView 的样式
        TextView title = new TextView(ctx);
        title.setText(TITLE);
        Sample s = sampleFrom(anchor);
        // 诊断:把 anchor 行的真实结构打出来,便于定位左对齐/间距问题
        if (log != null) {
            StringBuilder d = new StringBuilder();
            d.append("ENTRY DIAG anchor=").append(anchor.getClass().getSimpleName());
            d.append(" padL=").append(anchor.getPaddingLeft());
            d.append(" padT=").append(anchor.getPaddingTop());
            d.append(" padR=").append(anchor.getPaddingRight());
            d.append(" padB=").append(anchor.getPaddingBottom());
            d.append(" h=").append(anchor.getHeight());
            d.append(" bg=").append(anchor.getBackground() != null);
            d.append(" count=").append(((ViewGroup) anchor).getChildCount());
            if (anchor instanceof ViewGroup) {
                ViewGroup ag = (ViewGroup) anchor;
                for (int i = 0; i < ag.getChildCount(); i++) {
                    View c = ag.getChildAt(i);
                    d.append(" | ").append(i).append(":")
                            .append(c.getClass().getSimpleName())
                            .append("(v=").append(c.getVisibility()).append(")");
                    if (c instanceof TextView) {
                        CharSequence t = ((TextView) c).getText();
                        d.append(" [\"").append(t == null ? "" : t).append("\"]");
                    }
                }
            }
            log.info(d.toString());
        }
        if (s.title != null) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s.title.getTextSize());
            title.setTextColor(s.title.getTextColors());
            title.setTypeface(s.title.getTypeface());
            title.setGravity(s.title.getGravity());
            title.setLineSpacing(s.title.getLineSpacingExtra(), s.title.getLineSpacingMultiplier());
            title.setIncludeFontPadding(s.title.getIncludeFontPadding());
            title.setMinHeight(s.title.getMinHeight());
        } else {
            title.setTextSize(16f);
        }
        // 但 title 要放在一个容器里,占位和 anchor 行的文本容器一样
        // 所以先复制 anchor 行除最后一个子(箭头)以外的所有子,
        // 然后把其中第一个可见 TextView 换成我们的 title
        ViewGroup row = buildRowLike(ctx, anchor, title, s);

        // 箭头:克隆 anchor 行最右侧的 View
        if (lastIdx >= 0 && s.arrow != null) {
            try {
                View arrow = cloneView(ctx, s.arrow);
                if (arrow != null) {
                    arrow.setPadding(s.arrow.getPaddingLeft(), s.arrow.getPaddingTop(),
                            s.arrow.getPaddingRight(), s.arrow.getPaddingBottom());
                    if (s.arrow.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mp =
                                (ViewGroup.MarginLayoutParams) s.arrow.getLayoutParams();
                        arrow.setLayoutParams(new ViewGroup.MarginLayoutParams(
                                mp.width, mp.height) {{
                                    setMargins(mp.leftMargin, mp.topMargin,
                                            mp.rightMargin, mp.bottomMargin);
                                }});
                    }
                    row.addView(arrow);
                }
            } catch (Throwable t) {
                // 忽略
            }
        }

        row.setOnClickListener(v -> showPanel(v.getContext(), prefs, log));

        // 追加到列表最末尾,确保排在所有诊断入口(含打开调试模式)之后
        parent.addView(row);
        return true;
    }

    /**
     * 移除已插入的入口行。离开关于页时调用,让入口消失。
     */
    static void removeEntry(View anchor) {
        if (anchor == null) {
            return;
        }
        View root = anchor.getRootView();
        if (root instanceof ViewGroup) {
            removeTagged((ViewGroup) root);
        }
    }

    private static void removeTagged(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (ROW_TAG.equals(c.getTag())) {
                g.removeView(c);
                i--;
                continue;
            }
            if (c instanceof ViewGroup) {
                removeTagged((ViewGroup) c);
            }
        }
    }

    private static ViewGroup buildRowLike(Context ctx, View anchor, TextView titleView, Sample s) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        if (anchor.getBackground() != null) {
            row.setBackground(anchor.getBackground());
        }
        // 克隆 anchor 行完整的内边距(含上下→解决间距,左右→解决左边距/箭头对齐)
        row.setPadding(anchor.getPaddingLeft(), anchor.getPaddingTop(),
                anchor.getPaddingRight(), anchor.getPaddingBottom());
        ViewGroup.LayoutParams alp = anchor.getLayoutParams();
        row.setLayoutParams(alp != null ? cloneParams(alp) : new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 行高对齐:让新行的最小高度 = anchor 行的实际高度,和小诊所/日志上传一致
        int anchorH = anchor.getHeight();
        if (anchorH <= 0) anchorH = anchor.getMeasuredHeight();
        if (anchorH > 0) {
            row.setMinimumHeight(anchorH);
        }

        // 克隆 anchor 行左侧的 icon 占位(非文本的子),保持左对齐
        // 文本容器(含标题/副标题)不克隆,只放我们的 titleView
        if (anchor instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) anchor;
            int last = g.getChildCount() - 1;  // 最后一个子是箭头,留到 ensureRow 加
            for (int i = 0; i < last; i++) {
                View child = g.getChildAt(i);
                // 如果这个子本身含可见文本(标题/副标题),跳过→用我们的 titleView 替代
                if (child instanceof TextView && ((TextView) child).getText() != null
                        && ((TextView) child).getText().length() > 0) {
                    continue;
                }
                if (child instanceof ViewGroup) {
                    // 检查是否包含可见文本(可能是文本容器)
                    boolean hasText = false;
                    ViewGroup vg = (ViewGroup) child;
                    for (int j = 0; j < vg.getChildCount(); j++) {
                        View c = vg.getChildAt(j);
                        if (c instanceof TextView && c.getVisibility() == View.VISIBLE
                                && ((TextView) c).getText() != null
                                && ((TextView) c).getText().length() > 0) {
                            hasText = true;
                            break;
                        }
                    }
                    if (hasText) continue;  // 文本容器,跳过
                }
                // 非文本的子(icon 等),克隆作为占位
                View cloned = cloneView(ctx, child);
                if (cloned != null) {
                    row.addView(cloned);
                }
            }
        }

        // 只要「李田所」三个字,不要任何副标题小字
        row.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * 从 anchor 行的可视结构中提取:title TextView 和箭头 View。
     */
    private static final class Sample {
        TextView title;
        View arrow;
    }

    private static Sample sampleFrom(View anchor) {
        Sample s = new Sample();
        if (!(anchor instanceof ViewGroup)) {
            return s;
        }
        ViewGroup g = (ViewGroup) anchor;
        // 递归找整行里第一个可见且有文本的 TextView(嵌套结构也能拿到)
        s.title = findFirstVisibleText(g);
        // arrow = 最后一个子 View
        int childCount = g.getChildCount();
        if (childCount > 0) {
            s.arrow = g.getChildAt(childCount - 1);
        }
        return s;
    }

    private static TextView findFirstVisibleText(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getVisibility() == View.VISIBLE && t.getText() != null
                    && t.getText().length() > 0) {
                return t;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findFirstVisibleText(g.getChildAt(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static View cloneView(Context ctx, View src) {
        if (src instanceof android.widget.TextView) {
            android.widget.TextView st = (android.widget.TextView) src;
            android.widget.TextView nt = new android.widget.TextView(ctx);
            nt.setText(st.getText());
            nt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, st.getTextSize());
            nt.setTextColor(st.getTextColors());
            return nt;
        }
        if (src instanceof android.widget.ImageView) {
            android.widget.ImageView si = (android.widget.ImageView) src;
            android.widget.ImageView ni = new android.widget.ImageView(ctx);
            if (si.getDrawable() != null) {
                ni.setImageDrawable(si.getDrawable());
            }
            ni.setLayoutParams(si.getLayoutParams());
            return ni;
        }
        return null;
    }

    private static ViewGroup.LayoutParams cloneParams(ViewGroup.LayoutParams src) {
        if (src instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src);
        }
        if (src instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) src);
        }
        return new ViewGroup.LayoutParams(src);
    }

    // ------------------------------------------------------------ 设置面板（委托给 PanelDialog 统一渲染）

    /** 第一级：「李田所」面板。全部通过 PanelDialog 统一样式。 */
    static void showPanel(Context ctx, LocalPrefs prefs, XLog log) {
        try {
            PanelDialog.MenuItem[] items = {
                    new PanelDialog.MenuItem("屏蔽组件活动",
                            "不影响听歌的组件/活动，勾选即拦截",
                            v -> showComponents(v.getContext(), prefs, log)),
                    new PanelDialog.MenuItem("启动速度优化",
                            "减少冷启动耗时，需要重启生效",
                            v -> showStartup(v.getContext(), prefs, log)),
                    new PanelDialog.MenuItem("每日自动签到",
                            "开启后每次启动 App 自动完成金币中心日签",
                            v -> showAutoSignIn(v.getContext(), prefs, log)),
            };
            PanelDialog.showLevelOne(ctx, prefs, log, "李田所", items);
            if (log != null) log.hit("qq panel shown");
        } catch (Throwable t) {
            if (log != null) log.error("failed to show qq panel", t);
        }
    }

    /** 第二级：「屏蔽组件活动」勾选列表。 */
    private static void showComponents(Context ctx, LocalPrefs prefs, XLog log) {
        int on = 0;
        for (String[] it : COMPONENT_ITEMS) {
            if (prefs != null && prefs.get(it[0], false)) on++;
        }
        PanelDialog.CheckItem[] ci = new PanelDialog.CheckItem[COMPONENT_ITEMS.length];
        for (int i = 0; i < COMPONENT_ITEMS.length; i++) {
            ci[i] = new PanelDialog.CheckItem(
                    COMPONENT_ITEMS[i][0], COMPONENT_ITEMS[i][1], COMPONENT_ITEMS[i][2]);
        }
        PanelDialog.showLevelTwo(ctx, prefs, log,
                "屏蔽组件活动（已开 " + on + " / 共 " + COMPONENT_ITEMS.length + "）",
                "这些组件/活动都不影响听歌。勾选后立即拦截，不用重启。",
                ci);
    }

    /** 第二级：「启动速度优化」勾选列表。 */
    private static void showStartup(Context ctx, LocalPrefs prefs, XLog log) {
        int on = 0;
        for (String[] it : STARTUP_ITEMS) {
            if (prefs != null && prefs.get(it[0], false)) on++;
        }
        PanelDialog.CheckItem[] ci = new PanelDialog.CheckItem[STARTUP_ITEMS.length];
        for (int i = 0; i < STARTUP_ITEMS.length; i++) {
            ci[i] = new PanelDialog.CheckItem(
                    STARTUP_ITEMS[i][0], STARTUP_ITEMS[i][1], STARTUP_ITEMS[i][2]);
        }
        PanelDialog.showLevelTwo(ctx, prefs, log,
                "启动速度优化（已开 " + on + " / 共 " + STARTUP_ITEMS.length + "）",
                "减少 QQ 音乐冷启动耗时。勾选后下次启动生效。",
                ci);
    }

    /** 第二级：「每日自动签到」单开关。 */
    private static void showAutoSignIn(Context ctx, LocalPrefs prefs, XLog log) {
        PanelDialog.CheckItem[] ci = {
                new PanelDialog.CheckItem(K_AUTO_SIGNIN, "每日自动签到",
                        "开启后，每次启动 QQ 音乐会在后台静默完成金币中心日签"
                                + "（每天只签一次，用户无感知）。"
                                + "签到走 App 自己的登录态，不弹窗、不打断听歌。"),
        };
        PanelDialog.showLevelTwo(ctx, prefs, log, "每日自动签到",
                "开启后，每次启动 App 自动完成金币中心日签。", ci);
    }

    /** 通用勾选列表（不再使用，保留以防外部调用）。 */
    private static void showCheckList(Context ctx, LocalPrefs prefs, XLog log,
                                      String title, String desc,
                                      java.util.List<String> items, String keyPrefix) {
        // 迁移至 PanelDialog.showLevelTwo
    }

}
