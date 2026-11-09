# 发布 Windows 安装包

1. 在 `noticehub/version.py` 更新 `APP_VERSION` 和 `APP_CHANNEL`。测试版用 `beta` 和 `X.Y.Z-beta.N`，正式版用 `stable` 和 `X.Y.Z`。
2. 把更新日志中本次交付的条目移入 `## X.Y.Z-beta.N · YYYY-MM-DD`（正式版去掉后缀），保留「未发布」小节。提交并推送到自己的仓库。
3. 给准备发布的提交创建一个新标签，并只推送该标签。下面以 beta.19 为例，`fork` 是指向 `YANGAIJUNZHE/notice-hub` 的 Git 远端。

   ```powershell
   git tag -a v1.1.0-beta.19 -m "v1.1.0-beta.19"
   git push fork refs/tags/v1.1.0-beta.19
   ```

4. 在 [Actions](https://github.com/YANGAIJUNZHE/notice-hub/actions/workflows/release.yml) 查看结果。确认 [Releases](https://github.com/YANGAIJUNZHE/notice-hub/releases) 有 ZIP 和 `.sha256` 两个附件。测试版会标为预发布。Windows 安装和校验方法见 [安装与升级](install.md)。
5. 发版后从该版本的检出目录运行 `pwsh -File tools/wiki_sync.ps1`。脚本推送到自己的 GitHub Wiki。首次同步前需要在仓库 Wiki 创建首页，本地 Git 账号需要有写权限。

标签决定构建哪份源码。发布工作流先校验标签与应用版本、通道和更新日志，再把提交 SHA 传给测试工作流。Windows 冒烟测试通过后上传测试过的 ZIP。等 `ci-green` 汇总通过，发布任务下载同一个 ZIP、计算校验值并创建 Release。只有发布任务有写 Release 的权限。

临时故障可以在 Actions 重跑，或在默认分支的「Run workflow」填入已存在的标签。手动运行也按标签取源码。若需要改代码，提交修复后使用新版本和新标签。已发布的版本不会被工作流覆盖。上传中断时先检查是否留下草稿，确认草稿状态后再重试。

当前流程交付 Windows ZIP。安卓 APK 需用原发布签名另行构建和验收，Linux 和 macOS 按源码安装。软件会从本仓库检查新版本，未纳入签名更新清单的版本从发布页手动下载。ZIP 附带的 SHA-256 用于核对下载完整性，应用内更新仍须通过原有签名和信任校验。
