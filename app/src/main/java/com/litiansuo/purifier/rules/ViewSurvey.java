package com.litiansuo.purifier.rules;

import android.view.View;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.litiansuo.purifier.hook.XLog;

/**
 * 界面控件测绘：把目标应用实际用到的资源名打进日志。
 *
 * <p>为什么需要它：加固应用的业务类名全被混淆，无法作为 hook 锚点；但<b>资源名不参与混淆</b>，
 * {@code activity_ad_free}、{@code adgain_layout_open_ad} 这类名字在 APK 里是明文，
 * 而且跨版本相当稳定。所以「应用自家运营位」这类第三方 SDK 前缀抓不到的广告，
 * 只能靠资源名定位。</p>
 *
 * <p>与盲猜相比，先测绘再写规则能省掉大量往返：日志里直接给出界面上每个控件的真实资源名，
 * 对照用户描述的位置就能确定该堵哪一个。</p>
 *
 * <p>成本控制：<b>按资源名去重</b>，每个名字只打一次。日志行数因此等于界面上不同控件的数量
 * （几十行量级），而不是 {@code addView} 的调用次数（滑动列表时可达每秒上千）。
 * 去重集合用 {@code synchronizedSet} 包一层——{@code addView} 可能来自不同线程。</p>
 */
final class ViewSurvey {

    private final XLog log;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>(256));

    ViewSurvey(XLog log) {
        this.log = log;
    }

    /**
     * 记录一个 View 的资源名（若尚未记录过）。
     *
     * @param depth 该 View 在父链中的深度，便于判断它是页面级容器还是叶子控件
     */
    void record(View v, int depth) {
        if (v == null) {
            return;
        }
        String id = idName(v);
        if (id == null) {
            return; // 没有 android:id 的匿名容器对定位没有帮助，不打
        }
        if (!seen.add(id)) {
            return;
        }
        // 一并记录类名简称：能区分是 SDK 控件还是应用自绘控件
        String cls = v.getClass().getName();
        int dot = cls.lastIndexOf('.');
        log.info("view id=" + id + " cls=" + (dot < 0 ? cls : cls.substring(dot + 1))
                + " depth=" + depth);
    }

    /**
     * 取 View 的资源名（形如 {@code id/activity_ad_free}）。
     *
     * <p>{@code getResourceEntryName} 在 id 属于其它包或已被裁剪时会抛异常，
     * 所以整段包了 try——测绘失败不该影响应用运行。</p>
     */
    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return null;
        }
        try {
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }
}
