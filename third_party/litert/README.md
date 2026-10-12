# LiteRT C++ SDK

LiteRT C++ SDK version 2.1.0 for Android NDK.

## Structure

```
third_party/litert/
├── CMakeLists.txt
├── README.md
├── include/
│   └── litert/
│       ├── c/          # C API headers
│       └── cc/         # C++ API headers
└── lib/
    └── arm64-v8a/
        ├── libLiteRt.so
        └── libLiteRtOpenClAccelerator.so
```

## Setup Instructions

1. Download `litert-2.1.0.aar` from Google Maven:
   https://maven.google.com/web/index.html#com.google.ai.edge.litert:litert:2.1.0

2. Rename `.aar` to `.zip` and extract

3. Copy native libraries from extracted AAR:
   ```bash
   mkdir -p third_party/litert/lib/arm64-v8a
   cp <extracted_aar>/jni/arm64-v8a/*.so third_party/litert/lib/arm64-v8a/
   ```

4. Download LiteRT source from GitHub:
   https://github.com/google-ai-edge/LiteRT/archive/refs/tags/v2.1.0.zip

5. Copy headers from source:
   ```bash
   mkdir -p third_party/litert/include
   cp -R <extracted_source>/litert third_party/litert/include/
   ```

## Note

The `jni/` directory in the AAR is just the standard Android packaging location for native libraries.
Our project uses C++ with LiteRT's CompiledModel API, not the legacy JNI-based Interpreter API.

## Documentation

- [LiteRT Documentation](https://ai.google.dev/edge/litert)
- [CompiledModel API Guide](https://ai.google.dev/edge/litert/inference)
