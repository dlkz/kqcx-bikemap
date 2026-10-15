# 单车地图开发说明

面向仓库维护者。用户功能见 [README.md](README.md)，数据处理见 [PRIVACY.md](PRIVACY.md)，第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 1. 项目概览

- 应用定位：非官方 Android 工具，用于查看地图中心附近车辆、按编号定位车辆、生成二维码并提供本地提醒。
- 发布渠道：GitHub Releases。
- 原创代码许可：AGPL-3.0。
- 当前工程没有完整的单元测试或仪器测试套件，发布前依赖构建、静态检查和真机验证。

## 2. 技术基线

| 项 | 要求 |
|---|---|
| 语言 | Java |
| 工程 | 单模块 `:app` |
| Gradle / AGP | 8.13 / 8.13.2 |
| JDK | 17+ |
| Android SDK | compileSdk / targetSdk 36，minSdk 26 |
| namespace | `com.kqcx.bikemap` |
| Release applicationId | `com.kqcx.bikemap` |
| Debug applicationId | `com.kqcx.bikemap.debug` |
| 地图 | osmdroid Android 6.1.18 |
| 二维码 | ZXing Core 3.4.1 |
| AndroidX | Core 1.13.1、Activity 1.10.1 |
| 网络 | `HttpURLConnection`，车辆接口为 HTTPS |

依赖声明统一放在 `app/build.gradle.kts`。新增依赖前先确认必要性、许可证、体积和构建影响。

## 3. 工程结构

```text
app/src/main/java/com/kqcx/bikemap/    # Java 源码
app/src/main/res/                      # 布局、字符串、颜色、图标
app/src/main/assets/                   # 运行时编号目录
tools/                                 # 维护脚本
docs/developer-data/vehicle-number/    # 可公开的统计摘要
docs/release-notes/                    # 发布说明
LICENSES/                              # 第三方许可证文本
```

`MainActivity` 已超过 4000 行，集中承载地图、列表、设置、提醒和二维码流程。修改前应通读相关调用链，不做与任务无关的大范围重排或搬迁。

## 4. 版本与构建

### 4.1 版本规则

版本号和构建号保存在根目录 `version.properties`。例如 `2.1.1` 首次发布构建时应为：

```properties
versionName=2.1.1
buildNumberFor=2.1.1
buildNumber=1
```

`versionCode` 计算规则：

```text
主版本 × 100000
+ 小版本 × 1000
+ 补丁号 × 100
+ 构建号
```

约束：

- 主版本、小版本、补丁号均为数字。
- 小版本、补丁号范围 `0..99`。
- 构建号范围 `1..99`。
- 完整 `versionName` 变化时，构建号从 `1` 重计。
- 同一版本内构建号递增。
- `buildNumberFor` 保存当前完整版本轨道，例如 `2.1.1`。

示例：`2.1.1` 首次构建为 `versionCode=201101`。

成功执行 `assembleDebug`、`assembleRelease`、`bundleDebug` 或 `bundleRelease` 后，构建流程会把下一构建号写回 `version.properties`。这是构建溯源，不得回退。

### 4.2 构建命令

```powershell
$env:JAVA_HOME = "<JDK 17+ 路径>"
$env:ANDROID_HOME = "<Android SDK 路径>"

.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

### 4.3 构建变体

| 变体 | 用途 |
|---|---|
| `debug` | 本地调试，包名带 `.debug`，可与 Release 并行安装 |
| `release` | 正式发布，启用 R8 和资源裁剪，使用 Release 签名 |
| `releasePlain` | 仅供本地对比，不用于发布 |

### 4.4 签名与合规资产

- Release 签名从本地 `keystore.properties` 读取。
- `keystore.properties`、签名文件、`local.properties` 和本机凭据不得进入仓库。
- `prepareLegalAssets` 会把 `LICENSE`、`LICENSES/` 和 `THIRD_PARTY_NOTICES.md` 复制到 APK 的 `assets/legal/`。
- `releasePlain` 与 `release` 同包名、同签名，但未压缩，只用于本地比对。

## 5. 主要模块

| 模块 | 职责 |
|---|---|
| `MainActivity` | 地图、车辆列表、详情、设置、编号输入和二维码入口 |
| `AppMapView`、`MapLabelOverlay`、Callout 类 | 地图交互、标记和标签显示 |
| `BikeRepository` | 车辆接口请求、JSON 解析、距离计算和请求链路隔离 |
| `VehicleNumberCatalog` | 内置编号目录、尾号匹配和校园名解析 |
| `LearnedVehicleNumberStore` | 本机编号与校园名自学习，最多 500 条 LRU |
| `QrGalleryStore` | 二维码保存、所有权校验和延迟清理 |
| `ReminderScheduler` 及相关类 | 提醒调度、补排和前台倒计时通知 |
| `VehicleAlertService` | 车辆预警后台检测和通知 |
| `DiagnosticLog`、`BikeMapApplication` | 本机诊断日志和未捕获异常兜底 |

### 5.1 车辆查询

- `BikeRepository` 使用单线程 executor，避免多请求链路互相抢占。
- 两类接口统一校验信封错误；服务端 `resultMsg` 经 `safeServiceMessage` 过滤后再展示。
- `cancelPending()` 用于结束旧链路并换用新 executor，旧回调由调用方的 generation 忽略。
- 完整 9 位编号不依赖目录命中，始终可进入 `queryOneCar`。
- 3–4 位尾号匹配依赖内置目录。
- 选中已定位车辆并移动镜头后，在地图动画结束并等待落点稳定后按最终视口刷新顶部统计；连续查询失败后再选择列表车辆时，不保留上一次错误文案。

### 5.2 车辆预警

- 定位精度不超过 50 米时，按当前位置 100 米内可用车辆统计。
- 少于 3 辆时触发提醒，恢复到 4 辆以上时解除。
- 接口返回满 20 辆且疑似低库存时，追加四个偏移点查询，合并结果缓存 60 秒。
- 单次检查链路总预算 30 秒。
- 链路启动时保留覆盖预算的兜底闹钟；正常结束后由下一次检查覆盖。
- 超时按已获得的最佳统计结果结束，迟到的旧回调按 generation 忽略。
- 警告通知被用户或系统移除时，冷却状态失效，下一次同状态检查重新发布。

### 5.3 提醒

- 到点投递由系统精确闹钟触发。
- `ReminderForegroundService` 只显示倒计时通知，不承担到点投递。
- Android 12 及以上需要精确闹钟权限，Android 13 及以上需要通知权限。
- 设备重启或覆盖安装后只补排未结束的提醒。
- 应用在后台、锁屏或息屏时只发送系统通知，不拉起界面。

### 5.4 编号目录与自学习

- 运行时目录来自 `app/src/main/assets/vehicle_number_catalog.json`，由 `tools/generate_vehicle_number_catalog.py` 生成，勿手改。
- 多个 `--input` 按批次时间顺序传入，后传入批次覆盖同一编号的校园归属。
- `seed` 只在某 6 位号段唯一对应一个校园时用于补全。
- 目录在后台线程解析，解析完成后再替换占位目录。
- 自学习项只补未知编号；内置目录优先。
- 自学习项保存在 `app_settings` 的 `learned_vehicle_numbers`，最多 500 条 LRU；关闭“保留最近车辆编号”或清除记录时删除。

### 5.5 二维码

- `QrGalleryStore` 只删除本应用创建、通过所有权校验的精确对象。
- 二维码默认 30 秒后删除；进程中断后下次启动继续清理。
- 不按编号模糊查找删除，不删除整个 `Pictures/BikeMap/` 目录。

## 6. 数据与隐私

- Android 定位结果为 WGS-84，地图显示和车辆查询前转换为 GCJ-02。
- 查询附近车辆时，地图中心坐标发送至第三方接口。
- 按编号查询单车时，车辆编号和查询坐标发送至第三方接口。
- 地图瓦片由高德开放平台加载。
- 提醒设置、最近车辆编号和待清理记录仅保存在本机。
- 车辆预警开启且计时运行时，会按设定间隔定位并查询附近车辆。
- 不保存历史轨迹，不把定位或车辆查询数据发送到未披露服务。

公开数据边界：

- `app/src/main/assets/vehicle_number_catalog.json` 随 APK 分发，包含编号补全所需映射。
- `docs/developer-data/vehicle-number/` 只保留统计摘要。
- 完整车辆编号明细不提交公开仓库，由 `.gitignore` 排除。

## 7. 诊断日志

- 入口：「关于 → 问题排查」。
- 存储：`filesDir/diagnose/`。
- `event.log` 与 `event.log.1` 每个文件硬上限 256 KB。
- 单条事件记录上限 64 KB，过长消息和堆栈会截断并写入截断标记。
- `crash-<时间戳>.txt` 保留最近 5 份，每份硬上限 512 KB。
- 导出报告流式写入 `cacheDir/diagnose-export/`，最多保留 3 份。
- 日志写入同步完成；导出报告在后台线程生成，完成后回到主线程调起系统分享。
- `FileProvider` 只暴露 `diagnose-export/` 一个缓存根。
- 日志不联网、不上传，并排除在云备份和换机迁移之外。
- `allowBackup=false`，`backup_rules.xml` 和 `data_extraction_rules.xml` 全量排除诊断数据。
- `proguard-rules.pro` 当前为空；若将来加入日志裁剪规则，不得连带裁掉 `DiagnosticLog`。

脱敏红线：

- 常规事件日志不主动记录坐标、完整车辆编号、凭据或用户输入。
- 应用自行记录的车辆编号最多保留后三位。
- 崩溃堆栈和第三方错误文本只做基础过滤，不保证不含数字或其他敏感内容。
- 新增日志点前必须确认内容安全。

## 8. 验证

当前仓库没有完整自动化测试套件。修改后至少执行：

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

涉及以下功能时，必须补充模拟器或真机验证：

| 功能 | 重点 |
|---|---|
| 定位与地图 | 授权、拒绝、设置页返回、前后台切换 |
| 编号查询 | 完整编号、尾号匹配、目录外编号、失败回退 |
| 二维码 | 保存、分享、30 秒清理、应用重启后的续清理 |
| 提醒 | 倒计时、锁屏通知、后台存活、权限撤销、重启补排 |
| 车辆预警 | 开关、计时、后台定位、通知送达、通知补发 |
| 设置与主题 | 浅色、深色、跟随系统、大字体、小屏/大屏 |
| 诊断日志 | 导出、详细模式、清除、文件边界 |

编译成功不等于功能完成。无法执行真机验证时，必须写明未验证项目、原因和风险。

## 9. 发布

发布前检查：

- `version.properties` 与 Release notes 一致。
- 使用正式 Release 签名。
- `apksigner` 校验通过，并记录 APK 路径、版本号、文件大小、SHA-256 和证书摘要。
- `LICENSE`、`LICENSES/` 和 `THIRD_PARTY_NOTICES.md` 已进入 `assets/legal/`。
- README、PRIVACY、Release notes 与实际行为一致。
- Release notes 写清升级要求。
- Release 标签指向实际构建 APK 的源码提交。
- 构建后递增的 `version.properties` 与 Release 标签分开处理。

当前签名口径：

- `1.0.1` 及更早为旧证书，必须先卸载。
- `2.0.0` 及之后共用当前证书，可直接覆盖升级并保留本机数据。

## 10. 维护同步清单

| 变更类型 | 需要同步 |
|---|---|
| 用户可见功能或文案 | `README.md`、`strings.xml` |
| 权限、网络或数据行为 | `PRIVACY.md`、`AndroidManifest.xml` |
| 第三方组件或服务 | `THIRD_PARTY_NOTICES.md`、`LICENSES/` |
| 版本号或构建规则 | `version.properties`、Release notes、应用内更新日志 |
| 诊断日志格式或边界 | `PRIVACY.md`、本文档 |
| 车辆编号数据 | 运行时目录、开发者数据摘要 |
| 发布物 | APK、Release notes、源码标签、哈希记录 |

## 11. 已知限制

- 车辆位置、状态和编号数据来自第三方接口，可能延迟、偏差或缺失。
- 部分 Android 版本可能限制后台启动定位前台服务，车辆预警会安全暂停。
- 不同设备的后台限制、通知渠道、锁屏和横幅策略可能影响提醒展示。
- 当前没有完整自动化测试套件。
