# 第三方代码和二进制输入

第三方组件保留原许可证。完整随包清单见 [open_source_notices.txt](../app/src/main/assets/licenses/open_source_notices.txt)。

| 组件 | 位置 | 来源与版本 |
| --- | --- | --- |
| OCR 集成 | `app/src/main/java/io/github/lumiagg/gestureevo/ocr` | 截图管线基于 CyIME `c875fe8`，GPL-3.0，保留 PaddleOCR / RapidOCR 的 Apache-2.0 声明 |
| OCR 模型 | `app/src/main/assets/ocr` | PaddleOCR PP-OCRv5 Mobile，RapidOCR 3.9.2 ONNX 权重与字典，Apache-2.0 |
| OCR 运行库 | Gradle Maven 依赖 | ONNX Runtime Android 1.28.0，MIT；OpenCV Android 4.12.0，Apache-2.0 |
| TinyPinyin | `app/libs/tinypinyin-2.0.3.jar` | promeG/TinyPinyin 2.0.3，Apache-2.0 |
| 分词词典 | `app/src/main/assets/segmenter/zh_core_dict.txt` | jieba / THUOCL 和项目自定义词，保留 MIT 声明及生成脚本 |

[OCR 来源与许可](../app/src/main/assets/licenses/ocr/PP-OCR-NOTICE.txt)记录模型原始下载地址和上游版本。[binary-manifest.json](binary-manifest.json) 记录本地 JAR 与模型的 SHA-256，仓库检查会核对其内容。
