package com.litiansuo.purifier.rules;

/**
 * 123 云盘集成的广告 SDK 清单。
 *
 * <p>这份清单来自两处实测证据（版本 3.2.17），不是猜的：manifest 的组件声明，以及
 * {@code resources.arsc} 里的 layout 资源前缀。后者更能反映「谁真的在渲染广告」——
 * 例如 AnyThink 只有 8 个组件，却有 46 个 layout，而实测开屏正是它在做竞价。</p>
 *
 * <p>应用自身的业务代码被爱加密整体加固、静态反编译取不到，但<b>广告 SDK 未混淆</b>，
 * 类名公开且跨版本稳定，所以从 SDK 侧下手比从业务侧下手可靠得多。</p>
 *
 * <p>按 layout 数量排序的主要 SDK：快手 195、sdm(智数) 119、AnyThink 46、
 * 趣盟 42、美数 42、聚力 39、ptg 35、倍孜 28、章鱼 18、望玛 17、Sigmob 16、adgain 15。</p>
 */
final class AdSdk {

    private AdSdk() {
    }

    /**
     * 广告 SDK 的类名前缀。
     *
     * <p>用途有两个：判断某个 Activity 是不是广告页、判断某个 View 是不是广告 SDK 造的。
     * 前缀必须足够精确，避免误伤应用自身或通用库——例如不能只写 {@code com.bytedance}，
     * 那会命中抖音开放平台登录等非广告组件；也不能只写 {@code com.baidu}，
     * 那会命中百度 OAuth 登录。</p>
     */
    static final String[] AD_CLASS_PREFIXES = {
            // ── 聚合平台（同时也是开屏广告的实际入口）─────────────────
            // AnyThink / TopOn：实测开屏走它做 head bidding
            "com.anythink.",
            // ── 各家广告联盟 ──────────────────────────────────────
            // 快手
            "com.kwad.sdk.",
            "com.kwad.components.",
            "com.kwad.auth.",
            // 穿山甲 / 穿山甲聚合
            "com.bytedance.sdk.openadsdk.",
            "com.bytedance.msdk.",
            "com.bytedance.android.openliveplugin.",
            // 穿山甲 SDK 的改名重打包副本（同包内有 com.byazt.mx.CSJDownloadService
            // 与 com.byazt.oap.TTMultiProvider，说明 byazt 就是换名的穿山甲）
            "com.byazt.",
            "com.byted.live.",
            // 优量汇（广点通）
            "com.qq.e.",
            // 倍孜
            "com.beizi.ad.",
            "com.beizi.fusion.",
            // 章鱼
            "com.octopus.ad.",
            // Sigmob
            "com.sigmob.sdk.",
            "com.sigmob.windad.",
            // 百度移动广告（只匹配 mobads，避免误伤 com.baidu.oauth 登录）
            "com.baidu.mobads.",
            // 望玛
            "com.wangmai.",
            // 美数
            "com.meishu.sdk.",
            // 爱奇艺
            "com.mcto.sspsdk.",
            // 新联盟
            "com.alliance.ssp.",
            // ptg
            "com.ptg.ptgapi.",
            // 趣盟
            "com.qy.sdk.",
            // adgain
            "com.adgain.sdk.",
            // 智数
            "com.smartdigimkt.",
            // 诚聚
            "cj.mobile.",
            // 幻响
            "com.huanxiao.sdk.",
    };

    /** 该类名是否属于广告 SDK。 */
    static boolean isAdClass(String className) {
        if (className == null) {
            return false;
        }
        for (String p : AD_CLASS_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
