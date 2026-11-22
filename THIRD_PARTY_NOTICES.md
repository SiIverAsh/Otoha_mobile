# 来源与许可

Otoha Mobile 延续 [SiIverAsh/Otoha](https://github.com/SiIverAsh/Otoha) 的 GPL-3.0-or-later 许可，参见 LICENSE 和完整条款 COPYING。

下列 Kotlin 实现按原项目当前本地源码移植其业务规则，原文件可能包含尚未进入上游 main 的修改：

| 安卓文件 | 原项目参考文件 |
| --- | --- |
| AsmrOneClient / AsmrOneParser | internal/asmrone/client.go、dto.go、client_test.go |
| AsmrOneTrackTitles / DlsiteTrackListParser | internal/scrape/asmrone_track_titles.go、internal/db/asmrone_tracks.go、internal/dlsite/tracklist.go |
| Asmr18Client / Asmr18Parser 与 work.html 测试样例 | internal/asmr18/client.go、client_test.go、testdata/work.html |
| SubtitleParser | internal/subtitle/parse.go、parse_test.go |

原项目工作区只读使用。未内嵌原 Go HTTP 服务、索引或数据库。

主要第三方组件：Kotlin、kotlinx.coroutines、kotlinx.serialization、AndroidX / Jetpack Compose / Media3、OkHttp、Coil（Apache-2.0），jsoup（MIT），JUnit 4（EPL-1.0）。这些组件的许可由各自作者保留。构建时从标准 Maven 仓库获取其发行包与许可元数据。
