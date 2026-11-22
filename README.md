# Otoha Mobile 🎧

在 Android 上搜索、收藏和播放来自 ASMR.one 与 ASMR18 的音声作品。

支持 **Android 8.0 及以上**。在发布页的 **Assets** 中下载 `Otoha-Mobile-vX.Y.Z.apk` 即可安装。[下载 APK](https://github.com/SiIverAsh/Otoha_mobile/releases)

## 🎵 功能描述

| 功能 | 用法 |
| --- | --- |
| 搜索与发现 | 「全局搜索」用同一个关键词查询两个音源，结果按音源独立翻页。在「发现」可以按标签、声优或社团筛选，也可以直接粘贴 ASMR18 作品链接。 |
| 版本与队列 | 一部作品按目录提供多个版本时，选中的版本会独立排队，顺播时不会串到其他版本。点底部播放栏的曲名回到作品详情，点右侧上箭头展开播放器。 |
| 字幕与章节 | ASMR.one 音轨会自动匹配同目录下的同名字幕，拖动进度后就能看到对应台词。带章节的 ASMR18 音轨可以在「章节」里直接跳转。 |
| 后台与睡眠播放 | 支持 HLS 流式播放，切到其他应用也能继续听。播放器提供 0.75× 至 2× 倍速，睡眠定时可选 15、30 或 60 分钟。 |
| 收藏与续听 | 在作品详情点「收藏」，以后可以从「收藏」页找到它。「最近 → 接着听」会恢复上次的队列、位置和倍速。播放中途断网时，播放器会等待重新联网后尝试续播。 |

默认只显示明确标为全年龄的作品。「设置 → 显示受限和未分级作品」同时控制发现、搜索、收藏和续播入口。设置中也可以切换浅色、深色或跟随系统的主题。

ASMR.one 音轨只有编号时，打开详情会尝试从 DLsite 补全曲名。
每组音轨的编号和时长都匹配时才会显示官方曲名。

打开「无痕聆听」会停止记录收听历史和进度，退出后重新选曲才恢复记录。播放仍需联网，音源和 CDN 仍会收到请求。封面缓存可以在设置中单独清理。

## 🛠️ 如何开发

### 在本地运行

1. 克隆仓库，用 Android Studio 打开项目根目录。
2. 将 Gradle JDK 设为 **JDK 21**，在 SDK Manager 安装 **Android SDK Platform 36** 和 **Build Tools 35.0.0**。
3. 等待 Gradle 同步完成，选择 `app` 和一台 Android 8.0 及以上的设备或模拟器，点击 **Run**。

也可以从项目根目录使用 Gradle Wrapper 构建。命令行通过 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` 定位 Android SDK，首次构建需要联网下载依赖。

```sh
./gradlew :app:assembleDebug
```

生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 从哪里改起

- **音源与字幕**　两个站点的请求、作品解析和字幕格式处理都在纯 Kotlin 的 `core` 模块，对应的 JVM 测试也在这里。[core 源码](core/src/main/) · [core 测试](core/src/test/)
- **界面与播放**　Compose 页面、Media3 后台播放服务、弱网恢复和本地收听记录在 `app` 模块。[app 源码](app/src/main/) · [app 单元测试](app/src/test/)

### 运行测试

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python3 scripts/check-icu-regex.py
python3 -m unittest discover -s scripts -p 'test_*.py'
```

单元测试使用离线样例。正则兼容性检查使用系统 ICU，macOS 自带，Debian 或 Ubuntu 需安装 `libicu-dev`。要在设备上运行 Android 测试，启动模拟器或连接已授权的测试机，再执行 `./gradlew :app:connectedDebugAndroidTest`。[CI 工作流](.github/workflows/android.yml) · [音源验证记录](docs/source-validation.md) · [签名与发布说明](docs/releasing.md)

项目采用 **GPL-3.0-or-later**。部分音源和字幕解析规则及测试样例移植自 Otoha。[Otoha 原项目](https://github.com/SiIverAsh/Otoha) · [许可证](LICENSE) · [完整条款](COPYING) · [第三方来源说明](THIRD_PARTY_NOTICES.md)
