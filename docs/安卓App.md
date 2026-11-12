# 安卓 App

当前开发版已改为支持离线编辑的日程客户端。安装、配对、迁移、固定域名与同步说明统一维护在 [wiki：手机日程管理与离线同步](wiki/mobile-sync.md)，验证范围见 [验收记录](wiki/mobile-sync-validation.md)。

已经发布的 beta.16 APK 仍使用旧版只读协议。新版需要配套的电脑端代码，并重新进行设备配对；不能只安装旧 Release APK 来使用新功能。

包名自本次改名起为 `com.agendum.app`（旧版是 `com.noticehub.app`）。两者是不同的应用：先把旧版待同步编辑同步回电脑，再卸载旧版、安装新版并重新扫码配对。

源码工程在 `android/`。最低 Android 6.0（API 23），编译 SDK 35，使用 JDK 17 或 21。常用构建命令：

```powershell
cd android
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。它使用调试签名；若手机已经安装了其他签名的同包名版本，不能直接覆盖安装。先保存旧数据，再决定是否卸载。正式升级包必须使用原发布签名构建，不要把调试签名当作正式发行签名。
