package com.litiansuo.purifier.rules;

/**
 * 123 云盘集成的广告 SDK 清单。
 *
 * <p>这些前缀是从 APK 的 AndroidManifest 组件声明里实测得到的（3.2.17），不是猜的。
 * 应用自身的业务代码被爱加密整体加固、静态反编译取不到，但<b>广告 SDK 本身没有混淆</b>，
 * 类名公开且跨版本稳定，所以从 SDK 侧下手比从业务侧下手可靠得多。</p>
 *
 * <p>共 9 家聚合：快手、穿山甲（含 msdk 聚合与 byazt 这个改名重打包的副本）、优量汇、
 * 倍孜、章鱼、Sigmob、百度、望玛。</p>
 */
final class AdSdk {

    private AdSdk() {
    }

    /**
     * 广告 SDK 的类名前缀。
     *
     * <p>用途有两个：判断某个 Activity 是不是广告页、判断某个 View 是不是广告 SDK 造的。
     * 前缀必须足够精确，避免误伤应用自身或通用库——例如不能只写 {@code com.bytedance}，
     * 那会命中抖音开放平台登录等非广告组件。</p>
     */
    static final String[] AD_CLASS_PREFIXES = {
            // 快手联盟
            "com.kwad.sdk.",
            "com.kwad.components.",
            // 穿山甲 / 穿山甲聚合
            "com.bytedance.sdk.openadsdk.",
            "com.bytedance.msdk.",
            // 穿山甲 SDK 的改名重打包副本（实测同包内 com.byazt.mx.CSJDownloadService、
            // com.byazt.oap.TTMultiProvider，说明 byazt 就是换了名字的穿山甲）
            "com.byazt.",
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
            // 百度移动广告
            "com.baidu.mobads.",
            // 望玛
            "com.wangmai.",
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
