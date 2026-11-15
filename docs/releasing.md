# 使用 GitHub Actions 发布安卓版本

发布入口是 `.github/workflows/release.yml` 的 **Release Android → Run workflow**。它只响应手动 `workflow_dispatch`，推送代码或标签不会自动发布。同一标签的运行使用同一个并发组，不会相互中断。

发布前，将版本号与发布说明提交到选定分支。例如 `v0.2.0` 必须对应 APK 的 `versionName = "0.2.0"`，说明文件必须存在于 `docs/releases/v0.2.0.md`。工作流会固定检出本次 dispatch 的提交 SHA，构建后再次读取 APK 的 `output-metadata.json` 核对版本。

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

选择新标签（格式 `vX.Y.Z`），保留或取消 `prerelease` 后运行工作流。发布前必须通过：

1. Core JVM 单元测试、Android app JVM/Robolectric 测试及 `lintRelease`。
2. 使用主机 ICU 实际编译源码正则的回归检查。
3. Android 36、`google_apis`、x86_64 模拟器上的三个 `AndroidParserCompatibilityTest` 测试。模拟器启动或测试失败会阻止发布；仅编译 instrumentation APK 不算通过。
4. Release APK 构建、`apksigner` 签名校验、标签与 APK 版本核对及 SHA-256 校验。

这些检查使用离线样例，不请求 ASMR.one 或 ASMR18 音源。构建工具和依赖由 Actions 联网获取。

所有检查通过后，工作流使用当前 SHA 创建新标签，并通过 `gh release create --target "$GITHUB_SHA"` 发布。发行说明直接读取 `docs/releases/$TAG.md`，附件为：

- `Otoha-Mobile-vX.Y.Z.apk`
- `SHA256SUMS`

默认发布为 prerelease。下载后可使用 `sha256sum --check SHA256SUMS` 核验 APK。

## 失败处理

工作流会保留 JVM/Lint 报告、模拟器测试报告和已验证的签名产物，默认保留 14 天；构建失败时也会尽量上传诊断报告。签名 secret 和 keystore 不属于这些附件。

已存在的标签或 Release 会被拒绝，不会覆盖标签、Release 或已有资产。创建标签采用 GitHub 的新 ref API，检查期间发生的同标签竞争也会失败。若发布在创建标签后中断，后续重跑会因该标签已存在而停止；维护者应先检查 GitHub 上的实际标签、Release 和附件状态，再决定如何处理，不能直接覆盖重试。

全局 token 权限为 `contents: read`；只有通过前置检查后的 `publish` job 获得 `contents: write`。`GH_TOKEN` 仅映射到创建标签和 Release 的最后一步。
