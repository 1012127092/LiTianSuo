package com.litiansuo.purifier.rules;

import com.litiansuo.purifier.core.AdaptedApps;

/**
 * 包名到规则集的路由。
 *
 * <p>新增适配应用时在这里加一个分支，并同步更新
 * {@link com.litiansuo.purifier.core.AdaptedApps}、{@code scope.list} 与
 * {@code AndroidManifest.xml} 的 {@code <queries>}。</p>
 */
public final class AppRules {

    private AppRules() {
    }

    /** 找不到对应规则集时返回 null。 */
    public static RuleSet forPackage(String packageName) {
        if (AdaptedApps.PKG_PAN123.equals(packageName)) {
            return new Pan123Rules();
        }
        if (AdaptedApps.PKG_QQMUSIC.equals(packageName)) {
            return new QqMusicRules();
        }
        return null;
    }
}
