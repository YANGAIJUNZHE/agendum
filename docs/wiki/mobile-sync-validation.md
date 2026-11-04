# 手机同步验收记录

实施与验收时间：2026-11-01 至 2026-11-02。数据库、协议、网页、Android 本地队列、固定域名和实际设备分别验证；不以单元测试代替公网或真机结果。

## 已执行的验收

| 验收组 | 结果与范围 |
| --- | --- |
| 指定 Python 回归 | 同一组 502 项连续两轮通过，退出码均为 0。存储、恢复、待办应用层、日历、ICS、配对、路由、Web、wiki、新同步存储与 HTTP，以及手机内置页面交互 |
| Android 构建与 JVM | JDK 21、SDK 35、Gradle 8.11.1；Debug APK 与测试 APK 构建、4 项模型测试连续两轮通过，退出码均为 0 |
| Android 本地集成 | API 28 x86 只读模拟器；10 项连续两轮通过，ADB 退出码均为 0，输出均为 `OK (10 tests)`。真实 Room、Keystore、持久队列与 Repository，HTTP 对端为可控模型。覆盖离线全部管理操作、响应丢失、发送途中再编辑、字段冲突、后续编辑保留、删除另存、后端隔离、撤销及界面订阅生命周期 |
| 实际客户端链路 | 电脑 Edge 页面、手机内置页面和真实 APK + Python HTTP 后端共 5 项，连续两轮通过，退出码均为 0。包含编辑打开期间收到另一端修改、杀进程恢复和后端重启 |
| 固定域名 | 配置的 HTTPS 入口 `/api/v1/info` 实际返回 200；16 项公网检查连续两轮通过。配对、全量与增量、新建、重试去重、两端读写、完成恢复、删除和撤销均经过真实隧道 |
| 真实手机 | Android API 36 测试设备。最终 12 项检查连续两轮通过，Node 退出码均为 0。独立验收副本完成公网配对、离线保存与强停恢复、后端重启、同字段冲突选择、完成恢复、确认删除及已有配对从深链冷启动 |

公网的 `/`、`/api/meta`、`/api/v1/admin/devices` 均返回 404；未配对与已撤销设备读取同步返回 401。响应为 `Cache-Control: no-store`，Cloudflare 实测 `CF-Cache-Status: DYNAMIC`。验收环境未创建额外的自定义 Bypass 规则；接口及当时的 Cloudflare 默认策略禁止这些响应缓存。

每轮 Python 测试前都在实际运行环境确认无其他 Python 进程，不使用 discover。早期一次回归在读取旧更新模块的临时 `state.json` 时遇到 Windows `PermissionError`；该次失败日志保留，未放宽断言，随后同一组 502 项连续两轮均通过。真机暴露的界面刷新落后一轮问题已修复：旧 Activity 不能取消新 Activity 的订阅，本地保存与前台同步结束后都会重新读本地数据库。

## 真机安装与升级边界

首轮验收时测试设备原有 `com.noticehub.app` 为已发布签名的 beta.15；本地 Debug 证书不同，不能保留数据直接覆盖。验收使用 `-PnoticehubValidation` 构建的 `com.noticehub.app.validation`，当时显示名称「群务台（同步验收）」。首轮验收保留了原 beta.15，后续更新与清理见下节。

默认构建仍使用原包名 `com.noticehub.app`。两份 APK 都是调试签名，正式覆盖升级需要原发行签名。此次验证了真实手机上的新版本功能与数据持久化，未宣称已验证发行签名覆盖升级。

## 安卓 beta.17 更新与手机残留检查

2026-11-02，安卓版升级为 `1.1.0-beta.17`（versionCode 17），显示名称统一为「群务台」，移除括号。新版原包名不变，采用覆盖安装保留数据；旧版卸载应在确认新版数据完整后进行。检查范围包括主用户安装包、共享存储顶层、下载与文档目录、ADB 临时目录，以及本项目 App 的外部目录。

已在 Android 测试设备完成覆盖安装。读回手机安装包的 SHA-256 与构建文件一致，系统应用信息页显示「群务台」「版本 1.1.0-beta.17」。升级前后配对、后端身份、本地日程和待同步数量一致；随后卸载旧 `com.noticehub.app` 及其数据，测试环境只保留新版 `com.noticehub.app.validation`。

同一组版本、残留与冷启动检查连续两轮通过，退出码均为 0：旧版及测试包不存在；上述可访问目录未发现本项目验收文件，旧版外部目录与 `offline-tests` 测试数据库不存在。新版的 `schedule-sync.db` 及正常 WAL、SHM 和后台任务数据保留，不能作为残留删除。检查后手机仍已配对，待同步为 0。本轮证据与新版 APK 位于 `.tmp-qa/mobile-sync/package-update/`，首轮同步行为验收记录仍按 beta.16 留档。

本次新建日程均为验收临时数据，验收后清理；操作回执与删除记录按协议继续保留。

## 复核命令与产物

Python 主验收的指定模块：

```powershell
./.venv/Scripts/python.exe -m unittest tests.test_sync_store tests.test_sync_api tests.test_application_tasks tests.test_store tests.test_store_recovery tests.test_application_calendar tests.test_ics tests.test_pairing tests.test_routes tests.test_webapp tests.test_wiki tools.e2e.mobile_ui_check -q
```

客户端专项组：

```powershell
./.venv/Scripts/python.exe -m unittest tools.e2e.desktop_sync_check tools.e2e.mobile_ui_check tools.e2e.mobile_native_check -v
```

上述客户端组依赖本机 Edge、Node、ADB 和本任务模拟器，不参与常规 CI 自动发现。真实手机使用 `tools/e2e/phone_sync_check.mjs`，必须明确提供 `NOTICEHUB_PHONE_SERIAL`，只操作验收副本与脚本创建的临时日程，不执行卸载或清数据。它会短暂停止并重启本项目后端来验证电脑离线。

`mobile_native_check.py` 同样要求通过 `NOTICEHUB_PHONE_SERIAL` 指定测试设备，不自动选择已连接设备。ADB 默认从 PATH 查找，可用 `NOTICEHUB_TEST_ADB` 指定路径。公网验收脚本必须显式传入自己的入口，例如 `./tools/e2e/public_sync_check.ps1 -Base https://sync.example.com`；示例域名需要替换为实际部署域名。

本地证据统一位于 `.tmp-qa/mobile-sync/`：两个 APK、SHA-256、构建与测试日志、公网与真机 JSON 记录，以及离线和冲突截图。这个目录不提交仓库，不包含可公开发布的管理员令牌或隧道令牌。

后台十五分钟调度受系统省电策略约束；此次没有把前台同步实测当作所有厂商后台准时到达的保证。当前工作区没有配置真实 QQ 群和机器人凭据，真实 QQ 通知自动生成未在这台手机上实测；已有生成链路及其写入保护通过存储与应用层回归覆盖。
