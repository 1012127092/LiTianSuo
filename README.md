# 李田所

针对特定应用逐个适配的 Android 去广告模块（Xposed / LSPosed）。

## 定位

**只对已适配的应用生效**，不做通用或启发式去广告。通用方案在加固、自绘广告、
聚合 SDK 混用的现实环境里效果不可靠，与其给出「可能有用」的承诺，不如把每个
应用做扎实。

已适配应用登记在 `AdaptedApps` 中，同时体现为 LSPosed 的作用域列表
（`module.prop` 里 `staticScope=true`），所以模块在 LSPosed 里只显示已适配的应用。

## 已适配

| 应用 | 包名 | 验证版本 |
|---|---|---|
| 123 云盘 | `com.mfcloudcalculate.networkdisk` | 3.2.17 |

## 技术选型

基于 **libxposed 新版 API（API 102）**，不是旧的 `de.robv.android.xposed`。
两者不能混用：`targetApiVersion >= 102` 的模块在 classloader 层面就被禁止访问
legacy 包，因此 `XposedHelpers`、`XSharedPreferences`、`XposedBridge.log` 全部不可用。

相应的替代：

| 旧 API | 本项目 |
|---|---|
| `XposedHelpers.findAndHookMethod` | `hook(Executable).intercept(chain -> ...)`，见 `Hooks` |
| `XposedHelpers.callMethod` / `getObjectField` | 自写标准反射，见 `Reflect` |
| `XC_MethodHook` before/after 两段 | 拦截器链：`chain.proceed()` 前后即 before/after |
| `param.setResult(v)` | 不调 `proceed()`，直接 `return v` |
| `XSharedPreferences` | `getRemotePreferences(group)`，支持变更监听 |
| `XposedBridge.log` | `log(priority, tag, msg)` |

模块声明走 `META-INF/xposed/`（`module.prop` + `scope.list` + `java_init.list`），
不再使用 `assets/xposed_init` 与 `xposedmodule` meta-data。

## 结构

```
core/AdaptedApps     已适配应用注册表（新增应用的三处同步点之一）
core/PrefKeys        模块 App 与目标进程共享的配置键约定
hook/PurifierModule  入口类，按包名 + 进程名过滤，主进程只初始化一次
hook/FeatureGuard    功能隔离：单项注册失败只降级那一项，输出 OK/FAILED/DISABLED 汇总
hook/Hooks           hook 注册封装，统一 PROTECTIVE 异常模式与 hook id
hook/Reflect         反射工具（含多候选类名、按参数个数定位方法）
hook/RuntimeConfig   远程配置读取与缓存，热路径不走 binder
hook/XLog            同时写 logcat 与 LSPosed 模块日志
rules/RuleSet        规则集接口
rules/AppRules       包名 → 规则集路由
rules/AdSdk          广告 SDK 类名前缀清单
rules/Pan123Rules    123 云盘规则
ui/MainActivity      激活状态与按应用开关
ui/ServiceBridge     与 LSPosed 的连接、远程配置读写
```

## 失败隔离

每项功能的注册都包在 `FeatureGuard` 里，任何一项因目标应用改版、SDK 未集成、
方法签名变化而失败，只把那一项标记为不可用，其余照常工作。运行期异常则由框架的
`ExceptionMode.PROTECTIVE` 兜住（`module.prop` 已全局设为 protective）：hook 回调
抛异常时只记日志，调用按未被 hook 的方式继续，不会把目标应用带崩。

排查现场问题看一行日志即可：

```
[<包名>] features OK(n): ...
[<包名>] features FAILED(n): ...
```

## 新增一个适配应用

必须同步改四处，漏改会导致「界面里有但 hook 不生效」：

1. `core/AdaptedApps` 加一条 `Entry`
2. `app/src/main/resources/META-INF/xposed/scope.list` 加一行包名
3. `app/src/main/AndroidManifest.xml` 的 `<queries>` 加一行
4. `rules/AppRules.forPackage` 加一个分支，并实现对应 `RuleSet`

## 构建

```
gradle assembleRelease \
  -PLTS_STORE_PASSWORD=<存储密码> \
  -PLTS_KEY_PASSWORD=<密钥密码> \
  -PLTS_KEY_ALIAS=<别名>
```

签名密钥 `release.keystore` 不入库，密码只通过 `-P` 参数或本地
`~/.gradle/gradle.properties` 传入。

`app/libs/` 下三个 jar 是从对应 aar 中取出的 `classes.jar`。这样做的原因与代价
见 `app/build.gradle` 中的注释：这些 aar 声明 `minCompileSdk=37`，超出本地
AGP 8.5.2 + android-34 的上限。

## 环境要求

- 框架需实现 Xposed API 102（`module.prop` 中 `minApiVersion=102`）
- `minSdk 26`（libxposed 的要求）
- 编译需 Java 17（api 的 class 文件版本为 61，且用到 sealed interface 与 record）
