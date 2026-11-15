# 使用 GitHub Actions 发布安卓版本

发布入口是 `.github/workflows/release.yml` 的 **Release Android → Run workflow**。选择仓库默认分支，再选择 `bump`，即可自动递增版本并创建 GitHub Release，无需手填 tag。它只响应手动 `workflow_dispatch`，推送代码或标签不会自动发布。所有发布共用一个并发组，不会相互中断。

版本基线来自默认分支 `app/build.gradle.kts`；以 `0.2.1` 为例：

| bump | 类型 | 新 versionName / Release |
| --- | --- | --- |
| `patch`（默认） | 补丁 | `0.2.2` / `v0.2.2` |
| `minor` | 小版本 | `0.3.0` / `v0.3.0` |
| `major` | 大版本 | `1.0.0` / `v1.0.0` |

三种选择都会将 Android `versionCode` 加 1。工作流固定使用 dispatch 时的提交和同一版本计算结果进行测试、Lint、模拟器回归及签名构建，再读取 APK 的 `output-metadata.json` 核对版本名和 versionCode。

所有检查通过后，工作流自动提交新的 `versionName` / `versionCode` 到默认分支，Release 及其内部版本 tag 指向这个版本提交，供下次发布继续递增。发布说明默认由 GitHub 根据提交自动生成；也可提前提交 `docs/releases/vX.Y.Z.md` 使用自定义说明，文件不是必需项。

## 签名配置

仓库维护者需配置以下 Actions repository secrets：

| Secret | 内容 |
| --- | --- |
| `OTOHA_KEYSTORE_BASE64` | 持久签名 keystore 文件的 Base64 内容 |
| `OTOHA_KEYSTORE_PASSWORD` | keystore 密码 |
| `OTOHA_KEY_ALIAS` | 签名条目 alias |
| `OTOHA_KEY_PASSWORD` | 签名条目密码 |

**当前发行版本沿用早期开发版本的既有签名，以便覆盖安装并保留设备上的个人数据。** 应备份并使用同一份已有 keystore，不能在每次 Actions 运行时重新生成开发密钥。发布说明也应明确这一签名安排；如果以后更换签名，需要另行制定迁移方案。

只有 `publish` job 的签名构建步骤会接收上述 secrets。keystore 解码到 `$RUNNER_TEMP`，文件权限受 `umask 077` 限制，步骤退出时删除；路径和密码不写入 `GITHUB_ENV`。构建关闭配置缓存，并使用进程内 Kotlin 编译，避免另起持有签名环境变量的 Kotlin daemon。产物上传路径不包含 keystore。

## 检查与发布

选择 `patch`、`minor` 或 `major`，保留或取消 `prerelease` 后运行工作流。发布前必须通过：

1. 版本递增脚本的离线单元测试、Core JVM 单元测试、Android app JVM/Robolectric 测试及 `lintRelease`。
2. 使用主机 ICU 实际编译源码正则的回归检查。
3. Android 36、`google_apis`、x86_64 模拟器上的三个 `AndroidParserCompatibilityTest` 测试。模拟器启动或测试失败会阻止发布；仅编译 instrumentation APK 不算通过。
4. Release APK 构建、`apksigner` 签名校验、自动版本与 APK 元数据核对及 SHA-256 校验。

这些检查使用离线样例，不请求 ASMR.one 或 ASMR18 音源。构建工具和依赖由 Actions 联网获取。

所有检查通过并保存版本提交后，工作流创建内部版本 tag，并通过 `gh release create --target "$RELEASE_SHA"` 创建 Release。运行摘要会显示 Release 链接，附件为：

- `Otoha-Mobile-vX.Y.Z.apk`
- `SHA256SUMS`

默认发布为 prerelease。下载后可使用 `sha256sum --check SHA256SUMS` 核验 APK。

## 失败处理

工作流会保留 JVM/Lint 报告、模拟器测试报告和已验证的签名产物，默认保留 14 天；构建失败时也会尽量上传诊断报告。签名 secret 和 keystore 不属于这些附件。

版本名只能是 `X.Y.Z`，versionCode 不能超过 Android 的上限 `2100000000`。工作流检查所有非草稿 Release（包含 prerelease）；源码版本落后于已发布版本时会停止，避免使用过期基线。

默认分支在排队或检查期间前进时，运行会停止；请从最新提交重新运行。自动保存版本只允许快进更新，不会覆盖同时推送的代码；分支保护也必须允许该版本提交，否则停止发布。

检查或构建失败时不写回版本。若保存版本提交后发布中断，该版本号已被使用；新建一次运行会继续递增，允许版本号留空。先检查 GitHub 上的版本提交、tag、Release 和附件状态，再处理失败版本，不要直接覆盖重试。已存在的 tag、Release 或资产不会被覆盖；创建 tag 使用新 ref API，检查期间发生的同版本竞争也会失败。

全局 token 权限为 `contents: read`；只有通过前置检查后的 `publish` job 获得 `contents: write`，用于保存版本提交和创建 Release。`GH_TOKEN` 仅映射到最后的发布步骤，版本提交使用 `actions/github-script` 的默认 token。
