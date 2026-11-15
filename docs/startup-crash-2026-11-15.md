# 2026-11-15 启动异常：字幕正则与 Android ICU

## 症状与证据

用户设备为 Xiaomi 15 Pro，Android 16。安装后点击应用图标，主页面刚显示即退出。0.1.1 增加本地未捕获异常记录后，用户提供的真机堆栈将原因明确指向 `SubtitleParser` 的静态初始化，而后续播放器刷新出现 `NoClassDefFoundError`。

0.1.2（versionCode 3）已修正该触发点，**用户已确认同一设备能够正常打开，并能获取 ASMR18 目录**。这属于启动修复反馈，播放、锁屏和耳机等验收仍未完成。

## 确切根因

`core/src/main/kotlin/io/github/otoha/core/SubtitleParser.kt` 为去除 ASS 字幕的覆盖标签而创建正则。旧表达式末尾的右花括号没有转义：

```kotlin
// 旧表达式：桌面 JVM 接受，本次 Android ICU 拒绝编译。
Regex("\\{[^}]*}")

// 0.1.2：显式匹配作为文本的右花括号。
Regex("\\{[^}]*\\}")
```

这个正则在 Kotlin `object SubtitleParser` 初始化时编译。播放器连接后的刷新逻辑会调用 `SubtitleParser.activeCues`，即使当前尚未播放音频、字幕列表为空，也会触发对象初始化。因此用户不必先打开 ASS 文件，就可能在主页面启动后遇到该错误。

本次异常链为：正则编译失败 → `SubtitleParser` 的 `<clinit>` 失败并产生 `ExceptionInInitializerError` → 后续播放器刷新再次访问该类时出现 `NoClassDefFoundError`。后一个错误表示类先前初始化失败，并不表示 APK 漏打包了 `SubtitleParser`。这次定位依据是真机异常链及对应正则，不是把网络失败或媒体权限当作原因。

## 为什么此前测试没有发现

0.1.1 的 50 项 JVM/Robolectric 测试通过，其中包括 Android 16 的播放服务初始化、主界面与服务绑定测试。但是这些测试在宿主 JVM 上执行，本次没有覆盖设备 ICU 对正则的解析行为；Android API 等级相同不等于正则引擎行为相同。

修正时另用宿主系统 ICU 对照编译两条表达式：

| 表达式 | 编译结果 | ICU status |
| --- | --- | --- |
| 末尾裸右花括号的旧表达式 | 失败 | 66305 |
| 末尾右花括号显式转义 | 成功 | 0 |

该对照复现了引擎兼容性差异，支持这次修正；它不是 APK 在 Android 上运行的结果。0.1.2 重测结果：50 项 JVM/Robolectric 测试通过、Lint 无错误、Debug APK 与 Android 回归测试 APK 均编译通过。`scripts/check-icu-regex.py` 直接检查当前 core 源码的 14 条正则，全部通过 ICU 编译，旧表达式负对照如期失败；该检查已加入 CI。3 项 Android/ART 回归测试尚未在设备执行。

0.1.2 Debug APK 已通过签名校验，SHA-256 为 `63b6215e00a16ca1a1e5ad311d2642a11be132efef5a5f769cb49beb724a299e`；已检查最终 APK DEX，旧错误正则出现 0 次，修正正则出现 1 次。

Android 16 实际模拟器已成功启动；后续 ADB 控制请求未获授权后停止，没有在该模拟器内完成 APK 安装或启动验收。

## 本地诊断与复测流程

最近一份未捕获 Java 异常报告存于应用私有路径：

```text
Context.noBackupFilesDir/last-crash-report.txt
```

默认 Android 用户下通常对应 `/data/user/0/io.github.otoha.mobile/no_backup/last-crash-report.txt`，实际目录由系统的 `Context` 决定。报告包括设备厂商/型号、Android 版本/SDK、PackageManager 应用版本、时间、线程和异常链；最多 64KiB，超长会标记截断。报告不会自动上传。

用户不需要直接读取私有目录：重新打开应用后，若存在报告，先显示“上次运行出现异常”，可以点击“复制完整报告”。“清除并重试”只清除该异常报告并尝试进入正常界面；它不意味着问题已经修复。正常设置页也可“复制诊断信息”。诊断页不会提前创建媒体控制器或请求音源。

安装 0.1.2 后，先区分旧报告与新异常：如果仍显示 0.1.1 报告，可先复制保存，再清除并重试。需要确认主界面能持续显示、能进入作品并开始播放；若仍退出，再次打开并提供含 0.1.2 版本号的新报告。之后还需完成锁屏、切轨、字幕、HLS seek、耳机与音频焦点等独立验收。
