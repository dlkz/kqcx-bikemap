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
- 提醒倒计时、锁屏通知、后台存活和权限撤销后的恢复流程。
- 车辆预警开关、检测计时、后台定位和通知送达。
- 浅色、深色、小屏和大字体布局。

编译成功不等于功能验证完成；验证结果应区分已测试项、未测试项和环境限制。

## 工程结构

```text
app/src/main/java/com/kqcx/bikemap/    # 主程序
app/src/main/res/                      # 布局、字符串、颜色、图标
app/src/main/assets/                   # 运行时数据
docs/developer-data/vehicle-number/    # 可供维护者使用的统计摘要
LICENSES/                              # 第三方许可证文本
```

主要模块：

- `MainActivity`：主页面、地图、车辆详情、设置和二维码流程。
- `BikeRepository`：车辆接口请求、JSON 解析和距离计算。
- `VehicleNumberCatalog`：车辆编号索引与尾号匹配。
- `QrGalleryStore`：二维码保存、所有权校验和延迟清理。
- `ReminderScheduler`、`ReminderReceiver`、`ReminderForegroundService`：本地提醒和前台通知。
- `VehicleAlertService`：车辆预警后台检测与通知。

## 数据与隐私

- Android 定位结果为 WGS-84，地图显示和车辆查询前转换为 GCJ-02。
- 车辆查询会将地图中心坐标发送至第三方数据接口；地图瓦片由高德开放平台加载。
- 车辆位置和状态由第三方接口提供，可能存在延迟、偏差或缺失。
- 提醒设置、最近车辆编号和待清理记录仅保存在设备本地。
- 运行时 `app/src/main/assets/vehicle_number_catalog.json` 包含编号补全所需的映射，随 APK 分发。
- `docs/developer-data/vehicle-number/` 仅保留统计摘要。完整明细不提交到公开仓库，并由 `.gitignore` 的通用规则排除。
- 不提交 `keystore.properties`、签名文件、`local.properties`、构建缓存或本地凭据。

## 发布

- 发布包必须使用正式 Release 签名。
- 2.0.0 使用新的签名身份，与 1.x 正式版不能覆盖安装或共存；升级前需要卸载旧版。
- 发布 APK 时，应在 GitHub Release 同时提供对应源码或源码链接。
- 本应用原创代码按 AGPL-3.0 授权；第三方组件与参考项目继续遵循各自许可证。

发布前检查：

- `version.properties` 的版本号与 Release 说明一致。
- 使用正式签名，并通过 `apksigner` 校验。
- `LICENSE`、`LICENSES/` 和 `THIRD_PARTY_NOTICES.md` 已进入 APK 的 `assets/legal/`。
- README、PRIVACY、Release notes 与实际行为一致。
- Release notes 明确标注与旧版不能共存。
- Release 标签对应正确的构建源码提交。
- 记录 APK 文件名、大小和 SHA-256。
