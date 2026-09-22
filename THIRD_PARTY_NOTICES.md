# 第三方组件与服务声明

本文件区分应用运行时调用的外部服务、实际编译进 APK 的运行时组件，以及仅用于实现参考的项目。各项目、服务、商标和内容归其各自权利人所有。

## 外部服务与专有软件

- 地图瓦片：高德开放平台，https://lbs.amap.com/
- 车辆数据接口：快趣出行，https://www.kvcoogo.com/

高德开放平台和快趣出行不属于本项目的开源组件。本应用为非官方工具，与上述服务及参考项目均无合作、授权、隶属或背书关系。第三方服务的可用性、数据、隐私规则和使用条款由各自提供方负责。

## 使用的开源组件

### osmdroid 6.1.18

- 作者：osmdroid contributors
- 许可证：Apache License 2.0
- 项目链接：https://github.com/osmdroid/osmdroid

### ZXing Android Embedded 4.3.0

- 作者：JourneyApps 与 ZXing Authors
- 许可证：Apache License 2.0
- 项目链接：https://github.com/journeyapps/zxing-android-embedded

### ZXing Core 3.4.1

- 作者：ZXing Authors
- 许可证：Apache License 2.0
- 项目链接：https://github.com/zxing/zxing

### AndroidX Core 1.13.1 / Activity 1.10.1

- 作者：Android Open Source Project
- 许可证：Apache License 2.0
- 项目链接：https://developer.android.com/jetpack/androidx

Gradle 运行时依赖树还包含 AndroidX、Kotlin 与 Kotlin Coroutines 等传递依赖；其发布元数据声明使用 Apache License 2.0。

## 参考项目

以下项目未作为运行时代码打进 APK，但本项目参考了其公开实现或接口用法。

### Kuaiqu_RemoteScan

- 作者：Jiayi Li（Wuwang777）
- 许可证：MIT License
- 项目链接：https://github.com/Wuwang777/Kuaiqu_RemoteScan

Copyright (c) 2026 Jiayi Li

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### JUWP-Schedule / 快趣出行

- 作者：Inonvation
- 许可证：MIT License
- 项目链接：https://github.com/Inonvation/JUWP-Schedule

Copyright (c) 2026 Inonvation

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## 许可证文本

- AGPL-3.0：见仓库根目录 [LICENSE](LICENSE)。
- Apache License 2.0：全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。
- MIT License：上述两个参考项目的完整许可文本已列在本文件中。

构建 APK 时，上述许可证与声明会一并放入安装包的 `assets/legal/` 目录，随安装包分发。
