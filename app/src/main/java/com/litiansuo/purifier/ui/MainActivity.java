package com.litiansuo.purifier.ui;

import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

import com.litiansuo.purifier.R;
import com.litiansuo.purifier.core.AdaptedApps;

import io.github.libxposed.service.XposedService;

/**
 * 模块主界面：显示激活状态，并对每个已适配应用提供开关。
 *
 * <p>界面用代码构建而非 XML：控件数量少、结构简单，代码构建省掉一层布局文件间接跳转，
 * 也避免为几个开关引入 RecyclerView / AndroidX 依赖。</p>
 */
public final class MainActivity extends android.app.Activity implements ServiceBridge.Callback {

    private TextView statusView;
    private LinearLayout listContainer;
    private final Map<String, Switch> switches = new java.util.LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());
        ServiceBridge.get().attach(this);
    }

    @Override
    protected void onDestroy() {
        ServiceBridge.get().detach();
        super.onDestroy();
    }

    private View buildContentView() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setText(R.string.status_inactive);
        statusView.setTextSize(15f);
        root.addView(statusView);

        TextView section = new TextView(this);
        section.setText(R.string.section_targets);
        section.setTextSize(13f);
        section.setPadding(0, dp(24), 0, dp(8));
        root.addView(section);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        buildAppRows();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * 为每个已适配应用建一行。
     *
     * <p>只列 {@link AdaptedApps} 里登记的应用——本模块只对逐个适配过的应用生效，
     * 列出没适配的应用等于给用户虚假承诺。</p>
     */
    private void buildAppRows() {
        listContainer.removeAllViews();
        switches.clear();

        for (Map.Entry<String, AdaptedApps.Entry> e : AdaptedApps.all().entrySet()) {
            AdaptedApps.Entry entry = e.getValue();
            boolean installed = isInstalled(entry.packageName);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(this);
            name.setText(entry.label);
            name.setTextSize(16f);
            texts.addView(name);

            TextView sub = new TextView(this);
            sub.setTextSize(12f);
            if (installed) {
                sub.setText(entry.packageName + "  ·  适配版本 " + entry.verifiedVersion);
            } else {
                sub.setText(entry.packageName + "  ·  未安装");
                sub.setTextColor(Color.GRAY);
            }
            texts.addView(sub);

            row.addView(texts);

            Switch sw = new Switch(this);
            sw.setEnabled(false); // 服务连上之前不允许操作，避免写入丢失
            sw.setOnCheckedChangeListener((v, checked) -> {
                if (!v.isPressed()) {
                    return; // 程序化设置状态时不触发写入
                }
                boolean ok = ServiceBridge.get().setPackageEnabled(entry.packageName, checked);
                if (!ok) {
                    v.setChecked(!checked);
                    toast(getString(R.string.toast_no_service));
                } else {
                    toast(getString(R.string.toast_need_restart));
                }
            });
            switches.put(entry.packageName, sw);
            row.addView(sw);

            listContainer.addView(row);
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------- ServiceBridge

    @Override
    public void onConnected(XposedService service) {
        String summary = ServiceBridge.get().frameworkSummary();
        statusView.setText(summary == null
                ? getString(R.string.status_active)
                : getString(R.string.status_active) + "\n" + summary);

        java.util.Set<String> enabled = ServiceBridge.get().enabledPackages();
        for (Map.Entry<String, Switch> e : switches.entrySet()) {
            Switch sw = e.getValue();
            sw.setEnabled(true);
            // 与 hook 侧的缺省一致：键不存在时视为启用
            sw.setChecked(enabled.isEmpty() || enabled.contains(e.getKey()));
        }
    }

    @Override
    public void onUnavailable() {
        statusView.setText(R.string.status_inactive);
        for (Switch sw : switches.values()) {
            sw.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------ 工具

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
