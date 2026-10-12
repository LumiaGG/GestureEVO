# GestureEVO

Android 手势与快捷操作工具，让常用操作触手可及。

- 手势轮盘与自定义快捷动作
- 应用、联系人及搜索引擎快捷搜索
- 剪贴板与屏幕内容处理、本地 OCR
- Pin 便签与提醒
- 本地与 WebDAV 备份

支持 Android 7.0 及以上的 ARM64 设备。按功能需要授予无障碍、悬浮窗等权限；部分系统操作需要 Root 或 Shizuku。

## 构建

配置 JDK 17、Python 3.10+ 和 Android SDK 后运行：

```bash
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。所需 Android SDK 组件为 SDK Platform 36、Build Tools 36.0.0、NDK 27.0.12077973 和 CMake 3.22.1。设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在本地 `local.properties` 中配置 `sdk.dir`。

仓库使用 Gradle Wrapper 8.13；首次构建需要联网下载 Gradle、依赖和 Abseil 源码。Python 需支持标准库 `lzma`，可通过 `-PpythonExecutable=/path/to/python` 指定解释器。调试 APK 输出到 `app/build/outputs/apk/debug/`，应用 ID 带 `.debug` 后缀，可与正式版共存。

## 参与与许可

欢迎提交 Issue 或 Pull Request，详见[贡献指南](CONTRIBUTING.md)。安全问题请参阅[安全说明](SECURITY.md)。

主项目采用 [GPL-3.0-only](LICENSE)，第三方组件保留各自许可，见 [NOTICE](NOTICE)。

[隐私政策](PRIVACY_POLICY.md) · [用户协议](USER_AGREEMENT.md)
