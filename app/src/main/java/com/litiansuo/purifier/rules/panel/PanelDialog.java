package com.litiansuo.purifier.rules.panel;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.litiansuo.purifier.hook.LocalPrefs;
import com.litiansuo.purifier.hook.XLog;

/**
 * 「李田所」所有面板的统一样板。
 *
 * <p>所有目标应用（QQ 音乐、UC 浏览器及未来新增）的「李田所」面板均<b>必须</b>通过本类构建，
 * 不允许直接使用 {@link android.app.AlertDialog}——后者在不同 Android 主题/版本下表现不一致，
 * 无法保证面板视觉统一。</p>
 *
 * <h3>视觉规格（所有面板统一）</h3>
 * <ul>
 *   <li><b>窗口</b>：屏幕居中 + 圆角白底（{@link #buildDialogBackground}）+ 阴影；</li>
 *   <li><b>宽度</b>：minWidth 280dp、maxWidth 560dp（标准 Material 规范）；</li>
 *   <li><b>标题</b>：对话框标题栏（系统渲染，下面留 8dp 分隔）；</li>
 *   <li><b>一级菜单行</b>：标题 16sp + 灰色 12sp 副标题 + 右侧 {@code ›} 箭头；</li>
 *   <li><b>二级勾选</b>：{@code CheckBox} 15sp + 缩进 32dp 的灰色 12sp 副标题；</li>
 *   <li><b>footer 按钮</b>：纯文字 + 靠右 + 无背景框（{@code setBackground(null)}）；
 *       文字色取自 {@code android:textColorLink}，无则 fallback Material 蓝；</li>
 *   <li><b>分割线</b>：1px 浅灰横线（{@code #22000000}）。</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>
 * // 弹出「李田所」一级面板（菜单行列表）
 * PanelDialog.showLevelOne(ctx, prefs, log, "李田所", new PanelDialog.MenuItem[]{
 *     new PanelDialog.MenuItem("后台与推送", "拦截推送/保活服务", v -&gt; showPush(ctx, prefs, log)),
 *     new PanelDialog.MenuItem("信息流广告", "隐藏信息流广告卡", v -&gt; showFeedAd(...)),
 * });
 *
 * // 弹出二级面板（CheckBox 勾选列表）
 * PanelDialog.showLevelTwo(ctx, prefs, log, "后台与推送",
 *     "勾选后拦截对应推送/保活服务。改动需重启 UC 生效。",
 *     new PanelDialog.CheckItem[]{
 *         new PanelDialog.CheckItem("uc.block.push", "推送保活拦截", "..."),
 *     });
 * </pre>
 */
public final class PanelDialog {

    private PanelDialog() {
    }

    // ============================================================ 一级菜单

    /** 一级菜单项：标题 + 副标题 + 点击回调。 */
    public static final class MenuItem {
        public final String title;
        public final String desc;
        public final View.OnClickListener onClick;

        public MenuItem(String title, String desc, View.OnClickListener onClick) {
            this.title = title;
            this.desc = desc;
            this.onClick = onClick;
        }
    }

    /**
     * 弹出一级「李田所」菜单面板（屏幕居中 + 圆角白底 + 统一 footer「关闭」）。
     */
    public static Dialog showLevelOne(Context ctx, LocalPrefs prefs, XLog log,
                                      String title, MenuItem[] items) {
        return showCentered(ctx, title, buildLevelOneBody(ctx, prefs, log, items), "关闭");
    }

    // ============================================================ 二级勾选

    /** 二级 CheckBox 项：键名 + 标题 + 副标题。 */
    public static final class CheckItem {
        public final String key;
        public final String title;
        public final String desc;

        public CheckItem(String key, String title, String desc) {
            this.key = key;
            this.title = title;
            this.desc = desc;
        }
    }

    /**
     * 弹出二级 CheckBox 勾选列表（屏幕居中 + 圆角白底 + 统一 footer「完成」）。
     *
     * @param title  面板标题（建议附加「（已开 N / 共 M）」计数）
     */
    public static Dialog showLevelTwo(Context ctx, LocalPrefs prefs, XLog log,
                                      String title, String hint,
                                      CheckItem[] items) {
        return showCentered(ctx, title, buildLevelTwoBody(ctx, prefs, log, hint, items), "完成");
    }

    // ============================================================ 内部：通用 dialog 容器

    /**
     * 统一的 dialog 容器：屏幕居中 + 圆角白底背景。
     *
     * <p>用 {@link Dialog}（不是 {@code AlertDialog}）是因为不想要系统 dialog 的标题栏/按钮栏
     * 任何装饰，body 完全我们自己画；窗口背景也只是我们画的圆角白底，干净可控。</p>
     */
    private static Dialog showCentered(Context ctx, String title, View body, String footerText) {
        Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        // 外层：垂直 [ 自绘标题 | body | 分割线 | footer按钮 ]
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.WHITE);

        // 标题
        if (!TextUtils.isEmpty(title)) {
            TextView titleView = new TextView(ctx);
            titleView.setText(title);
            titleView.setTextSize(18f);
            titleView.setTextColor(Color.parseColor("#212121"));
            int p = dp(ctx, 20);
            titleView.setPadding(p, p, p, dp(ctx, 8));
            outer.addView(titleView);
        }

        outer.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        outer.addView(buildDivider(ctx));
        outer.addView(buildFooterButton(ctx, footerText, v -> dismissDialogOf(v)));

        dlg.setContentView(outer);

        // 把 dialog 绑到 footer 按钮的 tag 上（点击时 dismiss）
        tagDialogOnFooter(outer, dlg);

        // 窗口：居中 + 圆角白底（系统背景置 null，自己画）
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(buildDialogBackground(ctx));
            // 关闭默认标题栏背景
            w.setDimAmount(0.4f); // 屏幕变暗
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            int width = (int) (Math.min(
                    Math.max(280 * ctx.getResources().getDisplayMetrics().density,
                            ctx.getResources().getDisplayMetrics().widthPixels * 0.85f),
                    560 * ctx.getResources().getDisplayMetrics().density));
            lp.width = width;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        dlg.show();
        return dlg;
    }

    // ============================================================ 内部：body 构造

    private static View buildLevelOneBody(Context ctx, LocalPrefs prefs, XLog log, MenuItem[] items) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 8);
        body.setPadding(p, p, p, p);
        for (MenuItem m : items) {
            body.addView(buildMenuRow(ctx, m.title, m.desc, m.onClick));
        }
        return body;
    }

    private static View buildLevelTwoBody(Context ctx, LocalPrefs prefs, XLog log,
                                           String hint, CheckItem[] items) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);

        // 可滚动列表：让条目很多时也不会顶出 dialog
        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        inner.setPadding(pad, dp(ctx, 8), pad, pad);

        if (!TextUtils.isEmpty(hint)) {
            TextView hintView = new TextView(ctx);
            hintView.setText(hint);
            hintView.setTextSize(12f);
            hintView.setTextColor(Color.parseColor("#757575"));
            inner.addView(hintView);
        }

        int on = 0;
        for (CheckItem it : items) {
            if (prefs != null && prefs.get(it.key, false)) on++;
            inner.addView(buildCheckItem(ctx, prefs, it.key, it.title, it.desc, log));
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(inner);
        // 给 scroll 一个上限（防止超长清单把 dialog 撑爆）：
        int maxH = dp(ctx, 400);
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        return body;
    }

    // ============================================================ 内部：原子视图

    private static View buildMenuRow(Context ctx, String title, String desc,
                                     View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        int p = dp(ctx, 16);
        row.setPadding(p, p, p, p);
        row.setOnClickListener(onClick);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(16f);
        t.setTextColor(Color.parseColor("#212121"));
        texts.addView(t);
        TextView d = new TextView(ctx);
        d.setText(desc);
        d.setTextSize(12f);
        d.setTextColor(Color.parseColor("#757575"));
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(22f);
        arrow.setTextColor(Color.parseColor("#BDBDBD"));
        row.addView(arrow);
        return row;
    }

    private static View buildCheckItem(Context ctx, LocalPrefs prefs, String key,
                                       String title, String desc, XLog log) {
        // 整行：水平 [ 自绘勾选框 | 标题/副标题文本 ]，整行可点击
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        int pad = dp(ctx, 12);
        row.setPadding(0, dp(ctx, 10), 0, 0);
        // 点击整行 = toggle 勾选
        row.setOnClickListener(v -> {
            UniformCheckbox cb = (UniformCheckbox) row.getChildAt(0);
            cb.toggle();
        });

        // 自绘勾选框
        UniformCheckbox cb = new UniformCheckbox(ctx);
        cb.setChecked(prefs != null && prefs.get(key, false));
        cb.setOnCheckedChangeListener(() -> {
            if (prefs != null) {
                prefs.set(key, cb.isChecked());
                if (log != null) log.info("panel: " + key + " -> " + cb.isChecked());
            }
        });
        row.addView(cb);

        // 标题 + 副标题
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        int tpad = dp(ctx, 12);
        texts.setPadding(tpad, 0, 0, 0);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(Color.parseColor("#212121"));
        texts.addView(t);

        if (!TextUtils.isEmpty(desc)) {
            TextView sub = new TextView(ctx);
            sub.setText(desc);
            sub.setTextSize(12f);
            sub.setTextColor(Color.parseColor("#757575"));
            sub.setPadding(0, dp(ctx, 2), 0, 0);
            texts.addView(sub);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 1px 浅灰横线分割条。 */
    private static View buildDivider(Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(Color.parseColor("#22000000"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(ctx, 6);
        divider.setLayoutParams(lp);
        return divider;
    }

    /**
     * footer 按钮：纯文字 + 靠右 + 无背景框。
     *
     * <p>row 本身可点击并 setTag(dialog)，点击 row 内任意位置都 dismiss。
     * TextView 自己的 onClick 转发到 row 的 dismiss 逻辑，避免按钮有点击反馈但
     * 外层 row 没反应的怪现象。</p>
     */
    private static View buildFooterButton(Context ctx, String text, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 8);
        row.setPadding(p, p, p, p);
        row.setClickable(true);
        row.setOnClickListener(v -> dismissDialogOf(v));

        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(15f);
        btn.setTextColor(getLinkColor(ctx));
        btn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        btn.setClickable(true);
        btn.setBackground(null); // 唯一与系统按钮的区别：无背景框
        btn.setOnClickListener(v -> dismissDialogOf(row));
        row.addView(btn);
        return row;
    }

    /**
     * dialog 窗口背景：圆角白底 + 阴影（用 {@link GradientDrawable} 模拟 Material dialog）。
     */
    private static Drawable buildDialogBackground(Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.WHITE);
        gd.setCornerRadius(dp(ctx, 8));
        return gd;
    }

    /** footer 按钮文字色：与勾选框一致的绿色（统一视觉）。 */
    private static int getLinkColor(Context ctx) {
        return Color.parseColor("#4CAF50");
    }

    private static void dismissDialogOf(View v) {
        Object tag = v.getTag();
        if (tag instanceof Dialog) ((Dialog) tag).dismiss();
    }

    /**
     * 把 dialog 绑到 footer 按钮的 tag 上：outer 的最后一级 LinearLayout（即 footer row）持有 dialog。
     *
     * <p>{@link #buildFooterButton} 返回的 LinearLayout row 在 outer 里是最后一个子 View。
     * 点击 row 内任意位置（row.onClick 或 TextView.onClick）都会从 tag 读 dialog 并 dismiss。</p>
     */
    private static void tagDialogOnFooter(LinearLayout root, Dialog dlg) {
        int count = root.getChildCount();
        if (count > 0) {
            View last = root.getChildAt(count - 1);
            if (last instanceof LinearLayout) {
                ((LinearLayout) last).setTag(dlg);
            }
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
