# 贡献指南

先阅读 [README 中的构建说明](README.md#构建)。提交代码、构建脚本及文档时，遵循项目 GPL-3.0-only 授权；引入第三方内容必须注明来源、版本和原许可证，保留版权声明。

## 修改范围

- 按功能目录放置代码，跨模块共享且确实通用的逻辑才进入 `utils`。第三方 SDK 放在 `third_party`，不要混入项目功能代码。
- 保留既有数据库记录、配置键、Intent 和 AIDL 契约。修改行为或持久化格式时，明确迁移方案、兼容范围及验证结果。
- Kotlin 遵循官方风格及 `.editorconfig`；优先明确类型和安全空值分支，避免无依据的 `!!`。不要吞掉协程取消，使用可追踪的日志和受生命周期管理的作用域。
- C++ 保留上游声明，区分上游 SDK 与本地适配。不为消除警告而批量修改第三方实现。
- 工具使用 Python 3.10+ 标准库；命令传参数数组，凭据从环境读取。发布通过受保护的 GitHub Actions Environment，保留对应标签源码和许可证。

## 验证与提交

运行 `python tools/check_repository.py`、`python -m unittest discover -s tools/tests` 和 `gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest`（Unix 使用 `./gradlew`）。原生或资源修改还需 `:app:assembleDebug`。纯文档修改检查链接和格式即可。

提交说明写清触发条件、修改后的行为、验证结果及未验证的设备范围。避免提交 SDK 路径、IDE 状态、日志、构建产物、签名文件或账号数据。保持 Room 历史 schema；它们是迁移依据。

报告问题时提供应用版本、Android 版本、设备架构、复现步骤和必要日志。日志应删除个人数据。安全问题请先阅读 [SECURITY.md](SECURITY.md)。
