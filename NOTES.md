# 开发笔记 / 待办

记录已确认的事实与未验证的假设，避免重复调研。

## 已确认（有实测依据）

### 本机框架支持 API 102
LSPosed 管理器 2.1.1。参考模块酷安净化（`io.github.yylsping.coolapkpurifier`，
`module.prop` 为 `minApiVersion=102 / targetApiVersion=102 / staticScope=true`）在本机
实测输出 `initialized API 102 hooks in com.coolapk.market`，且框架自身打出
`ProtectiveHooker ... Exception in hooker`。

这一点重要：公开资料里「上游 LSPosed 至今只 pin libxposed=100、无 release 支持
API 101/102」的说法对本机不成立，因此按 102 编写是可行的。

### 123 云盘用爱加密整体加固
- `application` = `s.h.e.l.l.S`，`appComponentFactory` = `s.h.e.l.l.A`
- `assets/` 内有 `ijiami.ajm`(11MB)、`ijiami.dat`(16.7MB)、`IJMDal.Data`、
  `libijmDataEncryption_arm64.so`
- 整个 APK 只有一个 `classes.dex`，13,916 字节；106MB 的包 jadx 只反编译出 6 个
  java 文件，其中 4 个是壳

**结论：静态反编译拿不到任何业务代码。** 但加固只挡静态分析，不挡运行时——hook
发生时壳已把真实 dex 解密加载完毕。所以规则不依赖业务类名，只依赖两个稳定面：
未混淆的广告 SDK 类名，以及 Android 框架方法签名。

### 123 云盘集成的广告 SDK（从 manifest 组件声明实测）
快手 `com.kwad.sdk`(54 个组件)、穿山甲 `com.bytedance.sdk.openadsdk` /
`com.bytedance.msdk`、穿山甲改名副本 `com.byazt.*`（同包内有
`com.byazt.mx.CSJDownloadService`、`com.byazt.oap.TTMultiProvider`）、
优量汇 `com.qq.e`、倍孜 `com.beizi.ad` / `com.beizi.fusion`、章鱼 `com.octopus.ad`、
Sigmob `com.sigmob.sdk`、百度 `com.baidu.mobads`、望玛 `com.wangmai`。

这些 SDK **未混淆**，类名公开且跨版本稳定，是可靠的着力点。

### 工具链约束
- libxposed 的 api / service / interface 三个 aar 都声明 `minCompileSdk=37`，
  本机 AGP 8.5.2 + android-34 无法直接依赖 → 改用 aar 内的 `classes.jar`
- api 的 class 文件版本为 61（Java 17），且 `Invoker.Type` 用了 sealed interface
  + record → `sourceCompatibility` 必须 17
- `FeatureGuard.run` 必须接受能抛受检异常的函数式接口：定位类和方法时的
  `ClassNotFoundException` / `NoSuchMethodException` 正是「该项不可用」的常规信号

## 未验证的假设（下一步要用日志确认）

### 两个 hook 点是否真的覆盖 123 云盘的广告
- `Instrumentation.execStartActivity` —— 所有 Activity 启动的收敛点。**但如果
  开屏广告是在应用自己的 Activity 里内嵌绘制，就不会经过这里。**
- `ViewGroup.addView(View, int, LayoutParams)` —— 其余 addView 重载的汇聚点。
  **但如果广告 View 由 SDK 直接 attach 到 Window，或用 Compose / 自绘实现，
  同样绕过。**

这两条是从框架 API 层面推断的，逻辑成立但**未经真机确认**。日志出来前不能声称
去广告已生效。

### 六家 SDK 的 init 方法名是否准确
`Pan123Rules.SDK_INIT_ENTRIES` 里的类名与方法名来自通行用法，未逐一核对各家
SDK 的实际签名。失败会体现为 `features FAILED` 中的
`sdk-init/<名字>`，属于预期内的降级，不影响其它项。

## 下一步

1. 在 LSPosed 管理器启用「李田所」，确认作用域包含 123 云盘
   （当前日志中无任何 `LiTianSuo` 输出，说明尚未注入）
2. 抓日志确认：`ad sdk present` 报出哪几家真的加载、四项功能各自 OK 还是 FAILED
3. 按结果调整 hook 点；若 Activity 拦截无命中，考虑改从各家 SDK 的
   splash / loadAd 入口下手
4. 打开 `verbose_log` 观察命中详情

## 抓日志

```
adb logcat -c
adb shell am force-stop com.mfcloudcalculate.networkdisk
adb shell monkey -p com.mfcloudcalculate.networkdisk -c android.intent.category.LAUNCHER 1
# 等 12~15 秒
adb logcat -d | Select-String "LiTianSuo"
```

改任何代码后都必须 `force-stop` 目标应用，否则旧进程里的 hook 不会更新。
