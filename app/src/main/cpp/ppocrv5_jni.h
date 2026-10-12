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

#ifndef PPOCRV5_JNI_H
#define PPOCRV5_JNI_H

#include <jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeCreate(
        JNIEnv *env,
        jobject thiz,
        jstring det_model_path,
        jstring rec_model_320_path,
        jstring rec_model_640_path,
        jstring keys_path,
        jint accelerator_type);

JNIEXPORT jobjectArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeProcess(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap);

JNIEXPORT jobjectArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeDetectTextBoxes(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap);

JNIEXPORT void JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeDestroy(
        JNIEnv *env,
        jobject thiz,
        jlong handle);

JNIEXPORT jobject JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeRecognizeTextBox(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jobject bitmap);

JNIEXPORT jfloatArray JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeGetBenchmark(
        JNIEnv *env,
        jobject thiz,
        jlong handle);

JNIEXPORT jint JNICALL
Java_me_fleey_ppocrv5_ocr_OcrEngine_nativeGetActiveAccelerator(
        JNIEnv *env,
        jobject thiz,
        jlong handle);

}  // extern "C"

#endif  // PPOCRV5_JNI_H
