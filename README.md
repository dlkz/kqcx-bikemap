# 单车地图

一个用于查看地图中心附近共享车辆、扫码与按编号生成二维码的 Android 工具。

> **非官方工具**，与任何车辆品牌、运营平台及第三方服务均无隶属、合作或授权关系。本项目仅供学习交流与个人技术研究，详见[免责声明](#免责声明)。

## 功能

**地图与车辆**

- osmdroid 地图显示，支持缩放、拖动和定位到当前位置。
- 以地图视野中心查询附近车辆，移动地图后刷新当前区域的车辆。
- 圆形车辆标记，点击后可查看编号、位置、电量、距离与状态。
- 车辆列表仍可查看当前查询范围内的车辆，可定位视野外的车辆。

**扫码与二维码**

- 输入车辆编号后三位，自动补全完整编号。
- 生成对应车辆编号的二维码，可保存到系统相册。
- 二维码保存 30 秒后自动删除；应用在下次启动时继续清理未完成的删除任务。
- 可直接跳转微信扫一扫，扫描已保存的二维码。
- 高级设置中可控制是否显示二维码链接、是否启用复制链接。

**界面与设置**

- 支持浅色、深色和跟随系统主题。

## 使用

1. 首次启动时阅读免责声明，选择“同意并继续”后进入地图。
2. 授予定位权限后，应用会显示当前位置并查询地图中心附近的车辆。
3. 点击地图上的车辆标记查看详情，或在车辆列表中选择车辆。
4. 在详情页生成二维码，点击二维码可保存到系统相册；也可直接跳转微信扫一扫。
5. 输入车辆编号后三位即可生成对应二维码，无需车辆出现在当前查询范围内。

地图查询的车辆数据来自第三方接口。若当前区域没有可用车辆，地图顶部会显示提示；车辆列表仍会保留已查询到的数据。

## 开发

### 环境要求

| 项 | 要求 |
|----|------|
| JDK | 17+ |
| Android SDK | Platform 36，Build-Tools 36.x |
| Gradle | Wrapper 8.x（已随仓库配置） |
| minSdk / targetSdk | 26 / 36 |
| applicationId | `com.kqcx.bikemap` |

### 构建与安装

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

构建时会把 `LICENSE`、第三方声明和 Apache-2.0 全文放入 APK 的 `assets/legal/`，随安装包一并分发。

### 工程结构

```text
app/src/main/java/com/kqcx/bikemap/
  MainActivity.java          # 主页面、地图、车辆查询、详情与设置
  AboutActivity.java         # 版本信息与更新日志
  AppMapView.java            # 地图手势与交互
  BikeRepository.java        # 车辆数据请求与状态管理
  MapLabelOverlay.java       # 地图地名标签绘制
  Bike.java / BikeCallout.java
  CoordinateUtils.java
app/src/main/res/
  layout/                    # 首页、地图标注、详情弹窗与设置页面
  values/                    # 字符串、颜色、主题
  drawable/                  # 图标与背景资源
```

## 数据与权限

- `INTERNET`：加载地图瓦片并查询车辆数据。
- `ACCESS_NETWORK_STATE`：判断网络连接状态。
- `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`：显示当前位置并查询地图中心附近的车辆。
- `WRITE_EXTERNAL_STORAGE`：仅 Android 9 及以下保存二维码时使用。
- `CAMERA`：应用自身不使用相机权限；微信扫一扫由微信客户端处理。

车辆查询会将地图中心坐标发送至第三方服务；地图瓦片请求发送至高德开放平台。定位信息不在本机留存历史轨迹，也不在应用自有服务器存储。详细说明见 [PRIVACY.md](PRIVACY.md)。

## 参考

- Kuaiqu_RemoteScan：https://github.com/Wuwang777/Kuaiqu_RemoteScan
- JUWP-Schedule / 快趣出行：https://github.com/Inonvation/JUWP-Schedule
- osmdroid：https://github.com/osmdroid/osmdroid
- ZXing Android Embedded：https://github.com/journeyapps/zxing-android-embedded

## 免责声明

本项目为非官方工具，与任何车辆品牌、车辆运营平台、地图服务商、微信及相关项目均无隶属、合作、授权或背书关系。

- 车辆数据来自第三方接口，可能存在延迟、偏差或数据不准确的情况，实际车辆状态请以运营平台为准。
- 使用者应遵守当地法律法规、地图服务条款、车辆运营平台规则和微信相关规则。
- 本项目仅供学习交流与个人技术研究，请勿用于非法用途或侵犯他人权益的行为。
- 因使用或无法使用本软件产生的风险与后果，由使用者自行承担。

## License

Copyright (C) 2026 dlkz

除第三方组件外，本项目原创代码按 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）发布。第三方组件继续适用其各自许可证，完整声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，Apache-2.0 全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。

发布 APK 等目标代码时，应在同一发布页面提供对应版本的完整源码或可访问的源码下载地址。
