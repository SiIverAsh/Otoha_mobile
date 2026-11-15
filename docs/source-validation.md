# 音源验证记录

记录日期：2026-11-15（Asia/Shanghai）。离线解析、网络可达性和 Android 播放验证分别记录，不能相互替代。

## ASMR.one

### 离线解析与网络策略

ASMR.one 9 项、SourceHttp 8 项测试均通过，覆盖字段缺失、分级、目录与版本身份、字幕同目录配对、CDN URL 更新、镜像失败与取消、429/Retry-After、挑战、响应大小上限和编码。目录每页按需请求，进入详情才请求曲目。

### 在线请求

先用 curl 独立检查公开接口，再用真实 Kotlin 适配器运行：

```sh
./gradlew :core:run --args='one 243448'
```

本机 Wrapper/依赖缓存放在 `.local-tools/gradle-home`，构建时附加了本机临时 Maven 镜像 init script；该脚本只影响依赖下载，不影响音源请求。

| 验证项 | 实际结果 |
| --- | --- |
| 首个镜像 `api.asmr-200.com` 的 curl 请求 | 初次出现 TLS 连接失败；不将该次错误解释为镜像永久不可用 |
| 备用 `api.asmr.one/api/works` | HTTP 成功；JSON 分页元数据正常；Kotlin probe 读取到 30 条作品、page 1、hasMore=true |
| `workInfo/1714306` 与 `tracks/1714306` | 元数据成功；曲目仅一张 image 附件，没有可播放音轨，不计媒体验证通过 |
| `workInfo/243448` 与 `tracks/243448` | Kotlin 适配器成功取得详情与 6 条音轨 |
| 第一条 MP3 小范围请求 | `Referer: https://asmr.one/`、相应 Origin；HTTP 206、`Content-Type: audio/mpeg`、1024 字节，`Content-Range: bytes 0-1023/7622278` |
| Kotlin probe 结果 | `result status=PASS scope=bounded_network_chain_only`，Gradle 正常退出 |
| 全站关键词搜索 | 未验证，未提供对应入口 |
| Android 真机后台/锁屏播放 | 未验证；网络字节可达不等于播放器解码及系统集成通过 |

记录中不保存签名媒体 URL 或完整音频。请求链路验证只读取少量媒体字节。

### 0.1.2 空列表反馈复核

用户确认 Xiaomi 15 Pro / Android 16 上 0.1.2 已正常启动，ASMR18 可获取目录，ASMR.one 显示“本页没有可显示的作品，继续加载”。重新运行真实 Kotlin probe，ASMR.one 目录返回 30 条，参考作品详情仍有 6 条曲目，音频 Range 206/1024 字节通过。独立核对同一目录请求的 30 条作品，全部为受限分级（RESTRICTED），全年龄条数为 0。

当前默认仅显示 GENERAL，因此这些已获取的作品会被显示设置隐藏。用户可在“设置 → 显示受限和未分级作品”中主动调整显示范围后返回发现；保留默认设置时可继续翻页。该反馈不应标记为网络请求失败。后续界面可补充已加载/已隐藏数量，减少混淆。

## ASMR18

### 离线解析

- 参考来源：原 Go 项目 `internal/asmr18/client.go` 和 `internal/asmr18/testdata/work.html`，按 GPL-3.0 移植。
- 使用真实固定作品页样例验证静态播放器字符串解包、标题、社团、声优、标签、HLS URL 与五个章节映射。不执行页面 JavaScript。
- 六项单元测试覆盖真实打包脚本、多文件独立章节和队列、CDN 变化后的稳定曲目标识、作品 URL 和媒体域名约束、分类页去重及分页、访问挑战与结构变化错误。测试实现位于 `core/src/test/kotlin/io/github/otoha/core/Asmr18ParserTest.kt`。
- `allages` 映射全年龄；`boys`、`girls` 映射受限内容。无法识别的分类结构报错，具有明确无结果标记的页面才返回空列表。

### 在线请求

请求入口：`https://asmr18.fans/allages/`，携带 `User-Agent: Mozilla/5.0 (compatible; OtohaMobile/0.1)`、`Referer: https://asmr18.fans/` 和 `Origin: https://asmr18.fans`。初次沙箱请求 DNS 失败，随后升级调用被中止；这些初次结果不代表站点不可用。本次续验已通过真实 Kotlin 适配器完成以下请求：

```sh
./gradlew :core:run --args=eighteen
```

| 验证项 | 实际结果 |
| --- | --- |
| Kotlin 适配器分类页 | 24 条作品、page 1、hasMore=true |
| 第二页分页补充检查 | curl 获取第二页，含 24 个去重后的 `/allages/rj…/` 作品链接，并有指向第三页的下一页入口 |
| 真实作品详情 `allages/rj01752865` | Kotlin 适配器取得 1 条 HLS 音轨；页面文件槽位为 `file1_content` |
| 真实章节数据 | 页面起点为 0、1825、3744、5750、9069 秒，与固定样例一致 |
| HLS 入口媒体清单 | HTTP 200、58033 字节；含 1213 个 `EXTINF`，累计 12127.082364 秒，并有 `ENDLIST`，属于 VOD 媒体清单 |
| 子清单、密钥和初始化段 | 该样本没有 `EXT-X-STREAM-INF`、`EXT-X-MEDIA`、`EXT-X-KEY`、`EXT-X-SESSION-KEY` 或 `EXT-X-MAP`；没有独立子清单、密钥或初始化段可请求，不计作这些分支验证通过 |
| 首个 TS 分片小范围请求 | HTTP 206、1024 字节，Range 被支持；未下载完整音频 |
| 第二章附近分片 | 目标 1825 秒，清单定位到 1820.010621–1830.015954 秒的分片；HTTP 206、1024 字节、`Content-Type: video/mp2t`，每 188 字节的 MPEG-TS 同步字 `0x47` 校验通过 |
| 中段分片 | 目标 6063.541182 秒，定位到 6060.010515–6070.015848 秒的分片；HTTP 206、1024 字节、`video/mp2t`，MPEG-TS 同步字校验通过 |
| 第五章附近分片 | 目标 9069 秒，定位到 9060.010440–9070.015773 秒的分片；HTTP 206、1024 字节、`video/mp2t`，MPEG-TS 同步字校验通过 |
| 尾段分片 | 目标 12126.082364 秒，定位到 12119.999697–12127.082364 秒的分片；HTTP 206、1024 字节、`video/mp2t`，MPEG-TS 同步字校验通过 |
| Kotlin probe 结果 | `result status=PASS scope=bounded_network_chain_only` |
| Android 真机播放、拖动与章节跳转 | 未验证 |

当前证据确认一个全年龄作品的分类→详情→未加密 TS 媒体清单→首段、章节附近、中段和尾段的有限网络链路可达，抽样分片具有 MPEG-TS 同步字。它不证明所有作品、文件版本、CDN、嵌套主从清单、加密/换钥、字节范围 HLS 或 Android 解码与后台播放均可用。上述章节定位基于清单时长累加，不是播放器实测 seek；抽样字节通过也不等于完整分片解码或连续播放已经通过。真机播放、拖动与章节跳转验收仍未完成。
