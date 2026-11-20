# 单车地图开发说明

面向仓库维护者。用户功能见 [README.md](README.md)，数据处理见 [PRIVACY.md](PRIVACY.md)，第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 1. 快速开始与工程基线

单车地图是非官方 Android 车辆地图工具，用于附近车辆、编号查询、地图估算寻车、二维码和本地提醒。发布渠道为 GitHub Releases，原创代码使用 AGPL-3.0。

工程已有 JVM 状态测试和 Android 仪器测试，但不是完整端到端测试套件。构建、静态检查和必要的手工验证不能互相替代。

### 1.1 技术基线

| 项 | 要求 |
|---|---|
| 语言 / 模块 | Java，单模块 `:app` |
| Gradle / AGP | 8.13 / 8.13.2 |
| JDK | 17+ |
| Android SDK | compileSdk / targetSdk 36，minSdk 26 |
| namespace | `com.kqcx.bikemap` |
| applicationId | Release `com.kqcx.bikemap`；Debug `com.kqcx.bikemap.debug` |
| 地图 | osmdroid Android 6.1.18 |
| 二维码 | ZXing Core (Java) 3.4.1 |
| AndroidX | Activity 1.10.1、Core 1.13.1 |
| 网络 | `HttpURLConnection`，车辆接口 HTTPS |

直接依赖统一声明在 `app/build.gradle.kts`。传递依赖和许可证以实际解析结果及 `THIRD_PARTY_NOTICES.md` 为准。

### 1.2 工程结构

```text
app/src/main/java/com/kqcx/bikemap/    # 应用源码
app/src/main/res/                      # 布局、字符串、颜色和图标
app/src/main/assets/                   # 运行时编号目录
app/src/test/                          # JVM 状态测试
app/src/androidTest/                   # Android 仪器测试
tools/                                 # 目录生成和上传边界检查
docs/release-notes/                    # 发布说明
docs/developer-data/vehicle-number/    # 可公开的统计摘要
LICENSES/                              # 第三方许可证文本
```

### 1.3 修改前检查

1. 阅读根目录 `AGENTS.md`、本文件和任务相关的产品、隐私及第三方声明。
2. 执行 `git status --short --branch`，保留已有未提交修改和未跟踪资产。
3. 先阅读相关调用链、生命周期、权限和持久化路径，再做最小修改。
4. 不通过重排大型类、删除测试或扩大数据范围来“简化”问题。

`MainActivity` 是主流程编排中心，历史职责较多。新增逻辑应进入有明确边界的控制器或状态类，不继续堆叠可独立测试的 View 和状态逻辑。

## 2. 版本与构建

### 2.1 版本规则

版本号和构建号保存在根目录 `version.properties`。例如 `2.1.1` 首次发布构建时应为：

```properties
versionName=2.1.1
buildNumberFor=2.1.1
buildNumber=1
```

`versionCode` 计算规则：

```text
主版本 × 1000000
+ 小版本 × 10000
+ 补丁号 × 100
+ 构建号
```

约束：

- `versionName` 必须是规范的三段数字，不允许前导零或额外段。
- 主版本范围 `1..2146`。
- 小版本、补丁号范围 `0..99`。
- 构建号范围 `1..99`。
- 完整 `versionName` 变化时，构建号从 `1` 重计。
- 同一版本内构建号递增。
- `buildNumberFor` 保存当前完整版本轨道，例如 `2.1.1`。

示例：`2.1.1` 首次构建为 `versionCode=2010101`；`2.3.1` 构建号 `50`
为 `versionCode=2030150`。

`2.3.1` 构建号 `47` 起使用上述新编码；更早 Release notes 中的
`versionCode` 保留为历史记录，不再用于后续构建。

成功执行 `assembleDebug`、`assembleRelease`、`bundleDebug` 或 `bundleRelease` 后，构建流程会把下一构建号写回 `version.properties`。这是构建溯源，不得回退。

### 2.2 构建变体

| 变体 | 用途 |
|---|---|
| `debug` | 本地调试，包名带 `.debug`，可与 Release 并行安装 |
| `release` | 正式发布，启用 R8 和资源裁剪，使用 Release 签名 |
| `releasePlain` | 与 Release 同包名、同签名但未压缩；仅供本地对比，不用于发布 |

## 3. 架构与关键流程

### 3.1 主要模块

| 模块 | 职责 |
|---|---|
| `MainActivity` | 地图、车辆列表、详情、设置、编号输入和二维码入口 |
| `VehicleListItemFactory` | 车辆地图卡片和展开列表行的 View 创建与点击绑定 |
| `VehicleListPanelController` | 车辆列表面板的显示、展开、拖动、渲染和地图控件避让 |
| `QrHistoryStore` | 最近生成记录的持久化、去重、三条上限和尾号补全 |
| `VehicleNumberDialogHistoryPanel` | 编号弹窗最近生成与最近寻车区块的渲染和操作 |
| `VehicleNumberInputController` | 编号输入弹窗、目录异步刷新、范围选择和确认流程 |
| `VehicleNumberInputResolver` | 完整编号、尾号、候选和加载状态解析 |
| `BikeDetailPanelController` | 车辆详情卡片绑定、按钮状态和二维码/寻车入口 |
| `MapRecenterState` | 首次定位、重新定位跟随窗口、用户中断、车辆聚焦和落点代次 |
| `NearbyQueryState` | 附近车辆请求合并、最短间隔、距离阈值和待处理中心 |
| `VehicleFinderActivity` | 寻车页生命周期、定位、刷新、方向与提示编排 |
| `VehicleFinderRadarView` | 雷达距离分档动画 |
| `VehicleFinderBleController`、`VehicleBleScanner`、`VehicleBleMath`、`VehicleBleSignalTracker` | 前台 BLE 扫描、目标匹配、RSSI 分档和超时 |
| `VehicleHeadingTracker` | 方向传感器预热、可靠性和平滑 |
| `AppMapView`、`MapLabelOverlay`、Callout 类 | 地图交互、标记和标签显示 |
| `BikeRepository` | 车辆接口请求、JSON 解析、距离计算和请求链路隔离 |
| `LocationAcceptancePolicy` | 主页与寻车页共享的定位时间、提供者和精度采纳规则 |
| `VehicleNumberCatalog` | 内置编号目录、尾号匹配和校园名解析 |
| `LearnedVehicleNumberStore` | 本机编号与校园名自学习，最多 500 条 LRU |
| `FinderSnapshotStore`、`FinderSnapshotCapture` | 最近寻车快照的持久化、去重、清除代次和异步蓝牙补全 |
| `QrGalleryStore` | 二维码保存、所有权校验和延迟清理 |
| `ReminderScheduler` 及相关类 | 提醒调度、补排和前台倒计时通知 |
| `VehicleAlertService` | 车辆预警后台检测和通知 |
| `DiagnosticLog`、`BikeMapApplication` | 本机诊断日志和未捕获异常兜底 |

### 3.2 车辆查询

- `BikeRepository` 使用单线程 executor，避免多请求链路互相抢占。
- 两类接口统一校验信封错误；服务端 `resultMsg` 只经 `safeDisplayMessage` 过滤用于界面展示。
  该过滤器不是日志脱敏器，原始或过滤后的第三方正文都不得传给 `DiagnosticLog`。
- `cancelPending()` 用于结束旧链路并换用新 executor，旧回调由调用方的 generation 忽略。
- 完整 9 位编号不依赖目录命中，始终可进入 `queryOneCar`。
- 3–4 位尾号匹配依赖内置目录。
- 选中已定位车辆并移动镜头后，在地图动画结束并等待落点稳定后按最终视口刷新顶部统计；连续查询失败后再选择列表车辆时，不保留上一次错误文案。
- 页面不在前台时不启动新请求；附近刷新重建列表时保留正在按编号查询的车辆选择。

### 3.3 车辆预警

- 定位精度不超过 50 米时，按当前位置 100 米内可用车辆统计。
- 少于 3 辆时触发提醒，恢复到 4 辆以上时解除。
- 接口返回满 20 辆且疑似低库存时，追加四个偏移点查询，合并结果缓存 60 秒。
- 单次检查链路总预算 30 秒。
- 链路启动时保留覆盖预算的兜底闹钟；正常结束后由下一次检查覆盖。
- 超时按已获得的最佳统计结果结束，迟到的旧回调按 generation 忽略。
- 警告通知被用户或系统移除时，冷却状态失效，下一次同状态检查重新发布。

### 3.4 提醒

- 到点投递由系统精确闹钟触发。
- `ReminderForegroundService` 只显示倒计时通知，不承担到点投递。
- Android 12 及以上需要精确闹钟权限，Android 13 及以上需要通知权限。
- 设备重启或覆盖安装后只补排未结束的提醒。
- 应用在后台、锁屏或息屏时只发送系统通知，不拉起界面。

### 3.5 编号目录与自学习

- 运行时目录来自 `app/src/main/assets/vehicle_number_catalog.json`，由 `tools/generate_vehicle_number_catalog.py` 生成，勿手改。
- 多个 `--input` 按批次时间顺序传入，后传入批次覆盖同一编号的校园归属。
- 截至 2026-11-07，运行时目录由六轮扫描并集生成，共 5,595 个编号、8 个站点；公开统计摘要描述同轮最新单次重扫的 5,105 个编号，不等同于运行时并集。
- 编号来源优先级为：内置精确编号 > 本机自学习精确编号 > `seed` 号段推断。自学习不会覆盖内置精确编号；`seed` 只在前两者均无结果且某 6 位号段唯一对应一个校园时用于补全。
- 目录在后台线程解析，解析完成后再替换占位目录。
- 目录解析失败不影响完整 9 位编号直接查询；目录辅助的 3–4 位尾号匹配、校园/号段选择和站点推断会关闭，并显示目录不可用提示。
- 自学习项只补内置精确编号未覆盖的编号，不能覆盖内置精确归属。
- 自学习项保存在 `app_settings` 的 `learned_vehicle_numbers`，最多 500 条 LRU；关闭“保留最近车辆编号”或清除记录时删除。
- 清除自学习记录会推进代次，清除前已开始的在途查询不能重新写回旧数据。

### 3.6 二维码

- `QrGalleryStore` 只删除本应用创建、通过所有权校验的精确对象。
- 二维码默认 30 秒后删除；进程中断后下次启动继续清理。
- 不按编号模糊查找删除，不删除整个 `Pictures/BikeMap/` 目录。

### 3.7 地图估算寻车

#### 3.7.1 状态、快照与刷新

- `VehicleFinderTargetState` 维护车辆位置及 `ESTIMATED` / `REFRESHED` / `SNAPSHOT` 来源；刷新失败或返回旧位置时，保留现有坐标和来源。
- 非 `SNAPSHOT` 来源进入寻车页时，车辆上报时间未知、超过 2 分钟或缺少蓝牙名称，会触发一次有界刷新；同一 Activity 只自动尝试一次，不做持续轮询。
- 返回前台时，当前位置超过 5 秒会重新获取；目标坐标、上报时间、来源和蓝牙名称通过 `savedInstanceState` 恢复。
- “开始寻车”和二维码跳转前可保存快照。快照最多 3 条，关闭保留记录时同步清除，并保留清除代次防止旧异步任务写回。

#### 3.7.2 定位与方向

- 页面使用同一份已采纳的 `currentLocation` 计算距离、方位和质量；定位误差必须满足按车距计算的方向阈值。
- 5–8 秒的短暂定位降级可以保持最后方向，但车距变化超过 5 米或方位变化超过 45 度时立即取消。
- 指南针需要连续稳定样本后才能显示相对方向；低精度、不可靠或失去接触时隐藏箭头。
- 刷新同时更新车辆位置和手机位置，忽略刷新开始前的缓存点；较差网络定位最多等待 5 秒良好 GPS。
- 车辆位置超过 10 分钟提示可能变化，超过 1 小时使用过期警告；当前位置超过 2 分钟或位置未知时必须显示文字警告。

#### 3.7.3 BLE 辅助

- 只在用户主动开启且寻车页位于前台时扫描；按广播名称精确匹配，不使用“RSSI 最强设备”猜测目标。
- 扫描到的广播名称、BLE 地址、广播载荷和原始 RSSI 不持久化。接口返回的 `bluetoothName` 可随寻车快照保存在本机，用于后续精确匹配。
- RSSI 只用于“很强 / 较强 / 较弱 / 丢失”分档；短暂无广播先提示减弱，8 秒未恢复才确认丢失。
- 远端信号只用于面板提示，连续稳定进入较强档后才接管中央距离；BLE 不提供精确米数或方向。
- 当前验证矩阵：`100000` 开头的 1 序列已验证可用；`200000` 开头的 2 序列明确不支持；其他 9 位序列尚未验证，采用尽力匹配。现有界面只在 2 序列上给出明确的不支持提示，不额外标记其他未验证序列。
- 2 序列车辆以完整编号前缀 `200000` 判定。本版本没有可验证的 2 序列编号与广播名称映射，因此明确不支持 BLE 辅助，也不使用“RSSI 最强设备”或人工名称映射进行猜测。
- 2 序列判断先于蓝牙名称、设备和权限判断：寻车页固定显示“该车辆暂不支持蓝牙寻车”，隐藏扫描按钮，也不会仅为该类车辆申请 BLE 权限。地图估算距离、方向、位置刷新和寻车快照仍可使用。
- 2 序列车辆缺少蓝牙名称时，不触发额外的 BLE 身份刷新；车辆位置过期时仍按通用 2 分钟规则刷新。
- `supportsDirectMatch()` 同时要求 9 位数字编号、非 2 序列和接口返回可精确匹配的广播名称；该方法只表示设备具备尝试匹配的条件，不代表该号段已经完成验证。其他非 2 序列若提供可匹配名称，仍会显示普通蓝牙寻车入口。
- 将来支持 2 序列前，必须先引入经过验证的身份映射、匹配规则和边界测试，不能只移除前缀排除。

#### 3.7.4 关键阈值

| 场景 | 阈值 / 规则 | 代码入口 |
|---|---|---|
| 当前位置可用上限 | 120 秒 | `LOCATION_MAX_AGE_MS` |
| 目标自动刷新 | 2 分钟或缺少蓝牙名称 | `shouldAutoRefreshTarget()` |
| 目标老化 / 过期 | 10 分钟 / 1 小时 | `VehicleFinderMath.targetFreshness()` |
| 方向最多保持 | 8 秒 | `DIRECTION_HOLD_MAX_AGE_MS` |
| BLE 扫描超时 | 30 秒 | `DISCOVERY_TIMEOUT_MS` |

## 4. 数据、日志与安全边界

用户可见的数据流和保留期限以 `PRIVACY.md` 为准；本节只记录实现不变量。

### 4.1 数据边界

- 定位为 WGS-84，地图显示和车辆查询前转换为 GCJ-02；不保存手机历史轨迹。
- 附近查询发送地图中心坐标；编号查询和寻车刷新发送车辆编号与查询坐标；瓦片请求由高德处理。
- 应用私有 SharedPreferences 保存设置、最近编号、寻车快照和清除代次；`allowBackup=false`，备份和换机迁移规则全量排除。
- 现场 BLE 名称、地址、广播载荷和原始 RSSI 不持久化；接口返回的 `bluetoothName` 可随寻车快照保存。
- `vehicle_number_catalog.json` 随 APK 分发。公开仓库只保留统计摘要，完整编号明细由 `.gitignore` 排除。

### 4.2 诊断日志

- 存储为 `filesDir/diagnose/`；事件文件每个上限 256 KB、单条上限 64 KB；崩溃记录保留 5 份，每份上限 512 KB。
- 导出报告流式写入 `cacheDir/diagnose-export/`，保留 3 份；FileProvider 只暴露该目录。
- Release 不写 logcat，Debug 会镜像；写入文件、Debug logcat 和导出历史日志时遮挡连续九位编号，并修复历史 NUL。
- 常规事件不主动记录坐标、完整编号、凭据、MediaStore URI 或用户输入；崩溃堆栈仍可能包含非预期内容。
- 详细层只记录定位质量、距离、方向和 BLE 分档等排查字段，不记录原始坐标、角度、RSSI 或完整编号。
- 第三方正文只用于 UI，`safeDisplayMessage` 不是日志脱敏器；`resultMsg` 和异常派生文本禁止传给 `DiagnosticLog`。
- 关闭详细日志只停止新增，不删除已有记录；导出头显示当前开关和实际详细记录数。
- 清除会推进导出代次；导出必须绑定代次，并在生成、裁剪和分享前验证，防止清除后的旧任务重新生成或分享。
- `backup_rules.xml` 和 `data_extraction_rules.xml` 全量排除诊断数据；若增加日志 keep 规则，不得裁掉 `DiagnosticLog`。

## 5. 构建与验证

现有测试覆盖状态机、隐私边界和主要 View 控制器，但不是完整端到端测试套件。命令应逐项执行，任一步失败即停止后续发布步骤。

```powershell
$env:JAVA_HOME = "<JDK 17+ 路径>"
$env:ANDROID_HOME = "<Android SDK 路径>"

.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease

# 连接多台设备时，仪器测试必须指定目标设备
$env:ANDROID_SERIAL = "<设备序列号>"
.\gradlew.bat :app:connectedDebugAndroidTest
```

`assembleDebug`、`assembleRelease`、`bundleDebug` 和 `bundleRelease` 会推进 `version.properties`；验证时也要保留这一构建记录。

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

## 6. 发布与上传

### 6.1 发布检查

- 使用正式 Release 签名；签名配置只从本地 `keystore.properties` 读取，凭据和签名文件不得进入仓库或日志。
- 锁定唯一 APK，记录路径、`versionName`、`versionCode`、大小、SHA-256 和证书摘要，并执行 `apksigner verify`。
- `prepareLegalAssets` 必须把 `LICENSE`、`LICENSES/` 和 `THIRD_PARTY_NOTICES.md` 放入 `assets/legal/`。
- README、PRIVACY、Release notes、应用内更新日志和实际行为必须一致。
- Release notes 写清升级要求、已知限制和准确的 `versionCode`；标签必须指向实际构建 APK 的源码提交。
- 构建自动递增的 `version.properties` 属于下一次构建记录，不得回退，也不应与旧 Release 标签混为一体。

### 6.2 签名兼容

- `1.0.1` 及更早使用旧证书，必须先卸载；卸载会清除旧版本机数据。
- `2.0.0` 及之后共用当前证书，可直接覆盖升级并保留本机数据。

### 6.3 公开上传边界

不要直接上传或压缩整个工作区。公开代码包优先从已审查提交执行 `git archive HEAD`，或显式暂存文件后执行：

```powershell
.\tools\verify_upload_boundary.ps1
```

脚本检查暂存内容和待推送提交新增文本，拒绝密钥、签名配置、APK/AAB、日志、构建缓存、完整编号明细、本机维护资料和本机路径。测试样本必须使用不在运行时目录中的合成编号。

本机维护者还需保证签名文件 ACL 仅允许当前用户、`SYSTEM` 和管理员；设备型号、序列号等本机标记通过 `-LocalMarkers` 或 `BIKEMAP_LOCAL_MARKERS` 临时传入检查，不写入仓库。

## 7. 维护同步与已知限制

### 7.1 变更同步

| 变更类型 | 需要同步 |
|---|---|
| 用户可见功能或文案 | `README.md`、`strings.xml` |
| 权限、网络或数据行为 | `PRIVACY.md`、`AndroidManifest.xml` |
| 第三方组件或服务 | `THIRD_PARTY_NOTICES.md`、`LICENSES/` |
| 版本号或构建规则 | `version.properties`、Release notes、应用内更新日志 |
| 诊断日志格式或边界 | `PRIVACY.md`、本文档 |
| 车辆编号数据 | 运行时目录、开发者数据摘要 |
| 发布物 | APK、Release notes、源码标签、哈希记录 |

### 7.2 已知限制

- 车辆位置、状态和编号数据来自第三方接口，可能延迟、偏差或缺失。
- 部分 Android 版本可能限制后台启动定位前台服务，车辆预警会安全暂停。
- 不同设备的后台限制、通知渠道、锁屏和横幅策略可能影响提醒展示。
- 现有 JVM 和仪器测试仍不是完整端到端测试套件。
