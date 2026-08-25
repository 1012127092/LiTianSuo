# 开发笔记 / 待办

记录已确认的事实与未验证的假设，避免重复调研。

## 已确认（有实测依据）

### 模块已在真机生效

`ad-view` / `ad-activity` / `sdk-init` 全部装上并有命中记录：

```
H hid ad container by id: frame_ad_splash_container
H sdk-init/ATSDK#init/3 -> blocked
H sdk-init/ATSDK#start/0 -> blocked
```

运行时加载到的 SDK：`ATSDK, TTAdSdk, GDTAdSdk, KsAdSDK, WindAds, BeiZis, Octopus,
AdSdk, QyClient`（AdSdk = 美数 `com.meishu.sdk.core.AdSdk`）。
其中 7 家 init 已掐断，2 家降级见下。

### 诊断日志必须写文件，不能只靠 logcat

**这是本项目最容易踩、代价最大的坑。** 爱加密壳初始化之后，本进程的 logcat 输出
被静默：钩子回调、后台线程心跳、连普通 `android.util.Log.i` 全部不再出现，
而进程活得好好的。

结果是连续多轮把「logcat 没输出」误判成「钩子失效」，反复更换 hook 触发点。
实际上钩子一直在正常工作。

所以 `FileLog` 写 `/sdcard/Android/data/<pkg>/files/litiansuo-diag.log`：
应用写自己的外部私有目录不需要权限，adb 也能直接 `cat`（设备未 root，
内部 `filesDir` 拉不出来）。

**教训：诊断通道本身要先被证明可用，否则一切否定结论都不成立。**

读日志：

```
adb shell cat /sdcard/Android/data/com.mfcloudcalculate.networkdisk/files/litiansuo-diag.log
```

### `onPackageReady` 对本应用永不触发

API 契约要求框架能拿到 `getAppComponentFactory()` 与最终 classloader，而
123 云盘声明 `android:appComponentFactory="s.h.e.l.l.A"`，壳替换了 classloader，
框架直接跳过该回调。只有 `onModuleLoaded` 和 `onPackageLoaded` 会触发。

这是加固应用的固有行为，不是框架 bug。所以入口在 `onPackageLoaded`，规则拆两段：

- `installEarly` —— 只碰 Android 框架类（此刻壳还没解密业务 dex）
- `installLate` —— 广告 SDK 类（真实 dex 加载后）

### late 阶段用轮询，不用 hook 触发点

试过并**全部失败**的触发点：`Application.attach(Context)`（参考模块用的就是它）、
`Instrumentation.newApplication` 全部重载、`callApplicationOnCreate`、
`Application.onCreate`。装上了但从不打响。

改为后台 daemon 线程每 100ms 查一次 `ActivityThread.currentApplication()`，
非 null 即装。实测 **+600~700ms** 就绪。

时机很关键：开屏容器 `frame_ad_splash_container` 在 **+0.7s** 就加入布局。
早先用 1.5s/4s/8s 三轮固定延迟，第一轮要到 +7.7s 才装上，广告早跑完了。

顺带发现：**同 id 重复注册是叠加不是替换**（钩子数 42→76→110），所以 late
阶段用 `LATE_DONE` 保证只装一次。

### `Handler` + `Looper.getMainLooper()` 在本应用不可用

往 mainLooper post 的任务一个都没执行，而进程正在正常渲染 MainActivity。
壳很可能重建了主线程环境。装钩子不需要主线程，用普通线程即可。

### 开屏卡顿的成因

应用自己的日志反复出现：

```
anythink_bidding: {"action":"headbidding","result":"fail","adtype":"Splash",
"networkFirmId":46,"msg":"bid timeout!"}
```

AnyThink/TopOn 聚合开屏 head bidding 逐家等超时。所以掐 SDK init 同时治好卡开屏——
`com.anythink.core.api.ATSDK` 在 `SDK_INIT_ENTRIES` 里排第一位。

### 资源名是唯一可靠的界面锚点

业务类名全混淆，但资源名不参与混淆。真机测绘（`ViewSurvey`）拿到的开屏布局：

```
frame_ad_splash_container  ← 开屏容器，已拦
iv_splash_bg / iv_splash / iv_close / ll_root
```

以及 Sigmob 弹窗插屏一整套 `wm_*`（摇一摇、扭一扭、滑动跳转）。
`AD_CONTAINER_IDS` 只收**容器**不收叶子控件：隐藏容器比逐个隐藏子控件干净，
也不留空白占位。

### `module.prop` 里绝对不能写 `exceptionMode`

写了框架直接拒绝加载模块，表现为零输出。必须严格 3 行：

```
minApiVersion=102
targetApiVersion=102
staticScope=true
```

（`PROTECTIVE` 在代码里逐个 hook 设，不在 prop 里设。）

### 本机框架支持 API 102

LSPosed 管理器 2.1.1。参考模块酷安净化（`io.github.yylsping.coolapkpurifier`）
在本机实测正常注入 `com.coolapk.market`。

公开资料里「上游 LSPosed 只 pin libxposed=100」的说法对本机不成立。

### 123 云盘用爱加密整体加固

- `application` = `s.h.e.l.l.S`，`appComponentFactory` = `s.h.e.l.l.A`
- `assets/ijiami.ajm`(11MB)、`ijiami.dat`(16.7MB)、`libijmDataEncryption_arm64.so`
- 整个 APK 只有一个 `classes.dex`，13,916 字节；jadx 只出 6 个 java 文件

静态反编译拿不到业务代码，但加固只挡静态分析：hook 发生时壳已解密完毕。

### 广告 SDK 规模（`aapt2 dump resources` 按 layout 前缀统计，共 879 个 layout）

`ksad` 195、`sdm` 119、`anythink` 46、`qy` 42、`ms` 42、`ly` 39、`ptg` 35、
`hx` 29、`beizi` 28、`md` 26、`oct` 18、`fanti` 17、`sig` 16、`adgain` 15、
`asnp` 12、`zfcj` 8、`jyad` 5。

### 工具链约束

- libxposed 三个 aar 都声明 `minCompileSdk=37`，本机 AGP 8.5.2 + android-34
  无法直接依赖 → 改用 aar 内的 `classes.jar`，manifest 里手写 `XposedProvider`
- api 的 class 版本 61（Java 17），用了 sealed interface + record →
  `sourceCompatibility` 必须 17
- `Chain.getArgs()` 返回 `List<Object>` 而非 `Object[]`
- `FeatureGuard.run` 必须接受能抛受检异常的函数式接口：定位类和方法时的
  `ClassNotFoundException` / `NoSuchMethodException` 正是「该项不可用」的常规信号

## 已知降级项

| 项 | 原因 |
|---|---|
| `sdk-init/WindAds` | `com.sigmob.windad.WindAds` 无 `startWithOptions` / `init` |
| `sdk-init/QyClient` | `com.mcto.sspsdk.QyClient` 无 `init` |

两家都是方法名不对，需要用真机反射把实际方法列表打出来再修。它们的 View 层
已被 `wm_*` 资源名规则覆盖，优先级不高。

## 待办

1. 用户反馈仍在的三处广告，需要在对应界面测绘后写规则：
   - 首页中间左右滑动的轮播广告
   - 右下角「免广告」浮标（截图确认仍在，位置 1213,2402）
   - 传输页 3 条「开通会员」
   这三处是**应用自家运营位**，不是第三方 SDK，必须靠资源名。
   当前测绘只覆盖了启动路径，需要手动进那几个页面再抓一次。
2. 首页有 `FlutterView`（`view id=0x1 cls=FlutterView`）——首页轮播若在 Flutter
   里绘制，`addView` 抓不到，需要另找路子。**这条会决定第 1 项能不能做成。**
3. 修 `WindAds` / `QyClient` 的方法名
4. 把 `ServiceBridge.scope()` 显示到 `MainActivity`
5. 提交代码（仓库目前只有初始 commit）

## 真机验证流程

```
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell rm -f /sdcard/Android/data/com.mfcloudcalculate.networkdisk/files/litiansuo-diag.log
adb shell am force-stop com.mfcloudcalculate.networkdisk
adb shell monkey -p com.mfcloudcalculate.networkdisk -c android.intent.category.LAUNCHER 1
# 等 20 秒
adb shell cat /sdcard/Android/data/com.mfcloudcalculate.networkdisk/files/litiansuo-diag.log
```

改任何代码后都必须 `force-stop`，否则旧进程里的 hook 不会更新。

日志前缀：`I` 普通、`W` 警告、`E` 错误、`H` 命中（规则真的起作用了）、
`RAW` 阶段事件（不经过 xposed，用于排除日志通道本身的问题）。
