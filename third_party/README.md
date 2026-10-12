# 第三方代码和二进制输入

第三方组件保留原许可证，不因主项目采用 GPL-3.0 而删除或改写声明。完整随包清单见 [open_source_notices.txt](../app/src/main/assets/licenses/open_source_notices.txt)。

| 组件 | 位置 | 来源与版本 |
| --- | --- | --- |
| LiteRT SDK | `third_party/litert` | Google LiteRT 2.1.0，Apache-2.0；[复原步骤](litert/README.md) |
| OCR 集成 | `app/src/main/java/me/fleey/ppocrv5`、有对应头声明的 `app/src/main/cpp` 文件 | Fleey 2025，Apache-2.0；保留现有声明 |
| TinyPinyin | `app/libs/tinypinyin-2.0.3.jar` | promeG/TinyPinyin 2.0.3，Apache-2.0 |
| OCR 模型 | `app/src/main/modelAssets/models` | PaddleOCR / PP-OCRv5，Apache-2.0；模型 manifest 保存形状、大小、哈希及来源 |
| 分词词典 | `app/src/main/assets/segmenter/zh_core_dict.txt` | jieba / THUOCL 和项目自定义词；MIT 声明及生成脚本保留 |
| stb 图像写入 | `app/src/main/cpp/stb_image_write.h` | 原文件保留 MIT / 公有领域选项 |
| Abseil | 构建时获取 | Apache-2.0；`20250814.1` 对应的 [提交 d38452e](https://github.com/abseil/abseil-cpp/commit/d38452e1ee03523a208362186fd42248ff2609f6)，CMake 固定完整 SHA |

[binary-manifest.json](binary-manifest.json) 记录本地 JAR、SO 和模型的 SHA-256，仓库检查会核对其内容。Gradle Wrapper JAR 属于构建工具，单独记录在该清单中。已保留的二进制文件不能仅因体积大而删除，否则克隆后无法正常构建。

LiteRT CMake 包装和应用中的 `litert_*_lite.cc` 是集成适配，不属于完整未修改的上游发行包。更新 SDK 时核对调用 API、Android ABI 和头文件/预编译库版本，重新编译原生目标。SDK 的上游文件移动时没有修改内容。
