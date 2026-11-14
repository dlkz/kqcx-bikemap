# 第三方组件与服务声明

本文件区分应用运行时调用的外部服务、随应用分发的第三方运行组件，以及仅用于实现参考的项目。各项目、服务、商标和内容归其各自权利人所有。

## 外部服务与专有软件

- 地图瓦片：高德开放平台，https://lbs.amap.com/
- 快趣出行：https://www.kvcoogo.com/

高德开放平台及上述外部服务不属于本项目的开源组件。本应用为非官方工具，与上述服务及参考项目均无合作、授权、隶属或背书关系。第三方服务的可用性、数据、隐私规则和使用条款由各自提供方负责。

## 使用的开源组件

### osmdroid 6.1.18

- 作者：osmdroid contributors
- 许可证：Apache License 2.0
- 项目链接：https://github.com/osmdroid/osmdroid

### ZXing Core (Java) 3.4.1

- 作者：ZXing Authors
- 许可证：Apache License 2.0
- 项目链接：https://github.com/zxing/zxing

### AndroidX Activity 1.10.1 / Core 1.13.1 / Core KTX 1.13.1 / Lifecycle 2.6.2

- 作者：Android Open Source Project
- 许可证：Apache License 2.0
- 项目链接：https://developer.android.com/jetpack/androidx

### AndroidX 传递组件

- 作者：Android Open Source Project
- 许可证：Apache License 2.0
- 项目链接：https://developer.android.com/jetpack/androidx
- 版本：Annotation / annotation-jvm 1.8.1、Collection 1.0.0、Concurrent Futures 1.1.0、Core ViewTree 1.0.0、Profile Installer 1.4.0、SavedState 1.2.1、Startup Runtime 1.1.1、Tracing 1.0.0、Arch Core 2.2.0、Interpolator 1.0.0、VersionedParcelable 1.1.1 等。

### Kotlin Standard Library 1.8.22

- 作者：JetBrains
- 许可证：Apache License 2.0
- 项目链接：https://github.com/JetBrains/kotlin

### Kotlin Coroutines 1.7.3

- 作者：JetBrains
- 许可证：Apache License 2.0
- 项目链接：https://github.com/Kotlin/kotlinx.coroutines

### JetBrains Annotations 23.0.0

- 作者：JetBrains
- 许可证：Apache License 2.0
- 项目链接：https://github.com/JetBrains/java-annotations

### Guava ListenableFuture 1.0

- 作者：Google
- 许可证：Apache License 2.0
- 项目链接：https://github.com/google/guava

## 仅测试期组件

以下组件只用于本地 JVM 测试或设备仪器测试，不进入 Release APK，也不参与应用运行时行为。

### JUnit 4.13.2

- 作者：JUnit contributors
- 许可证：Eclipse Public License 1.0
- 项目链接：https://github.com/junit-team/junit4

### AndroidX Test Core 1.6.1 / Runner 1.6.2 / Ext JUnit 1.2.1

- 作者：Android Open Source Project
- 许可证：Apache License 2.0
- 项目链接：https://developer.android.com/training/testing

### Android JSON compatibility implementation

- 坐标：`com.vaadin.external.google:android-json:0.0.20131108.vaadin1`
- 许可证：Apache License 2.0
- 项目链接：https://repo1.maven.org/maven2/com/vaadin/external/google/android-json/

## 参考项目

以下项目未作为运行时组件随应用分发，但本项目参考了其公开实现或用法。

### Kuaiqu_RemoteScan

- 作者：Jiayi Li（Wuwang777）
- 许可证：MIT License
- 项目链接：https://github.com/Wuwang777/Kuaiqu_RemoteScan

Copyright (c) 2026 Jiayi Li

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### JUWPHub

- 作者：Inonvation
- 许可证：GNU General Public License v3.0
- 项目链接：https://github.com/Inonvation/JUWPHub

## 许可证文本

- AGPL-3.0：见仓库根目录 [LICENSE](LICENSE)。
- Apache License 2.0：全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。
- MIT License：Kuaiqu_RemoteScan 的完整许可文本已列在本文件中。
- GNU GPL v3.0：见 https://github.com/Inonvation/JUWPHub/blob/main/LICENSE。
