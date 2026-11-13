# 发布版本

1. 在 `agendum/version.py` 更新 `APP_VERSION` 和 `APP_CHANNEL`。测试版用 `beta` 和 `X.Y.Z-beta.N`，正式版用 `stable` 和 `X.Y.Z`。若涉及 Android 版本递进，同步检查 `android/app/build.gradle` 的 `versionCode`。
2. 确保仓库已配置 Android 签名密钥（GitHub Actions Secrets）：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`。密钥库是 PKCS12，**只有一把密码**——`keytool` 生成时会忽略 `-keypass`（提示「PKCS12 密钥库不支持独立的密钥密码」），私钥始终用 `storePassword` 加密。所以 `ANDROID_KEY_PASSWORD` 可以不配，不配时工作流自动沿用 `ANDROID_KEYSTORE_PASSWORD`；配了就必须与它相同，否则打包报错。
3. 把更新日志中本次交付的条目移入 `## X.Y.Z-beta.N · YYYY-MM-DD`（正式版去掉后缀），保留「未发布」小节。同时在 `release/update-manifest.json` 顶部加入本版条目：`tag`、`version`、`name`、`notes` 先写好，`size`、`sha256`、`download_url` 留待发布后回填（`tools/version_bump.py` 门禁要求最新条目与 `APP_VERSION` 一致）。提交并推送到自己的仓库。
4. 给准备发布的提交创建一个新标签，并只推送该标签。下面以 beta.19 为例，`fork` 是指向 `YANGAIJUNZHE/agendum` 的 Git 远端。

   ```powershell
   git tag -a v1.1.0-beta.19 -m "v1.1.0-beta.19"
   git push fork refs/tags/v1.1.0-beta.19
   ```

5. 在 [Actions](https://github.com/YANGAIJUNZHE/agendum/actions/workflows/release.yml) 查看结果。确认 [Releases](https://github.com/YANGAIJUNZHE/agendum/releases) 包含 `Agendum.zip` 与 `agendum-android.apk` 及其各自的 `.sha256` 校验文件。测试版会标为预发布。安装和校验方法见 [安装与升级](install.md)。
6. 回填并签发更新清单：把本版资产的实际 `size`、`sha256`、`download_url` 填进清单的本版条目，运行 `python release/sign-manifest.py --key <私钥路径>` 写入签名，再 `python release/sign-manifest.py --check` 自验，提交并推送到默认分支。私钥离线保存在 `~/.dsh/agendum-signing/update-signing-key.json`（key_id `agendum-2026-1`），不进仓库、不进 CI。没有签名条目的版本只能从发布页手动下载。
7. 发版后从该版本的检出目录运行 `pwsh -File tools/wiki_sync.ps1`。脚本推送到自己的 GitHub Wiki。首次同步前需要在仓库 Wiki 创建首页，本地 Git 账号需要有写权限。

标签决定构建哪份源码。发布工作流先校验标签与应用版本、通道和更新日志，再把提交 SHA 传给测试工作流。Windows 冒烟测试与 Android 构建测试通过后分别上传 Windows ZIP 和 Android APK。等 `ci-green` 汇总通过，发布任务下载测试过的两类产物、计算校验值并创建 Release。只有发布任务有写 Release 的权限。

临时故障可以在 Actions 重跑，或在默认分支的「Run workflow」填入已存在的标签。手动运行也按标签取源码。若需要改代码，提交修复后使用新版本和新标签。已发布的版本不会被工作流覆盖。上传中断时先检查是否留下草稿，确认草稿状态后再重试。

Android 签名在打包前会先验一遍：工作流跑 `./gradlew verifyReleaseSigning`，用 `keystore.properties` 里的别名和密码真解一次私钥，解不开就直接红，并说清是 `storePassword`、`keyAlias` 还是 `keyPassword` 出了问题。本地也可以自己跑一次 `cd android; ./gradlew verifyReleaseSigning`。签名产物还要过 `apksigner verify`，未签名的 `*-unsigned.apk` 不会被当成发布包上传。看到「Get Key failed: Given final block not properly padded」，就是 `keyPassword` 与 `storePassword` 不一致：PKCS12 里这两者必须相同，改 Secret 或删掉 `keyPassword` 一行让构建回落到 `storePassword`。

发布流程交付 Windows ZIP 和 Android APK，Linux 和 macOS 按源码安装。软件会从本仓库检查新版本，未纳入签名更新清单的版本从发布页手动下载。附件附带的 SHA-256 用于核对下载完整性，应用内更新仍须通过原有签名和信任校验。
