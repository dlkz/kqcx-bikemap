# 单车地图开发说明

本文件面向开发、构建和维护。用户功能与使用说明见 [README.md](README.md)，隐私说明见 [PRIVACY.md](PRIVACY.md)，第三方组件与服务声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Android SDK | Platform 36，Build-Tools 36.x |
| Gradle | Wrapper 8.x（已随仓库配置） |
| minSdk / targetSdk | 26 / 36 |
| applicationId | Release：`com.kqcx.bikemap`；Debug：`com.kqcx.bikemap.debug` |

## 构建号

构建号保存在仓库根目录的 `version.properties`。每次成功执行 `assembleDebug`、`assembleRelease`、`bundleDebug` 或 `bundleRelease` 后，下一次构建号会自动加一；本次安装包的 `versionCode` 与“关于”页显示的构建号一致。

## 构建类型

- `debug`：包名追加 `.debug`，版本名追加 `-debug`，用于本地调试和并行安装。
- `release`：当前开启 R8 代码压缩和资源裁剪，用于正式发布。
- `releasePlain`：与 `release` 使用相同包名和签名，但关闭 R8 与资源裁剪，仅供本地留存和对比，不用于正式发布。

## 构建与安装

```powershell
$env:JAVA_HOME = "C:\path\to\jdk"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
.\gradlew.bat :app:assembleDebug
```

调试安装包位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备或模拟器：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Debug 与 Release 并行测试

- Debug 使用 `com.kqcx.bikemap.debug`，应用名为“单车地图 Debug”，版本名带 `-debug`。
- Release 使用 `com.kqcx.bikemap`，应用名为“单车地图”。
- 两者签名和 `applicationId` 均不同，可同时安装、分别保留数据，不会互相覆盖。
- Debug 启动组件时需要使用完整组件名：`com.kqcx.bikemap.debug/com.kqcx.bikemap.MainActivity`。

## 法律资源打包

构建时会把 `LICENSE`、第三方声明和 Apache-2.0 全文放入 APK 的 `assets/legal/`，随安装包一并分发。

## 工程结构

```text
app/src/main/java/com/kqcx/bikemap/
  MainActivity.java          # 主页面、地图、车辆查询、详情与设置
  AboutActivity.java         # 版本信息与更新日志
  BaseActivity.java          # 主题与版本公共逻辑
  AppMapView.java            # 地图手势与交互
  BikeRepository.java        # 车辆数据请求与状态管理
  VehicleAlertService.java   # 车辆预警后台检测与通知
  VehicleNumberCatalog.java  # 校园、号段与车辆编号索引
  MapLabelOverlay.java       # 地图地名标签绘制
  ReminderScheduler.java     # 提醒计时、精确闹钟与通知渠道
  ReminderReceiver.java      # 提醒到点后发送系统通知
  ReminderForegroundService.java # 提醒期间的前台常驻通知
  QrGalleryStore.java        # 临时二维码保存、所有权校验与清理
  Bike.java / BikeCallout.java
  CoordinateUtils.java / UserLocationCallout.java
app/src/main/res/
  layout/                    # 首页、地图标注、详情弹窗与设置页面
  values/                    # 字符串、颜色、主题
  drawable/                  # 图标与背景资源
docs/developer-data/vehicle-number/
  *.csv / *.md               # 江西校区编号原始资料，仅开发者使用，不随 APK 打包
```

## 功能实现说明

### 地图与车辆查询

- 地图使用 osmdroid，地图瓦片接口使用 GCJ-02 坐标。
- Android 定位回调返回 WGS-84 坐标，展示和车辆查询前通过 `CoordinateUtils.wgs84ToGcj02()` 转换。
- 前台地图查询以地图视野中心为查询中心；后台车辆预警以前台服务获取的当前位置为查询中心。
- `BikeRepository` 负责 HTTPS 请求、JSON 解析、车辆状态判断和距离计算。

### 二维码与相册

- `QrGalleryStore` 负责二维码写入系统 MediaStore，并保存文件名、大小、SHA-256 和清理时间。
- 二维码默认 30 秒后清理；应用启动时继续处理未完成的清理记录。
- 删除前必须校验 URI、MediaStore 记录 ID、文件名、大小和安全路径/指纹，避免误删非本应用图片。
- 最近生成记录默认保留最近 5 个完整车辆编号，可通过设置关闭并清除。
- 二维码链接包含完整车辆编号，复制链接、导出图片或分享二维码时该编号会随内容暴露。

### 提醒功能

- 提醒固定使用 15 分钟计时，支持提前 1/3/5/7/10 分钟提示，以及响铃并震动、仅震动、静默通知三种模式。
- `ReminderScheduler` 负责精确闹钟、通知频道、提醒状态持久化和到时投递。
- `ReminderReceiver` 负责精确闹钟触发和短时 CPU 唤醒锁。
- `ReminderForegroundService` 在提醒计时期间提供倒计时前台通知，并持有有时限的部分唤醒锁；到时提醒时由通知和前台服务共同完成投递。
- 提醒权限流程按顺序检查通知运行时权限、精确闹钟、通知总开关/类别、电池优化和厂商后台活动引导；从系统设置返回后会重新验证状态。
- 首次通知类别引导使用一次性标记；只有实际检测到通知关闭、频道弱化或权限缺失时才继续提示，避免重复跳转。
- 权限被撤销时保留活动提醒状态，不静默清除；用户重新进入提醒功能后继续走权限恢复流程。

### 车辆预警

- 车辆预警的功能开关位于高级设置，检测计时位于提醒页，两者相互独立。
- 计时支持 10 秒、30 秒、1 分钟、3 分钟、5 分钟；功能开关和计时都开启后才满足后台运行条件。
- 前台服务按当前位置获取附近车辆，车辆数量不超过 5 辆，或最近可用车辆距离超过 150 米且超过定位误差范围时发送预警。
- 正常检查使用 Handler，AlarmManager 作为进程被回收或深度休眠时的唤醒兜底；系统省电策略仍可能使实际间隔略有偏差。
- 通知权限、通知总开关或车辆预警频道异常时，服务停止并引导用户检查通知设置。

### 权限与隐私边界

- 定位仅用于地图定位、附近车辆查询和用户主动开启的车辆预警，不保存历史轨迹。
- 车辆查询和车辆预警会把地图中心或当前位置发送至第三方数据接口，坐标不应写入日志、截图或仓库。
- 不读取通讯录、个人身份信息或相机画面。
- `keystore.properties`、签名文件、`local.properties`、构建缓存和本机凭据不得提交。

## 车辆编号数据

江西校区编号数据分为公开统计摘要、受控完整明细和应用运行时汇总资产三层。

### 公开统计摘要

以下统计摘要可作为开发者文档内容，放在 `docs/developer-data/vehicle-number/`，不会打入 APK：

- `江西快趣车辆校园编号范围.csv`：20 条校园号段范围记录，字段为 `序号、接口站点ID、接口站点名称、学校/校区名称、编号号段、最小编号、最大编号、本段发现数量、该校扫描发现总数、范围说明`。
- `江西快趣车辆校园编号范围.md`：扫描口径、统计结果和扫描限制说明。

### 受控完整明细（不公开）

- `江西快趣车辆明细-精确编号.csv`：1,797 个唯一车辆编号，仅保留在本机用于数据维护，已由 `.gitignore` 排除，不提交、不公开、不随 APK 打包。

### 应用运行时汇总资产

`app/src/main/assets/vehicle_number_catalog.json` 是由受控完整明细和统计摘要生成的运行时数据，随应用打包。当前覆盖的校区包括：

- 南昌航空大学
- 南昌交通学院
- 东华理工大学
- 江西师范大学
- 江西水利电力大学
- 南昌应用技术师范学院
- 江西科技师范大学（红角洲校区）

数据文件结构：

```text
{
  "schema": 1,
  "sites": [
    {
      "id": 1,
      "name": "校区名称",
      "series": ["008000", "008001"]
    }
  ],
  "numbers": {
    "完整车辆编号": [站点 id, "号段"]
  }
}
```

隐私判断：公开统计摘要不含姓名、手机号、邮箱、账号、密码、Token、坐标或设备标识；完整车辆编号属于车辆/运营数据标识，完整明细不进入公开仓库。应用为编号补全功能仍会把完整映射打包进 `app/src/main/assets/vehicle_number_catalog.json` 并随 APK 分发；若连 APK 也不能包含完整编号，需要另做运行时方案调整。

维护要求：

- 修改编号数据时同步更新 `VehicleNumberCatalog` 的读取或校验逻辑（如有变化）。
- 保持 `sites`、`numbers` 中的站点 ID 和号段引用一致。
- 不要把用户输入的车辆编号、历史记录或测试产生的编号写入示例数据。
- 数据文件属于应用资产，不按普通构建缓存清理。

## 文档边界

- `README.md`：面向用户的功能与使用说明。
- `PRIVACY.md`：面向用户的数据、网络与权限说明。
- `THIRD_PARTY_NOTICES.md`：第三方服务、开源组件和许可证声明。
- `DEVELOPER.md`：面向维护者的架构、数据格式、构建、测试和发布说明。
- 用户文档不写签名、构建号内部流程、R8 配置或依赖细节；开发文档不重复用户免责声明和过多产品说明。

## 发布合规

发布 APK 等目标代码时，应在同一发布页面提供对应版本的完整源码或可访问的源码下载地址。

## 本地敏感文件

不要提交 `keystore.properties`、签名文件、`local.properties`、构建缓存或其他包含本机路径与凭据的文件。
