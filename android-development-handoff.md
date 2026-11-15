# Otoha Mobile 安卓开发交接文档

更新日期：2026-11-15（Asia/Shanghai）。目标仓库：[SiIverAsh/Otoha_mobile](https://github.com/SiIverAsh/Otoha_mobile)，克隆地址为 `https://github.com/SiIverAsh/Otoha_mobile.git`。

本文供新的开发 session 接手使用。用户已经授权开始安卓开发，并要求使用独立仓库。本轮随后暂停了开发，当前交付物是这份交接文档；尚未实现 Android 工程，也没有生成 APK。

## 1. 已确定的产品方向

**Otoha Mobile 是直接连接 ASMR.one 和 asmr18.fans 的独立安卓前端，主要负责浏览作品和流式播放。安装后无需用户在电脑或 NAS 上部署 Otoha 服务。**

用户最终说明为：“音源是 asmrone 和 asmr18fans 的，安卓客户端只是一个前端。”此前讨论过的“手机本地音频建库”方向已被这条说明替代，不能继续沿用。

具体范围：

- 音源为 ASMR.one 和 `https://asmr18.fans`，客户端按需请求作品信息、音轨和播放地址。
- 安卓端保存自己的收藏、收听历史、播放进度、队列快照、设置及必要缓存。
- 在线流式播放是首版主线；手机本地目录扫描、文件库管理不在首版范围。
- 客户端直接访问两个音源是当前规划采用的架构。它不是现有 Otoha Web 页面的远程壳，也不要求手机内启动 Go HTTP 服务。
- 首版不要求登录两个源站的账号，不要求与源站收藏同步；这些能力如有需要，另行验证并扩展。

“只是一个前端”描述的是产品职责。网络适配、页面解析、缓存和原生播放服务仍由 APK 自身实现。

## 2. 仓库和当前状态

| 项目 | 位置与用途 |
| --- | --- |
| 安卓开发目标仓库 | `https://github.com/SiIverAsh/Otoha_mobile.git` |
| 原 Go 项目本地路径 | `/Users/silverash/Documents/code/github_open_source/Otoha` |
| 原 Go 项目远端 | `https://github.com/SiIverAsh/Otoha.git` |
| 本文路径 | `/Users/silverash/Documents/code/github_open_source/Otoha/docs/android-development-handoff.md` |

原 Go 项目仅作为现有实现与测试样例的参考。后续 Android 代码应在 `Otoha_mobile` 仓库开发。

交接时的实际状态：

- 新仓库已由用户提供，仓库元信息读取成功；本轮没有向新仓库推送内容。新 session 必须重新拉取并检查它的当前状态。
- 本轮曾尝试克隆到 `/private/tmp/Otoha_mobile`，网络请求未完成，在暂停时已中止。不要把该临时目录当作可用 checkout。
- 原 Go 项目有大量已有未提交修改，涉及元数据补全、ASMR18、HLS 和 Web 界面。不要覆盖、重置或顺带提交这些内容。
- ASMR18 当前代码包含未提交实现。需要按实际文件读取，不能只依赖远端 main 或本文记录的接口行号。
- 交接时本机可找到 JDK 21；未在标准位置找到 Android SDK、Android Studio 或 Gradle。新 session 应重新检查环境，准备可复现的 Gradle Wrapper 与构建流程。

## 3. 推荐技术架构

采用 Kotlin、Jetpack Compose 和 AndroidX Media3。UI 状态通过 ViewModel 管理，网络和解析逻辑通过音源适配器隔离，个人数据使用 Room 或等效的结构化本地存储，简单设置使用 DataStore。

```text
Compose 界面
    │
ViewModel / Repository
    ├── ASMR.one 适配器 ── JSON API
    ├── ASMR18 适配器 ──── 分类页、作品页及播放器数据解析
    ├── 本地收藏、历史、进度、缓存
    └── Media3 播放服务 ── 音频 URL / HLS
```

推荐初始目录：

```text
Otoha_mobile/
├── app/                 Android 界面、播放服务、本地数据与音源适配
├── core/                可选的纯 Kotlin 模型、解析器及 JVM 单元测试
├── gradle/wrapper/
├── docs/
├── .github/workflows/
├── README.md
└── LICENSE
```

先采用少量模块和清晰分包，业务稳定后再拆分。依赖版本、最低 Android 版本和 target SDK 在初始化工程时依据兼容性与官方资料确定，不把交接时的版本当作“永远最新”。

原 Go 实现适合提供业务规则、解析逻辑和测试样例。暂不把 Go 服务、Bleve 索引、SQLite 存储和 HTTP 路由整体嵌入 APK。

## 4. 可复用的现有代码

以下路径均相对于原 Go 项目 `/Users/silverash/Documents/code/github_open_source/Otoha`。

| 路径 | 可参考内容 |
| --- | --- |
| `internal/asmrone/client.go` | 分页、详情、曲目请求，镜像降级，字段映射，目录递归与字幕配对 |
| `internal/asmrone/dto.go` | 作品响应、分页、递归曲目节点、流地址与字幕数据结构 |
| `internal/asmrone/client_test.go` | 既有 JSON 解析和业务边界测试 |
| `internal/asmr18/client.go` | 分类翻页、作品页规范化、HTML 解析、播放器字符串静态解包、HLS 与章节提取 |
| `internal/asmr18/client_test.go`、`internal/asmr18/testdata/` | ASMR18 固定页面样例与解析测试 |
| `internal/stream/hls.go`、`internal/stream/hls_test.go` | HLS 资源关系、请求头、Range 与受限重定向处理 |
| `internal/subtitle/parse.go`、`internal/subtitle/parse_test.go` | LRC、SRT、VTT、ASS 文本与编码解析 |
| `internal/dlsite/workno.go`、对应测试 | RJ/BJ/VJ 作品号规范化，6 位与 8 位编号规则 |
| `internal/history/session.go` | 队列快照、进度与实听时长区分、历史与无痕语义 |
| `web/src/app.js` | 当前作品、目录、版本队列、字幕、章节与播放交互参考 |
| `docs/asmrone-sync.md`、`docs/asmr18-sync.md` | 当前源站接入说明与实现限制 |

原项目采用 GPL-3.0。若移植其代码或测试样例，应保留原许可及相应来源说明；新仓库默认延续该许可，第三方依赖单独保留必要声明。

## 5. 两个音源的接入要求

### ASMR.one

当前 Go 实现包含：

- `GET /api/works`：分页目录与排序，支持既有代码中的字幕筛选参数。
- `GET /api/workInfo/{workno}`：作品详情。
- `GET /api/tracks/{numericID}`：递归目录树、音频及附件。
- 默认候选镜像依次为 `api.asmr-200.com`、`api.asmr.one`、`api.asmr-100.com`、`api.asmr-300.com`。这是当前代码配置，实际可用性需验证。
- API 请求携带 User-Agent、`Referer: https://asmr.one/` 和相应 Origin。
- 音频存在 `mediaStreamUrl`、`mediaDownloadUrl` 和 `streamLowQualityUrl` 等字段；按实际可用字段选择播放地址。
- 同目录内按音频主文件名或音频完整文件名匹配字幕，保留目录与版本边界。

目录分页按需加载，进入作品后再取曲目；首版不应把全站目录同步到手机。

**搜索必须单独验证。** 现有 Go 音源客户端没有实现远端关键词搜索；Otoha 自己的 `/api/search` 搜索的是服务端本地 Bleve/SQLite 数据，不能直接搬来当作 ASMR.one 上游搜索。应验证源站搜索协议、分页和筛选能力后实现。

### asmr18.fans

当前 Go 实现包含：

- `allages`、`boys`、`girls` 分类页翻页与作品页发现。
- 规范化 `https://asmr18.fans/{category}/rj…/` 作品链接。
- 解析 HTML，静态解包特定播放器字符串，提取多文件 HLS 地址和章节起始秒数；不执行远端 JavaScript。
- 从页面读取标题、封面、社团、声优、标签等实际存在的字段。
- 可选探测 HLS 时长；列表加载和每次打开详情不必为获取时长而请求所有清单。
- 识别访问挑战、HTTP 错误和 `Retry-After`，与“作品没有内容”区分。
- 现有媒体请求使用 `Referer: https://asmr18.fans/` 和对应 Origin。

安卓播放器需要让主清单、子清单、分片和密钥请求采用正确的网络配置与请求头。原 Go 项目的 HLS 代理属于服务端实现参考，直连 APK 不必复刻 `/api/stream` 代理和清单 URL 改写。

ASMR18 当前只有分类页和作品页解析，没有已实现的站点搜索协议，也没有可保证存在的独立字幕。首版先提供分类浏览和粘贴作品链接入口；关键词搜索在验证后接入，界面应反映两个音源的能力差异。

站点结构、API 和 CDN 域名都可能变化。上述内容来自当前源码，不代表已经完成安卓真机或在线可用性验证。解析器应独立、可测试，失败时只影响相应音源。

## 6. 数据模型与播放规则

统一模型可以包含 `SourceId`、`WorkSummary`、`WorkDetail`、`TrackNode`、`AudioTrack`、`Chapter`、`PlaybackQueue` 和 `PlaybackCheckpoint`，名称可随实现调整。

必须保持以下约束：

1. 作品身份包含音源和源站作品标识；RJ 号用于跨源关联，不能代替所有实体的主键。
2. 曲目身份包含音源、作品标识及稳定曲目标识。ASMR.one 可优先参考远端 hash；ASMR18 的文件槽位标识需用真实页面验证稳定性。
3. CDN URL 属于可刷新字段，不能作为收藏、曲目或历史的永久主键。
4. ASMR.one 的多音轨目录和 ASMR18 的单流多章节分别建模；章节跳转是流内 seek，不是跳到另一条音轨。
5. SE有り、SE無し、不同编码或不同来源的版本保留独立队列；不自动把它们混排。
6. 保存队列顺序、当前索引、目录范围、播放位置和倍速，应用重开后恢复。源链接失效时重新解析，再定位原曲目与进度；无法对应时提示重新选择。
7. seek 位置与实际收听时长分开记录；拖到末尾不等于听完。
8. 无痕模式不新增历史和个人进度，退出后不补传无痕期间记录；普通音频缓存策略单独定义。
9. 分级显示设置同时作用于浏览、搜索结果、本地收藏与历史展示；保留安全的默认视图。

## 7. 首版功能

| 功能 | 首版要求 |
| --- | --- |
| 音源切换 | 两源独立浏览、加载状态、分页与错误提示 |
| 作品详情 | 标题、封面、编号及源站实际提供的信息 |
| ASMR.one 曲目 | 原目录树、版本选择、目录范围队列、配套字幕 |
| ASMR18 播放 | 多文件选择、HLS 播放、章节列表与跳转 |
| 播放控制 | 播放/暂停、上下一曲、拖动、倍速、当前队列 |
| 系统集成 | 后台与锁屏播放、通知栏、耳机控制、音频焦点、拔耳机暂停 |
| 个人数据 | 本地收藏或想听、最近收听、历史、进度与队列恢复 |
| 常用设置 | 网络配置、音源镜像设置、分级显示、缓存管理、无痕与历史清除 |
| 睡眠定时 | 定时暂停，适合在核心播放稳定后完成 |
| 搜索 | 验证后实现上游支持的搜索；不把当前页过滤标为全站搜索 |

后续扩展包括离线下载、复杂播放列表、多设备同步、源站账号同步和其他音源。首版不增加本地媒体扫描、WebDAV 服务或服务器管理面板。

## 8. 实施阶段及验收

| 阶段 | 工作与产出 | 验收条件 |
| --- | --- | --- |
| P0 环境与音源验证 | 拉取新仓库；建立工程和 Wrapper；验证两源目录、详情、媒体请求及搜索能力；记录请求头和错误样例 | 工程可构建，两源各有从入口到媒体请求的验证记录；在线访问问题与解析问题能区分 |
| P1 原生播放 | Media3 播放服务、HLS、队列、系统媒体控制、播放位置持久化 | 两源真实媒体可播放；HLS 拖动与章节有效；熄屏、耳机与音频打断场景通过 |
| P2 浏览界面 | 源切换、分页、搜索能力入口、详情、目录树、章节、字幕 | 两源均能从浏览进入播放；目录与版本不串队；缺字段和无字幕能正常显示 |
| P3 日常功能 | 收藏、历史、重开续播、无痕、睡眠定时、缓存和网络设置 | 应用重开恢复正确曲目和队列；断网及失效链接可恢复；隐私设置一致生效 |
| P4 构建发布 | CI、依赖与解析测试、Lint、APK 产物、签名配置和安装说明 | Debug APK 可下载和安装；正式发布使用持久签名，覆盖升级保留个人数据 |

阶段可以交错开发，但不能把“工程能编译”当成双源安卓应用已完成。未经实际验证的项目应明确留在未完成清单中。

自动化检查重点放在解析样例、目录与章节映射、稳定标识、历史恢复及网络错误处理；网络测试与离线单元测试分开，避免源站暂时不可用导致所有测试失效。

至少完成以下集成场景：

- 两源分别完成浏览 → 详情 → 选择版本/曲目 → 播放。
- ASMR.one 多层目录不混版本，字幕随切轨和 seek 更新。
- ASMR18 HLS 清单、分片及密钥请求正确；章节跳转、拖动和自动结束正常。
- 连续锁屏播放和自动切轨；耳机按键、蓝牙切换、来电或其他音频打断、拔耳机行为正确。
- 应用重新打开后恢复位置、当前版本和队列；用户主动强制停止应用后不承诺自动继续播放。
- 镜像失败、网络超时、429/5xx、访问挑战、空目录、解析失败和过期链接有可操作的状态。
- 无痕不写入个人历史与进度；分级隐藏后相关内容不出现在最近收听等入口。
- APK 安装、覆盖升级、数据库迁移与个人数据保留通过验证。

## 9. 新 session 的执行要求

- 从目标仓库及其 AGENTS.md、README、现有代码开始检查，不假定仓库仍为空。
- 使用独立、持久的本地 checkout 开发；不要继续使用此前未完成克隆的临时目录。
- 需要新分支时使用 `codex/` 前缀；提交、推送与发布遵循新仓库实际规则及当前授权。
- 可以并行实现音源适配、原生播放和界面，但先统一模型与接口。
- 保留原 Go 工作区的未提交内容。引用或移植当前本地实现前，核实它是否已进入远端。
- 开发环境不足时先完成能够独立验证的部分，再解决必要环境问题；不能仅生成工程骨架就宣布完成。
- 区分离线解析测试、构建通过、线上媒体访问和真机播放验证，分别报告证据。

可直接复制给新 session 的启动提示词：

```text
请在独立仓库 https://github.com/SiIverAsh/Otoha_mobile.git 开发 Otoha 的安卓版本。

请先完整阅读交接文档：
/Users/silverash/Documents/code/github_open_source/Otoha/docs/android-development-handoff.md

最终需求：APK 直接连接 ASMR.one 和 asmr18.fans，提供双源浏览、按源能力搜索、
作品详情、目录/版本选择、音频与 HLS 播放、章节、字幕、原生后台及锁屏控制，
收藏、收听历史、进度与队列保存在手机。无需电脑/NAS，也无需部署 Otoha Go 服务。
此前本地音频建库的方案已经作废，不要沿用。

使用 Kotlin + Compose + Media3。先检查目标仓库和开发环境，建立可复现构建，
验证两源真实请求链路，再推进播放器、界面和日常功能。搜索协议需要单独验证；
ASMR18 现有分类/页面解析不等于已经支持关键词搜索。

原 Go 参考项目位于 /Users/silverash/Documents/code/github_open_source/Otoha，
其中有大量未提交修改。可以只读参考音源解析、曲目/章节和字幕规则，保留许可，
不要覆盖或提交该工作区的已有改动。Android 代码全部写在新仓库。

请直接开始实施并持续推进，提供实际构建、测试和播放验证结果；遇到需要用户处理
的环境或授权问题时说明具体阻碍。未验证的能力保持未完成，不要把工程骨架当作成品。
```

## 10. 官方实现参考

- [Jetpack Compose](https://developer.android.com/compose)
- [Media3 后台播放与 MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls)
- [Media3 请求头与数据源定制](https://developer.android.com/media/media3/exoplayer/customization)
- [Media3 离线下载，供后续阶段参考](https://developer.android.com/media/media3/exoplayer/downloading-media)

接手时重新核对依赖兼容性、Android 平台要求和上游音源行为。
