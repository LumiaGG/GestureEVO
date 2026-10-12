/*
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ppocrv5_jni.h"
#include "ocr_engine.h"
#include "litert_env_manager.h"

#include <android/bitmap.h>
#include <string>

#include "logging.h"

#define TAG "PPOCRv5_JNI"

namespace {

    std::string jstring_to_string(JNIEnv *env, jstring jstr) {
        if (jstr == nullptr) {
            return "";
        }
        const char *chars = env->GetStringUTFChars(jstr, nullptr);
        if (chars == nullptr) {
            return "";
        }
        std::string result(chars);
        env->ReleaseStringUTFChars(jstr, chars);
        return result;
    }

    ppocrv5::AcceleratorType int_to_accelerator_type(jint type) {
        return ppocrv5::AcceleratorType::kCpu;
    }

    jint accelerator_type_to_int(ppocrv5::AcceleratorType type) {
        return 1;
    }

    jobject create_ocr_result_object(JNIEnv *env, const ppocrv5::OcrResult &result) {
        jclass ocr_result_class = env->FindClass("me/fleey/ppocrv5/ocr/OcrResult");
        if (ocr_result_class == nullptr) {
            LOGE(TAG, "Failed to find OcrResult class");
            return nullptr;
        }

        jmethodID constructor = env->GetMethodID(
                ocr_result_class, "<init>",
                "(Ljava/lang/String;FFFFFF)V");
        if (constructor == nullptr) {
            LOGE(TAG, "Failed to find OcrResult constructor");
            env->DeleteLocalRef(ocr_result_class);
            return nullptr;
        }

        jstring text = env->NewStringUTF(result.text.c_str());
        if (text == nullptr) {
            LOGE(TAG, "Failed to create text string");
            env->DeleteLocalRef(ocr_result_class);
            return nullptr;
        }

        jobject obj = env->NewObject(
                ocr_result_class, constructor,
                text,
                result.confidence,
                result.box.center_x,
                result.box.center_y,
                result.box.width,
                result.box.height,
                result.box.angle);

        env->DeleteLocalRef(text);
        env->DeleteLocalRef(ocr_result_class);

        return obj;
    }

    jobject create_rotated_box_object(JNIEnv *env,
                                      jclass box_class,
                                      jmethodID constructor,
                                      const ppocrv5::RotatedRect &box) {
        return env->NewObject(box_class, constructor,
                                     box.center_x, box.center_y, box.width, box.height, box.angle);
    }

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeSetCacheDir(
        JNIEnv *env,
        jclass clazz,
        jstring cache_dir) {

    std::string cache_path = jstring_to_string(env, cache_dir);
    if (!cache_path.empty()) {
        ppocrv5::LiteRtEnvManager::GetInstance().SetCacheDirectory(cache_path);
        LOGD(TAG, "NPU compiler cache directory set: %s", cache_path.c_str());
    }
}

JNIEXPORT void JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeShutdown(
        JNIEnv *env,
        jclass clazz) {

    ppocrv5::LiteRtEnvManager::GetInstance().Shutdown();
    LOGD(TAG, "LiteRT environment shutdown complete");
}

JNIEXPORT jlong JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeCreate(
        JNIEnv *env,
        jobject thiz,
        jstring det_model_path,
        jstring rec_model_320_path,
        jstring rec_model_640_path,
        jstring keys_path,
        jint accelerator_type) {

    std::string det_path = jstring_to_string(env, det_model_path);
    std::string rec_320_path = jstring_to_string(env, rec_model_320_path);
    std::string rec_640_path = jstring_to_string(env, rec_model_640_path);
    std::string keys = jstring_to_string(env, keys_path);

    if (det_path.empty() || rec_320_path.empty() || rec_640_path.empty() || keys.empty()) {
        LOGE(TAG, "Invalid model paths provided");
        return 0;
    }

    LOGD(TAG, "Creating OcrEngine: det=%s, rec320=%s, rec640=%s, keys=%s, accelerator=%d",
         det_path.c_str(), rec_320_path.c_str(), rec_640_path.c_str(), keys.c_str(), accelerator_type);

    ppocrv5::AcceleratorType accel_type = int_to_accelerator_type(accelerator_type);

    auto engine = ppocrv5::OcrEngine::Create(det_path, rec_320_path, rec_640_path, keys, accel_type);
    if (!engine) {
        LOGE(TAG, "Failed to create OcrEngine");
        return 0;
    }

    LOGD(TAG, "OcrEngine created successfully");
    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT jobjectArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeProcess(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap) {

    if (handle == 0) {
        LOGE(TAG, "Invalid engine handle");
        return nullptr;
    }

    if (bitmap == nullptr) {
        LOGE(TAG, "Bitmap is null");
        return nullptr;
    }

    auto *engine = reinterpret_cast<ppocrv5::OcrEngine *>(handle);

    AndroidBitmapInfo bitmap_info;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE(TAG, "Failed to get bitmap info");
        return nullptr;
    }

    if (bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE(TAG, "Unsupported bitmap format: %d (expected RGBA_8888)", bitmap_info.format);
        return nullptr;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE(TAG, "Failed to lock bitmap pixels");
        return nullptr;
    }

    auto results = engine->Process(
            static_cast<const uint8_t *>(pixels),
            static_cast<int>(bitmap_info.width),
            static_cast<int>(bitmap_info.height),
            static_cast<int>(bitmap_info.stride));

    AndroidBitmap_unlockPixels(env, bitmap);

    jclass ocr_result_class = env->FindClass("me/fleey/ppocrv5/ocr/OcrResult");
    if (ocr_result_class == nullptr) {
        LOGE(TAG, "Failed to find OcrResult class for array creation");
        return nullptr;
    }

    jobjectArray result_array = env->NewObjectArray(
            static_cast<jsize>(results.size()),
            ocr_result_class,
            nullptr);

    if (result_array == nullptr) {
        LOGE(TAG, "Failed to create result array");
        env->DeleteLocalRef(ocr_result_class);
        return nullptr;
    }

    for (size_t i = 0; i < results.size(); ++i) {
        jobject result_obj = create_ocr_result_object(env, results[i]);
        if (result_obj != nullptr) {
            env->SetObjectArrayElement(result_array, static_cast<jsize>(i), result_obj);
            env->DeleteLocalRef(result_obj);
        }
    }

    env->DeleteLocalRef(ocr_result_class);

    LOGD(TAG, "Processed frame: %zu results", results.size());
    return result_array;
}

JNIEXPORT jobject JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeRecognizeTextBox(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap) {
    if (handle == 0 || bitmap == nullptr) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 2 || info.height < 2) {
        return nullptr;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    ppocrv5::OcrResult result;
    try {
        result = reinterpret_cast<ppocrv5::OcrEngine *>(handle)->RecognizeTextBox(
                static_cast<const uint8_t *>(pixels),
                static_cast<int>(info.width), static_cast<int>(info.height),
                static_cast<int>(info.stride));
    } catch (...) {
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE(TAG, "Text box recognition failed");
        return nullptr;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return create_ocr_result_object(env, result);
}

JNIEXPORT jobjectArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeDetectTextBoxes(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap) {
    if (handle == 0 || bitmap == nullptr) {
        LOGE(TAG, "Invalid engine handle or bitmap for detection");
        return nullptr;
    }

    auto *engine = reinterpret_cast<ppocrv5::OcrEngine *>(handle);
    AndroidBitmapInfo bitmap_info;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE(TAG, "Invalid bitmap for detection");
        return nullptr;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    auto boxes = engine->DetectTextBoxes(
            static_cast<const uint8_t *>(pixels),
            static_cast<int>(bitmap_info.width),
            static_cast<int>(bitmap_info.height),
            static_cast<int>(bitmap_info.stride));
    AndroidBitmap_unlockPixels(env, bitmap);

    jclass box_class = env->FindClass("me/fleey/ppocrv5/ocr/RotatedBox");
    if (box_class == nullptr) return nullptr;
    jmethodID box_constructor = env->GetMethodID(box_class, "<init>", "(FFFFF)V");
    if (box_constructor == nullptr) {
        env->DeleteLocalRef(box_class);
        return nullptr;
    }
    jobjectArray result_array = env->NewObjectArray(
            static_cast<jsize>(boxes.size()), box_class, nullptr);
    if (result_array == nullptr) {
        env->DeleteLocalRef(box_class);
        return nullptr;
    }
    for (size_t i = 0; i < boxes.size(); ++i) {
        jobject box_obj = create_rotated_box_object(
                env, box_class, box_constructor, boxes[i]);
        if (box_obj != nullptr) {
            env->SetObjectArrayElement(result_array, static_cast<jsize>(i), box_obj);
            env->DeleteLocalRef(box_obj);
        }
    }
    env->DeleteLocalRef(box_class);
    return result_array;
}

JNIEXPORT void JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeDestroy(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {

    if (handle == 0) {
        LOGD(TAG, "nativeDestroy called with null handle");
        return;
    }

    auto *engine = reinterpret_cast<ppocrv5::OcrEngine *>(handle);
    delete engine;

    LOGD(TAG, "OcrEngine destroyed");
}

JNIEXPORT jfloatArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeGetBenchmark(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {

    if (handle == 0) {
        LOGE(TAG, "Invalid engine handle for benchmark");
        return nullptr;
    }

    auto *engine = reinterpret_cast<ppocrv5::OcrEngine *>(handle);
    ppocrv5::Benchmark benchmark = engine->GetBenchmark();

    jfloatArray result = env->NewFloatArray(4);
    if (result == nullptr) {
        LOGE(TAG, "Failed to create benchmark array");
        return nullptr;
    }

    jfloat data[4] = {
            benchmark.detection_time_ms,
            benchmark.recognition_time_ms,
            benchmark.total_time_ms,
            benchmark.fps
    };

    env->SetFloatArrayRegion(result, 0, 4, data);

    return result;
}

JNIEXPORT jint JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeGetActiveAccelerator(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {

    if (handle == 0) {
        LOGE(TAG, "Invalid engine handle for accelerator query");
        return 2;
    }

    auto *engine = reinterpret_cast<ppocrv5::OcrEngine *>(handle);
    ppocrv5::AcceleratorType accelerator = engine->GetActiveAccelerator();

    return accelerator_type_to_int(accelerator);
}

}  // extern "C"
