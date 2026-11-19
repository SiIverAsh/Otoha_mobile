# Otoha Mobile

直接连接 ASMR.one 和 [ASMR18](https://asmr18.fans) 的原生 Android 前端，无需电脑、NAS 或 Go 服务。Kotlin、Jetpack Compose、Media3；Android 8.0（API 26）及以上。

最新版本及安装包见 [GitHub Releases](https://github.com/SiIverAsh/Otoha_mobile/releases)。在 GitHub Actions 手动选择补丁（patch）、小版本（minor）或大版本（major），即可自动递增版本、构建、签名并发布，无需填写 tag。开发阶段采用预发布版本；新版本覆盖安装沿用已有签名，保留手机个人数据。

## 使用

- **发现**：切换音源，用上一页和下一页浏览作品。支持按标签、声优或社团筛选，点击作品详情里的同类信息也能进入筛选结果。
- **全局搜索**：从顶栏输入关键词，同时搜索 ASMR.one 和 ASMR18，按音源切换结果并独立翻页。
- **翻页缓存**：发现和搜索在内存中保留最近访问的页面，返回仍在缓存中的上一页时直接显示。手动刷新会更新当前目录并清除该目录的旧页面。
- **筛选**：ASMR.one 每类单选、三类可以组合，结果包含关联翻译版本；ASMR18 每次一个站点分类，选新条件会替换旧条件。筛选窗口只检索选项名称。
- **音源连接**：ASMR.one 的默认镜像优先使用本次运行中最近成功的镜像。包含镜像回退的单次操作最多等待 20 秒，作品详情和曲目共用这段时间。
- **分级**：默认仅显示明确标注全年龄的作品，未知分级也隐藏。需要时在“设置 → 显示受限和未分级作品”主动调整。列表会显示已加载与隐藏数量；此设置也作用于收藏、最近、详情和续播。
- **播放**：作品目录、文件版本各自独立队列，支持 HLS、拖动与后台媒体会话。点击底部迷你播放器打开播放页；播放键下方的倍速（如“1×”“2×”）与“定时”菜单可调整速度和 15/30/60 分钟睡眠定时。章节和播放队列默认折叠，点击标题展开。
- **字幕**：音轨与同目录字幕文件自动配对。点底部迷你播放器展开，字幕卡片在标题下、进度条上，随播放/seek 更新当前句，重叠字幕分行显示；加载、失败和空时间轴均有提示。
- **外观**：设置中选择跟随系统、浅色或深色，立即生效并保留选择，系统栏和异常诊断页同步适配。
- **个人数据**：本机收藏、最近作品、最后一次队列/位置/倍速。恢复前刷新音源地址，并按稳定曲目标识与目录匹配，无法匹配时要求重新选择。
- **无痕**：不新增历史与进度，退出后当前私密队列仍不记录，重新选曲才开始普通会话。封面缓存单独管理，无痕不隐藏向音源/CDN 的网络请求。

没有离线音频下载、本地目录扫描或账号同步。应用私有 JSON 存储个人数据，关闭系统云备份与设备迁移。封面缓存可在设置中清理。

## 构建与测试

需要 JDK 21、Android SDK Platform 36、Build Tools 35.0.0。使用 Android Studio 指定 SDK，或设置 ANDROID_HOME；local.properties 不提交。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check-icu-regex.py
```

ICU 检查需要系统 ICU（macOS 自带，Linux 可安装 libicu-dev）。首次构建需下载 Gradle、Maven 与 Robolectric SDK 依赖，离线前需要预热缓存。JVM/Robolectric 测试与音源联网验证分开；CI 不请求真实音源。

当前本地 **83 项 JVM/Robolectric 测试通过，18 条源码正则通过 ICU 检查，Debug/Release 构建与 Lint 通过**。JVM 测试不能代替 Android/ART；发布工作流在 Android 16 模拟器运行解析回归测试后才发布。工作流实际结果以 [Actions](https://github.com/SiIverAsh/Otoha_mobile/actions) 为准。

```sh
# 仅在已连接并授权的测试设备上运行
./gradlew :app:connectedDebugAndroidTest
# 主动请求公开接口及少量媒体字节，不加入离线测试/CI
./gradlew :core:run --args='one 243448'
./gradlew :core:run --args=eighteen
./gradlew :core:run --args=filters
./gradlew :core:run --args=subtitles
```

Debug APK：app/build/outputs/apk/debug/app-debug.apk。签名 Release 构建通过 OTOHA_KEYSTORE_PATH、OTOHA_KEYSTORE_PASSWORD、OTOHA_KEY_ALIAS、OTOHA_KEY_PASSWORD 注入；密钥不进入源码、日志或发布附件。Actions 配置见 [发布说明](docs/releasing.md)。

固定基线：AGP 8.13.2、Gradle 8.13、Kotlin 2.2.10、Compose BOM 2025.08.01、Media3 1.8.0，compile/target SDK 36。版本固定用于可复现构建，不表示各组件均为最新版。官方参考：[AGP 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Compose 编译插件](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)、[MediaSessionService](https://developer.android.com/media/media3/session/background-playback)。

## 验证与排错

双源公开请求、远端筛选及真实 VTT 配对已取得代表样本证据，见 [0.2.0 功能验证](docs/features-v0.2.0.md)、[音源记录](docs/source-validation.md) 与 [开发状态](docs/development-status.md)。用户已确认 0.1.2 在 Xiaomi 15 Pro / Android 16 启动恢复；0.2.0 的主题、筛选和播放仍需要用户设备反馈，熄屏、蓝牙、来电、连续播放、字幕声画同步和升级数据保留的完整设备验收尚未完成。

遇到未捕获 Java 异常，再次打开会先显示本地报告，可复制或“清除并重试”；设置页也可复制诊断信息。报告在 noBackupFilesDir/last-crash-report.txt，最多 64KiB，不自动上传。没有报告不能排除原生崩溃或系统终止进程。

曾修复字幕 ASS 正则在 Android ICU 上初始化失败的启动问题，见 [根因及回归记录](docs/startup-crash-2026-11-15.md)。

## 结构与许可

- core/：统一身份模型、音源适配、HTML/JSON 静态解析、字幕、JVM 测试与独立联网 probe。
- app/：Compose 界面、个人数据、Media3 服务、控制器和 Android 回归测试。
- docs/：功能、音源、排错及发布记录。
- android-development-handoff.md：早期产品范围与阶段验收依据。

源站播放器只进行特定格式静态解包，不执行远端 JavaScript。CDN URL 不作为收藏与曲目的永久身份；各目录/版本独立排队，ASMR18 章节为文件内 seek。

GPL-3.0-or-later。部分规则及测试样例移植自 [SiIverAsh/Otoha](https://github.com/SiIverAsh/Otoha)，参见 [LICENSE](LICENSE)、[完整条款](COPYING) 和 [来源说明](THIRD_PARTY_NOTICES.md)。
