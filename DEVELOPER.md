# 单车地图开发说明

面向 GitHub 仓库维护者。用户功能见 [README.md](README.md)，数据处理见 [PRIVACY.md](PRIVACY.md)，第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 技术基线

| 项 | 要求 |
|---|---|
| 语言 | Java |
| 工程 | 单模块 `:app` |
| Gradle / AGP | 8.13 / 8.13.2 |
| JDK | 17+ |
| Android SDK | compileSdk / targetSdk 36，minSdk 26 |
| applicationId | Release：`com.kqcx.bikemap`；Debug：`com.kqcx.bikemap.debug` |

版本号和构建号保存在根目录 `version.properties`。成功执行 `assembleDebug`、`assembleRelease`、`bundleDebug` 或 `bundleRelease` 后，下一次构建号自动加一。

## 构建

```powershell
$env:JAVA_HOME = "<JDK 17+ 路径>"
$env:ANDROID_HOME = "<Android SDK 路径>"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Release 签名依赖本地 `keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`、`storeType`）。该文件和签名文件不进入仓库。

构建变体：

| 变体 | 用途 |
|---|---|
| `debug` | 本地调试，包名带 `.debug`，可与 Release 并行安装 |
| `release` | 正式发布，启用 R8 和资源裁剪，使用 Release 签名 |
| `releasePlain` | 仅供本地对比，不用于发布 |

## 验证

当前仓库没有完整的单元测试或仪器测试套件。修改后至少执行：

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

涉及地图、定位、二维码相册、通知、提醒、车辆预警或前台服务时，还需要真机验证：

- 定位授权、拒绝授权和设置页返回。
- 二维码保存、分享、30 秒清理及应用重启后的续清理。
- 提醒倒计时、锁屏通知、后台存活、权限撤销后的恢复，以及设备重启/覆盖安装后的补排。
- 车辆预警开关、检测计时、后台定位和通知送达。
- 浅色、深色、小屏和大字体布局。

编译成功不等于功能验证完成；验证结果应区分已测试项、未测试项和环境限制。

## 工程结构

```text
app/src/main/java/com/kqcx/bikemap/    # 主程序
app/src/main/res/                      # 布局、字符串、颜色、图标
app/src/main/assets/                   # 运行时数据
tools/                                 # 维护脚本（如车辆编号目录生成器）
docs/developer-data/vehicle-number/    # 可供维护者使用的统计摘要
LICENSES/                              # 第三方许可证文本
```

主要模块：

- `MainActivity`：主页面、地图、车辆详情、设置和二维码流程。
- `BikeRepository`：车辆接口请求、JSON 解析和距离计算。
  - 两类查询统一走 `throwIfEnvelopeError`：接口信封 `errorCode` 非 0 时，透传服务端 `resultMsg`（经 `safeServiceMessage` 校验，缺失或不安全时回落通用提示）。
- `VehicleNumberCatalog`：车辆编号索引与尾号匹配。内置目录来自
  `app/src/main/assets/vehicle_number_catalog.json`（schema 含 `version`、
  `updatedAt`、`sites`、`seed`、`numbers`），并合并 `LearnedVehicleNumberStore`
  的本地自学习项；内置编号优先，学习项只补未知。目录在后台线程解析
  （`MainActivity.loadVehicleNumberCatalogAsync`），先用空目录占位，解析完成后再替换，
  避免目录变大时卡住启动；解析失败则保持空目录，仅编号补全不可用。已被内置目录覆盖的
  学习项会在加载时清除，避免将来内置编号被删除后由陈旧学习项“复活”。
  完整 9 位编号不依赖目录命中，始终可直接进入 `queryOneCar`；目录只负责尾号匹配和校园名补全。
- `LearnedVehicleNumberStore`：本地自学习层。按编号点名查到车后记录“编号→校园名”，
  存在 `app_settings` 的 `learned_vehicle_numbers`，最多 500 条（LRU），
  关闭“保留最近车辆编号”或清除记录时删除，不联网上传。
- `QrGalleryStore`：二维码保存、所有权校验和延迟清理。
- `ReminderScheduler`、`ReminderReceiver`、`ReminderRescheduleReceiver`、`ReminderForegroundService`：
  精确闹钟到点投递、设备重启/覆盖安装补排，以及前台倒计时通知；前台服务不承担到点触发。
- `VehicleAlertService`：车辆预警后台检测与通知。
- `DiagnosticLog`、`BikeMapApplication`：本机诊断日志与未捕获异常兜底；事件骨架常开，
  详细日志按需打开，「关于」页负责导出。

## 数据与隐私

- Android 定位结果为 WGS-84，地图显示和车辆查询前转换为 GCJ-02。
- 车辆查询会将地图中心坐标发送至第三方数据接口；按编号查询单车（`v2.0.0/queryOneCar`）会发送车辆编号与查询坐标；地图瓦片由高德开放平台加载。
- 车辆位置和状态由第三方接口提供，可能存在延迟、偏差或缺失。
- 提醒设置、最近车辆编号和待清理记录仅保存在设备本地。
- 运行时 `app/src/main/assets/vehicle_number_catalog.json` 包含编号补全所需的映射，随 APK 分发。
- 该目录由 `tools/generate_vehicle_number_catalog.py` 从各批次扫描明细生成，勿手改。多个 `--input` 按批次时间顺序传入，后传入的批次覆盖同一编号的校园归属（车辆可能在校园间调动）：
  `py tools/generate_vehicle_number_catalog.py --input <批次明细.csv> --input <更晚批次明细.csv> --updated-at <日期> --output app/src/main/assets/vehicle_number_catalog.json`。
  当前资产由 2026-09-27 至 2026-10-10 共五批扫描的并集生成，含 5,312 个编号、8 个接口站点（含站点 68 江西科技师范大学（枫林校区））。用 `--check` 校验已提交资产是否最新。`seed` 是号段级兜底：仅当某 6 位号段唯一对应一个校园时才用于补全，多校园共享的号段不猜。
- `docs/developer-data/vehicle-number/` 的公开摘要当前记录最新单次验证扫描（4,289 辆、8 个接口站点），不等于运行时目录的五批并集（5,312 个编号、8 个接口站点）。
- `docs/developer-data/vehicle-number/` 仅保留统计摘要。完整明细不提交到公开仓库，并由 `.gitignore` 的通用规则排除。
- 不提交 `keystore.properties`、签名文件、`local.properties`、构建缓存或本地凭据。

## 诊断日志

- 入口：「关于 → 问题排查」。「导出诊断日志」把事件日志与崩溃记录合成一份文本后走系统
  分享；「详细诊断日志」开关控制是否记录更细的内容；「清除诊断日志」删除本机事件日志、
  崩溃记录和导出报告，不改变详细日志开关状态。
- 存储：`filesDir/diagnose/`，`event.log` 超过 256 KB 后滚动到 `event.log.1`，
  两个事件日志按文件数量封顶；单条过长记录可能使文件略超阈值。
  `crash-<时间戳>.txt` 保留最近 5 份，单个崩溃文件没有字节上限；导出的合成报告写在
  `cacheDir/diagnose-export/`，最多保留 3 份，单个报告没有字节上限。
- FileProvider：authority `${applicationId}.fileprovider`；`res/xml/file_paths.xml`
  只开 `diagnose-export/` 一个根，多一个根就多一份被其他应用读到的可能。
- 写入是同步完成的，不走异步队列：崩溃前最后几条不能丢，而那恰恰是最有价值的一段。
- 挂接点：`BikeMapApplication.onCreate` 装 `Thread.setDefaultUncaughtExceptionHandler`
  （链式调用原 handler，不改变系统原有的崩溃行为）；其余日志统一走
  `DiagnosticLog.event/detail/warn/error`，不要再直接调用 `android.util.Log`。
- **脱敏红线**：新增日志点不得主动记录坐标、完整车辆编号、凭据或用户输入；应用自行拼接的
  编号最多记后三位。接口错误文本会经过 `safeServiceMessage` 的基础过滤，但该过滤不移除数字，
  不能把第三方返回消息视为完全脱敏，写入日志前必须再确认内容安全。
- 备份：`allowBackup=false`，且 `backup_rules.xml`、`data_extraction_rules.xml` 已全量
  排除，诊断日志不会随云备份或换机迁移离开设备。
- `proguard-rules.pro` 目前为空，R8 不会移除日志调用；若将来加入
  `-assumenosideeffects class android.util.Log`，注意不要连带裁掉 `DiagnosticLog`。

## 发布

- 发布包必须使用正式 Release 签名。
- 签名身份自 1.0.2 起为当前证书；1.0.1 及更早是旧证书，不能覆盖安装，必须先卸载。
  2.0.0 及之后的正式版共用同一签名，可以直接覆盖升级，本机设置与记录保留。
- `versionCode` = `主版本 × 100,000 + 小版本 × 1,000 + 构建号`（见 `app/build.gradle.kts`）。
  构建号按“主版本.小版本”轨道从 1 重置，同一小版本下的补丁版本继续沿用构建序列，
  versionCode 保持单调递增；`version.properties` 的 `buildNumberFor` 保存当前轨道（如 `2.1`），
  主版本或小版本变化时才重新计起。上限：构建号 999、小版本 99，越界直接构建失败；
  debug 与 release 共用同一套编号。
- 发布 APK 时，应在 GitHub Release 同时提供对应源码或源码链接。
- 本应用原创代码按 AGPL-3.0 授权；第三方组件与参考项目继续遵循各自许可证。

发布前检查：

- `version.properties` 的版本号与 Release 说明一致。
- 使用正式签名，并通过 `apksigner` 校验。
- `LICENSE`、`LICENSES/` 和 `THIRD_PARTY_NOTICES.md` 已进入 APK 的 `assets/legal/`。
- README、PRIVACY、Release notes 与实际行为一致。
- Release notes 写清升级要求：1.0.1 及更早需先卸载旧版，2.0.0 及之后可直接覆盖升级。
- Release 标签对应正确的构建源码提交。
- 记录 APK 文件名、大小和 SHA-256。
