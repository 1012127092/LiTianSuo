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

### Flutter 平台通道：Dart 世界唯一对 Java 敞开的入口

「Dart 侧的东西 Java 拦不到」只对了一半。业务 HTTP 确实走 native socket，
但 Flutter 与原生之间的**所有**通信都必须经过平台通道，而通道实现在 Java 侧。

`DartMessenger` 是收敛点：`handleMessageFromDart` 是 Dart→Java，`send` 是反向。
实测 40 个通道，应用自家的桥是 `com.wisdom.water.main`，形如「单通道 + methodName
分发」，共 12 个方法：

```
getAppChannel / getAppChannelName / getSystemRootDirectory / isPad
obtainLoginData / setUserInfo / obtainStoreListType
storageSharedPreferences / obtainSharedPreferences
storageAdFreeData / appAdConfig / clearRewardAdCache
```

`storageAdFreeData` 带 `button_splash_screen` / `button_upload` / `button_download` /
`button_quit` / `button_user_center` / `button_return_file` / `remove_ads_effect` /
`removeAdsTime`；`appAdConfig` 带 `isPreloadSplash` / `isPreloadInter` /
`isOpenSplash` / `splashInterval` / `allInterval`。

AnyThink 的 Dart 桥是 `anythink_sdk` 通道，能看到 `loadBannerAd` /
`loadNativeAd` / `loadInterstitialAd` / `loadRewardedVideo` 与各自的 placementID。

**这类桥按通道名去重只会留下第一条**，必须逐条记 payload 才看得见真正关心的调用。

**读通道字节必须 `ByteBuffer.duplicate()`**：直接读会推进原 buffer 的 position，
下游解码拿到残缺数据，症状看起来跟本模块毫无关系。

### hook 点的取舍：抽象方法不能 hook

想拦 `MethodChannel$MethodCallHandler.onMethodCall` —— 框架直接拒绝
`Cannot hook abstract methods`。抽象方法没有实体可替换，要拦只能找到每个实现类，
而实现类在应用侧且被混淆。

改为拦 `MethodCall` 的**构造函数**：它由编解码器在解出完整参数后构造，
是所有通道方法的必经之路，类名又稳定在 Flutter 框架里。
`arguments` 字段是 final，但解码出的 Map 本身可变，就地改内容即可。

改桥参数的两条硬规则：

- **保持原类型**：Dart 按声明类型解码，int 换成 bool 会直接抛异常。
- **只改值不拦调用**：Dart 在 await 回复，不放行会让界面永久卡住。

`removeAdsTime` / `splashInterval` / `allInterval` 不动：它们是时长不是开关，
置 0 可能被理解成「间隔 0 秒」而变成无限加载。

### 写入侧改不动的，去读取侧改

`storageSharedPreferences` 的参数是 `keyList` / `valueList` 双数组按下标对应，
所以写入侧能改（`bridge isVip 0 -> 1` 已确认）。但**回复方向**的数据同样是按下标
定位的数组，没有键名可匹配，通道层改不了。

绕过通道，直接改数据的真正来源：桥最终读的是 Android `SharedPreferences`。
在 `android.app.SharedPreferencesImpl` 的 `getString` / `getInt` / `getBoolean`
上各拦一层，无论谁问、走哪条路径，答案都一致。

`SharedPreferences` 读取很频繁，所以回调第一步就是键名比较，不匹配立刻
`proceed()`，不做任何额外工作。

### 判别实验：一次就能定性，别靠连续猜

传输页上传屏横幅写着「VIP 连续包月 ¥6.00」，而配置里正好有
`goodsName="VIP连续包月"` + `firstPrice=600`(分)。把 `firstPrice` 改成 123，
若横幅变 ¥1.23 就说明它读这份配置、还有开关可找；毫无变化就说明数据不来自这里。

结果：**仍显示 ¥6.00**。定性结论——那两处的数据完全由 Dart 侧自取，
既不读这份配置，也不经过任何 Java 通道。Java 层的手段到此为止。

比逐个字段试开关高效得多：一次实验换一个确定结论。

### 突破口：Flutter 的 shared_preferences 落在 Java 层

Flutter 的 `shared_preferences` 插件最终写的就是 Android `SharedPreferences`
（文件 `FlutterSharedPreferences`，键名带 `flutter.` 前缀），读取走 `getAll()`。
**所以 Dart 侧的持久化数据反而是 Java hook 能碰到的**，比平台通道更靠底层。

这解释了为什么之前改 HTTP 和通道都无效：**Dart 读的是自己缓存的那一份。**
首次启动时缓存已写好，之后就不再理会接口返回什么。

键名测绘（hook `SharedPreferencesImpl.getAll`）拿到 35 个键，关键的几类：

```
flutter.interface_config            Dart 侧全部接口 URL（200+ 个）
flutter.ownAdInfo_ / _<uid>         自家广告内容缓存
flutter.lastOwnAdNo_<uid>_2001      各位置最近一条广告号
flutter.lastShowAdType_<uid>_2001/2002/2005
flutter.lastOwnAdTime_<uid>_30004
flutter.button_upload / _download / _quit / _user_center /
        _return_file / _splash_screen        ← 与 HTTP 同名，各存一份
flutter.removeAdsEffect / flutter.removeAdsTime
flutter.TrackingInfo                {"loginStatus","vipType","vipSub",...}
```

那些 2001 / 2002 / 30004 正是 `advert_resource/get` 里 `data.list` 的位置号。

**探针必须逐个 prefs 文件都 dump**：应用会打开多个 prefs 文件（自家的、Flutter 的、
各 SDK 的），只记第一个非空的会漏掉真正想找的那个 —— 上一轮就是这么漏的，
错误地得出「prefs 里只有 lastVersionCodeUsed」。

`getAll()` 返回的是内部 map 的副本（`new HashMap<>(mMap)`），改它不污染真正的
prefs，磁盘文件也不会被改写。

### interface_config：Java 层唯一能碰到 Dart 网络请求的地方

Dart 用 `dart:io HttpClient` 走 native socket，请求本身 Java 看不见 ——
但请求用的**地址**来自 `flutter.interface_config`，而它存在 prefs 里。

里面有 200 多个接口 URL，与广告相关的两个：

```
adFreeConfig     /api/restful/goapi/v1/remove_ads/config   免广告配置
advertALiReport  /api/restful/goapi/v1/advert/ali/report   广告曝光上报
```

只断 `advertALiReport`（纯统计，无功能损失）。**`adFreeConfig` 刻意不断**：
试过对浮标无效，而它是「看广告换 24 小时免广告」的配置接口，
断掉反而剥夺用户真要用这功能时的选择 —— 既无收益又有代价。

改 URL 用 `127.0.0.1:1` 而不是空串：空串可能让 Dart 抛 `FormatException`
落进未预料的分支；合法但连不上的地址走正常的「请求失败」路径，
是应用本来就会处理的情况。手写字符串替换而不解析 JSON —— 200 多个字段结构未知，
解析再序列化有改坏其它字段的风险。

### AnyThink 的 Dart 桥才是弹窗广告的来源

`sdk-init` 拦的是 `ATSDK.init` 这类静态入口，但 **Flutter 插件这条路径完全独立**，
Dart 侧照样能发 `loadInterstitialAd` / `loadNativeAd` / `loadBannerAd` /
`loadRewardedVideo`。这解释了为什么 SDK 初始化明明被拦住，弹窗还是会出现。

hook 点：`AnythinkSdkPlugin.onMethodCall` **不存在**
（`NoSuchMethodException params=2`）—— 插件把处理器注册为 lambda 或内部类。
改为在 `MethodCall` 构造函数里按方法名前缀识别，命中就把 `method` 字段改掉，
让插件的分发落到「未知方法」分支自然回 `notImplemented`。

改方法名而不是清空参数：参数结构 Dart 侧不检查，清空未必阻止加载；
方法名不匹配则一定走不到加载逻辑。`notImplemented` 对 Dart 是明确的
「功能不存在」，比无限等待安全。

前缀判断还要求 `Ad` / `Video` 后缀，否则会误伤应用自家桥上的正常 `loadXxx`。

### 已排除的方向（不要重试）

| 试过什么 | 确认生效的日志 | 结果 |
|---|---|---|
| HTTP 改 `removeAdConfig` 全部 `button_*` | `patched config` | 传输页 3→1，浮标不动 |
| HTTP 改 `continuousPay`/`loadVipBuyId`/`loadBuyEntryMode`/`BuyRemoveAds` | 同上 | 全部无效 |
| 桥参数按键名就地改 | `bridge args patched: 7 key(s)` | 浮标不动 |
| prefs 伪造 `isVip=1`（读取侧） | `prefs isVip -> "1"` ×3 | 浮标不动 |
| prefs 改 `removeAdsEffect=0` | `prefs ad switches off: 1` | 浮标不动 |
| prefs 改 `TrackingInfo.vipType=1` | `TrackingInfo vipType -> 1` | 浮标不动 |
| 断 `adFreeConfig` 接口 | `ad endpoints broken: 2` | 浮标不动 |
| 改 `firstPrice=123` 观察横幅价格 | —— | 仍显示 ¥6.00 |

最后一条是判别实验：**一次实验换一个确定结论**，比逐个字段试开关高效得多。
它证明那些 Flutter 自绘的位置不读这份配置。

`TrackingInfo` 那条也说明了问题 —— 名字就是 Tracking，只是埋点上报的快照，
不参与界面判断。会员状态的真实来源在 Dart 内存里，由那些 Java 看不见的
业务请求直接填充。

### 主界面是 Flutter，UI 层拦不住应用自家运营位

三条独立证据：APK 内含 `libapp.so`；首页有 `view id=0x1 cls=FlutterView`；
切换底部 tab 时 `addView` **一条新记录都没有**。

Flutter 控件不是 Android View，全画在同一张 canvas 上，所以按类名或资源名隐藏
对它们完全无效。用户反馈的首页轮播、右下角浮标、传输页会员条都在这一层。
唯一的着力点是**数据源**。

### 只有 3 个请求走 Java 层 OkHttp

`url-probe` 实测结果，全部在启动阶段：

```
apigate.123795.com/getconfig-api/v1/getconfig
api.123278.com/api/app/config/get
api.123278.com/api/v2/advert_resource/get
```

之后无论翻页、切 tab、滑动，再也不出新记录。**业务请求由 Dart 侧的
`dart:io HttpClient` 发出，走 native socket，Java hook 完全看不到。**

这条事实划定了本模块的能力边界：能拦的只有这 3 个启动期接口。

### 广告接口：整体替换

`api.123278.com/api/v2/advert_resource/get` 真实响应：

```json
{"code":0,"message":"ok","data":{"list":{"2001":[{"advert_id":30004,
  "advert_position":2001,"image_url":"...","jump_url":"{\"path\":\"/vip/center/page\"}"}]}}}
```

`data.list` 是「广告位号 → 广告数组」的字典，置成 `{}` 即可让所有位置都没广告，
不必枚举位置号（位置号随运营调整，枚举等于埋雷）。`code:0` 必须保留，
改错误码会让应用走失败分支弹重试。

### 配置接口：只能定点改字段

`api.123278.com/api/app/config/get` 的 `removeAdConfig` 是运营位总开关：

```json
"isInitAd":1,
"removeAdConfig":{"button_upload":1,"button_download":1,"button_return_file":1,
  "button_user_center":1,"button_quit":1,"button_splash_screen":1,
  "BuyRemoveAds":2,"remove_ads_effect":1,...}
```

这份响应同时承载 `clientIP`、`crmURL`、接口地址等应用必需内容，
**绝不能像广告接口那样整体替换**，否则直接把应用弄坏。

已验证：`button_*` 全置 0 → 传输页三屏横幅**从 3 条降到 1 条**（下载页、
离线下载页的消失，上传页的仍在）。

已验证无效：`continuousPay` / `loadVipBuyId` / `loadBuyEntryMode` / `BuyRemoveAds`
置 0，上传页横幅与右下角「免广告」浮标依然存在 —— 它们由 Dart 侧取数绘制。

不动 `remove_ads_effect`：字面是「免广告已生效」，含义未确认，
伪造权益状态可能让应用进异常分支。只关入口。

### 改 OkHttp 响应的三个坑

1. **hook `Response$Builder.build()` 而不是 `RealCall.execute`** ——
   后者包名在 OkHttp 3/4 之间不同（`okhttp3.RealCall` vs
   `okhttp3.internal.connection.RealCall`），得做版本适配。
2. **改 builder 的 `body` 字段，不要拿到 Response 再 `newBuilder().build()`** ——
   后者会重新进入本 hook 造成递归。
3. **拦截器链每层都 build 一个 Response，其中很多 `body` 为 null** ——
   必须先判 null 再处理。第一版把「只打一次」的标记设在读取之前，
   第一个中间响应就把唯一机会用掉了，于是只看到一次 NPE 而永远拿不到真正的响应体。

另外该接口回的是 **gzip**（应用自己加了 `Accept-Encoding`，OkHttp 不做透明解压），
所以读要先解压、写要按原编码压回去。用魔数 `1f 8b` 判断而不是读 `Content-Encoding`，
中间层可能改过头字段。

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

## 当前拦截效果

| 广告位 | 状态 | 手段 |
|---|---|---|
| 开屏广告 | 已消除 | `frame_ad_splash_container` 置 GONE + 掐 7 家 SDK init |
| 开屏卡顿（bid timeout） | 已消除 | 拦 `ATSDK.init/start` |
| 弹窗/插屏广告 | 已消除 | 改 `MethodCall.method` 让 AnyThink 加载调用落空 |
| 首页轮播广告 | 已消除 | `advert_resource/get` 的 `data.list` 置空 |
| 自家运营位缓存 | 已清空 | prefs 里 `flutter.ownAdInfo*` 置空串 |
| 广告曝光上报 | 已断开 | `interface_config.advertALiReport` 指向死地址 |
| 传输页会员横幅 | 3 条 → 1 条 | `removeAdConfig.button_*` 置 0 |
| 上传屏横幅 | **拦不到** | Dart 自绘自取，8 种手段全部证伪 |
| 右下角「免广告」浮标 | **拦不到** | 同上 |

遍历四个 tab 全程 `E`（错误）计数为 0，应用工作正常。

## 剩余两处为什么拦不到

见上文「已排除的方向」表：8 种手段每一种都确认生效（有 `H` 命中日志），
但那两处一动不动。判别实验（改 `firstPrice` 观察横幅价格）证明它们不读
Java 能碰到的任何配置。

结论：数据在 Dart 侧自取自绘 —— 业务请求走 `dart:io` native socket，
渲染走 Skia，中间不经过任何 Java 对象。要继续只剩两条路，都超出本模块范畴：

- 改 `libapp.so`（Dart AOT 机器码，需重打包，破坏签名与完整性校验）
- Frida 之类的 native 层 hook（需要 root 或注入器）

**不要再往 `CONFIG_PATCHES` / `AD_OFF_KEYS` / prefs 补丁里加字段试。**
已排除方向表就是为了防止重走这些路。

## 已启用的功能（12 项）

探针在定位完成后已全部移除，只留真正改变行为的规则：

| feature id | 作用 |
|---|---|
| `ad-activity` | 拦广告 Activity 启动（开屏、激励视频、落地页） |
| `ad-view` | 广告 View 加入布局时置 GONE（按 SDK 类名前缀） |
| `ad-view-id` | 按资源名隐藏容器（Sigmob 的 `wm_*` 那套混淆弹窗） |
| `anythink-bridge` | **改 `MethodCall.method` 掐掉 Dart 侧广告加载** |
| `own-ad-cache` | 清 prefs 里的自家广告缓存 + 开关置 0 + 断曝光上报 |
| `ad-api` | 清空 `advert_resource/get` 的 `data.list`、点改 `config/get` |
| `sdk-init/*` | 7 家 SDK 的 init 方法级掐断 |

## 已移除的探针（不要重新加回来）

| 曾经的 feature | 用途 | 为什么移除 |
|---|---|---|
| `probe` | 列出实际加载的 SDK | 名单已固化进 `SDK_INIT_ENTRIES` |
| `net-probe` | 判断 HTTP 栈 | 结论已确定：OkHttp + Flutter 并存 |
| `url-probe` | 找广告接口 | 3 个接口已找到并写进规则 |
| `body-probe` | 看响应 JSON 结构 | 结构已吃透，`CONFIG_PATCHES` 就是产物 |
| `flutter-probe` | 通道测绘 | ~40 条通道已测完，`anythink_sdk` 已定点拦 |
| `survey` | 打控件资源名 | `AD_CONTAINER_IDS` 就是产物 |
| `native-bridge` | 改桥参数里的开关 | 实测对两处 Flutter 广告位无效 |
| `fake-vip` | 伪造 `isVip=1` | 实测无效，且是唯一伪造状态的规则，无收益不留 |

移除它们的收益不只是代码量：`flutter-probe` 挂在 `DartMessenger` 上，
每条通道消息都要过一次拦截器；`native-bridge` 与 `fake-vip` 挂在
`MethodCall` 构造与 `SharedPreferences` 读取这两条高频路径上。
去掉后 hook 点从 20 项降到 12 项，日志从数百行降到 130 行。

## 已知降级项

| 项 | 原因 |
|---|---|
| `sdk-init/WindAds` | `com.sigmob.windad.WindAds` 无 `startWithOptions` / `init` |
| `sdk-init/QyClient` | `com.mcto.sspsdk.QyClient` 无 `init` |

两家都是方法名不对，需要用真机反射把实际方法列表打出来再修。它们的 View 层
已被 `wm_*` 资源名规则覆盖，优先级不高。

---

# QQ 音乐（com.tencent.qqmusic）

适配版本 `20.7.5.8`（versionCode 7308），规则在 `rules/QqMusicRules.java`。

## 与 123 云盘处境完全相反

| 维度 | 123 云盘 | QQ 音乐 |
|---|---|---|
| 加固 | 爱加密整体加固，业务 dex 在 `assets/ijiami.dat` | **无加固**，25 个 dex 全明文（7.8–11.3 MB 各） |
| UI | Flutter（`libapp.so` + Skia 自绘） | 原生 View + Hippy（`libhippy.so` 15 MB） |
| 可静态分析 | 只能拿到壳 `s.h.e.l.l.*` | jadx 可直接读业务码 |
| `addView` 隐藏 | 对主界面**完全无效** | **有效**，广告控件都是真 View |

已确认无 `ijiami|libshell|libDexHelper|libjiagu|legu|libtprt|libmix` 任何特征，
`Application` 是明文的 `com.tencent.qqmusic.MusicApplication`。所以这个应用的
hook 点全部是**读过反编译源码后选的决策点**，不是靠真机探针猜的。

## 三家广告体系并存，类名全部未混淆

按类数量（11,647 个广告相关类）：

| 包 | 类数 | 身份 |
|---|---|---|
| `com/qq/e/comm` + `com/qq/e/tg` | 2511 | 广点通（优量汇）|
| `com/tencent/ams/fusion` | 1072 | 腾讯 AMS 竞价引擎 |
| `com/tencent/ams/mosaic` | 760 | AMS 动态模板 |
| `com/tencent/ams/dsdk` | 377 | AMS 引擎 |
| `com/tencentmusic/ad/*` | 1139 | TME 自家广告 SDK |
| `com/tencent/qqmusic/business/ad/*` | 2788 | 应用自家广告业务 |

**不存在**穿山甲、快手、AnyThink —— 与 123 云盘那种十几家聚合完全不同。

自家业务包把广告位置直接写进了包名，这是最有价值的发现：

```
ad/freemode 719   ad/recommend 551  ad/player 353   ad/splash 280
ad/reward 104     ad/topbarad 98    ad/media 91     ad/vipearningmode 63
ad/pauseEgg 19    ad/interstitial 7
```

## 最值钱的 hook：`PlayerAdControl` 的展示总闸

反编译 `com.tencent.qqmusic.business.ad.player.PlayerAdControl` 看到：

```java
public final boolean e(SongInfo song, AdType adType) {
    ...
    Log.h("EasterEggPlayerAdControl", "show ad new logic");
    return true;                      // 无条件放行
}
```

`AdType` 只有 `playerAD` 与 `easterEggAD` 两个枚举值，正好是「播放页广告」与
「暂停彩蛋」。这一个方法就是两者共用的总闸，恒返回 `false` 等于两个广告位一起关。

**方法名 `e` 没有硬编码**：按签名特征定位（返回 `boolean`、两参、第二参是枚举），
该组合在这个类里唯一，混淆改名不影响。特征不唯一时返回 null 让整项降级，不赌。

## 刻意不拦的部分

`KEEP_PREFIXES` 显式豁免这些子包，它们是「用户主动点了才出现、看广告换权益」：

- `ad/freemode`、`ad/radarfreemode` —— 免费听模式
- `ad/reward` —— 激励视频
- `ad/vipearningmode` —— 会员赚取模式
- `topbarad/freemode` —— 免费听入口条
- `ad/debug` —— 广告调试面板

同理 **`TMEAds.init` 不掐**：上面三个功能都走它，掐了会一起坏。广点通与
`TangramAdManager` 才是纯外部联盟，与权益无关，可以从初始化就断。

豁免必须写成显式清单：广告业务包有 20 多个子包，列「不拦哪些」比列「拦哪些」
短得多，也不会因为应用新增广告位而漏掉。判定时**豁免优先**——`freemode` 同时
也匹配 `business.ad` 前缀，顺序反了就会误杀。

## 拦 Activity 必须用精确全名，不能用前缀

开屏页 `com.tencent.qqmusic.activity.DynamicSplashActivity` 与应用主界面
`AppStarterActivity` 同在 `activity` 包下，用前缀会把整个应用拦死。

清单里的 5 个页面：`DynamicSplashActivity`（冷启开屏，是个 `WebViewActivity`
子类，靠 `auto_close_time` / `show_skip_btn` extra 驱动）、
`HotLaunchSplashActivity` 与 `HotLaunchLargeScreenSplashActivity`（热启开屏）、
`GDTLandingPageWebViewActivity`、`com.tencent.tads.splash.AdLandingPageActivity`。

## 插屏：hook `DialogFragment.show` 而不是业务触发点

插屏走 `business.ad.interstitial.InterstitialAdDialogFragment`。选框架类的
`show` 是因为签名稳定、且是所有弹出路径的必经处。

两个坑：
1. **必须按 `getThisObject()` 的类名过滤**。登录、分享、确认框都走同一个方法，
   不过滤会把应用所有弹窗干掉。
2. `show` 有 void 与 int 两种返回类型（`show(FragmentTransaction,String)` 返回
   事务 id）。返回类型不匹配会当场抛 `ClassCastException`，所以按返回类型分别
   给 `null` 与 `-1`。

## 真机验证结果（首轮就通过）

```
features OK(6): ad-activity, ad-view, player-ad, interstitial-ad,
                gdt-init/GDTADManager, gdt-init/TangramAdManager
E（错误）计数 0
player ad gate hooked: e(SongInfo, AdType)     ← 签名定位成功
```

命中统计（节流生效后，浏览首页 → 我的 → 关于 → 播放页，约 6 分钟）：

| 命中 | 说明 |
|---|---|
| `GDTADManager#initWith -> blocked` | 记到 `(x100)`，实际远多于此，SDK 在重试 |
| `TangramAdManager#init -> blocked` | 同上 |
| `hid ad view: TMENativeAdContainer` | 记到 `(x10)` |
| `hid ad view:` 其余 7 类 TME 控件 | 各 1 行（TrackExposureEmptyView / AdPlayedTimeView / VideoCoverImageView / VideoView / ExpressMediaControllerView / MediaView） |
| `hid ad view: business.ad.search.banner.BannerAdTopCropImageView` | 1 行 |
| `player ad gate -> false (e)` | 2 次 |

整份日志 33 行 / 3.0 KB，`E` 计数 0。开屏无广告直接进主界面，
首页、我的、关于、播放页均无广告位，播放正常，应用功能完好。

**反编译产物已归档到 `逆向/QQ音乐/`**（跟 123 云盘同规矩）：
`dex/`（25 个 dex）、`jadx_output/sources/`（11 个反编译类）、`ad-classes.txt`。

## 广点通重试风暴：定性清楚了，但故意不修

首轮 `initWith` 被拦 1950 次、`TangramAdManager#init` 632 次，日志 10 分钟 308 KB。

反编译 `GDTADManager.initWith` 看到原因：它靠实例字段 `Boolean f368a` 做
「已初始化」短路（`if (f368a) return true`），我们返回 `false` 时没有置那个字段，
所以调用方每次都认为初始化失败、下次继续重试。

**曾经打算反射把 `f368a` 置 `TRUE` 骗它已初始化，看完源码后放弃了。**
`initWith` 成功路径要建立 `APPStatus` / `DeviceStatus` / `SM` / `PM` 四个字段，
而 `TangramAdManager.init`、`TGSplashMaterialUtil.checkPreloadSplashMaterial`、
`getExposureChecker` 全都紧接着调 `GDTADManager.getInstance().getPM().getPOFactory()`。
只置标志位而不建那些对象，等于让 SDK 在自认就绪的状态下裸奔 —— 下一次调用就是 NPE。

让广告 SDK 反复重试是浪费；让它在半初始化状态下运行是**把目标应用推向崩溃**。
两者不是一个量级，所以选择留着重试。

真机验证也支持这个判断：返回 `false` 走的是 SDK 自己就有的失败分支，
`TangramAdManager.init` 收到 false 后只是 `onError(1)` + 打日志，不会异常。

### 改成只治日志：`hitThrottled`

`XLog.hitThrottled(key, msg)` 按 **10 的量级** 记（第 1、10、100、1000… 次），
并在行尾带累计次数 `(x100)`。日志长度对命中次数取对数：拦 3 次和拦 3 万次都只有
几行，但量级差异一眼可辨。第一次必写 —— 静默成功比可见失败更危险。

`ad-view` 也换成节流（按控件类名分 key），滑列表时同一个容器会反复出现。

配套加了 `XLog.callerSummary(n)`，只在**首次命中**时取一次调用栈定性重试来源。
取栈要遍历调用链并分配字符串，放在高频拦截点上会明显拖慢目标应用，
所以调用方必须自己保证只取一次（用 `hitThrottled` 的返回值判 `== 1`）。

实测结果：**同样的浏览流程，日志从 308 KB 降到 3.0 KB、33 行**，`E` 计数仍为 0。

首次命中打出的调用栈也确认了重试来源在 TME 侧而不是广点通自己：

```
TangramAdManager#init caller: ... <- android.app.NurleemFlisth#init
                               <- com.tencentmusic.ad.c1.a#c <- com.tencentmusic.ad.c1.c#invoke
```

`NurleemFlisth` 是 LSPosed 生成的 hook 桥类名（每次开机随机），
`com.tencentmusic.ad.c1.*` 才是真正的调用方 —— TME 的 SDK 初始化编排器在重试。
线程名 `TMEAds-init-async-tasks` → `TMEAds-AD-REQ#N` → `AMS-SDKInit0-thread-N`
也印证了这条链。

## QQ 音乐待办

1. 体验后决定是否再拦 `ad/recommend`（551 类，首页推荐位）与 `ad/naming`（冠名）
2. `ad/media` 91 类未看，可能是音频贴片广告
3. `topbarad` 至今一次都没命中过 —— 那个位置本来就没弹，不是规则漏了，
   等真见到横幅再补规则，别凭空加

## 通用待办

1. 修 `WindAds` / `QyClient` 的方法名（真机反射打出实际方法列表）
2. `FileLog` 收进 verbose 开关，别无条件写 `/sdcard`
3. 把 `ServiceBridge.scope()` 显示到 `MainActivity`
4. `Hooks` 用 id→handle 映射解决同 id 重复注册叠加的问题

## 真机验证流程

把 `<pkg>` 换成目标包名（`com.mfcloudcalculate.networkdisk` 或
`com.tencent.qqmusic`）：

```
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell rm -f /sdcard/Android/data/<pkg>/files/litiansuo-diag.log
adb shell am force-stop <pkg>
adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1
# 等 25 秒（QQ 音乐启动比 123 云盘慢）
adb shell grep -cE '\] E ' /sdcard/Android/data/<pkg>/files/litiansuo-diag.log
adb shell "grep -E '\] H ' <log> | sed 's/.*\] H //' | sort | uniq -c | sort -rn"
```

新增一个适配应用要同步改**四**处，少一处就静默不生效：

1. `core/AdaptedApps.java` 加常量 + `ENTRIES` 一条
2. `rules/AppRules.java` 加分支
3. `resources/META-INF/xposed/scope.list` 加包名（LSPosed 只注入这里声明的应用）
4. `AndroidManifest.xml` 的 `<queries>` 加一行（否则模块 App 查不到是否安装）

改完还要在 **LSPosed 管理器里手动勾选**新应用的作用域 —— `staticScope=true` 只是
把候选列表限定为 `scope.list`，不代表自动启用。第一轮 QQ 音乐日志文件压根没生成
就是这个原因，别误判成代码问题。

改任何代码后都必须 `force-stop`，否则旧进程里的 hook 不会更新。

日志前缀：`I` 普通、`W` 警告、`E` 错误、`H` 命中（规则真的起作用了）、
`RAW` 阶段事件（不经过 xposed，用于排除日志通道本身的问题）。
