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

package me.fleey.ppocrv5.ocr

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin

data class OcrResult(
  val text: String,
  val confidence: Float,
  val centerX: Float,
  val centerY: Float,
  val width: Float,
  val height: Float,
  val angle: Float,
) {
  val box: RotatedBox
    get() = RotatedBox(centerX, centerY, width, height, angle)
}

data class RotatedBox(
  val centerX: Float,
  val centerY: Float,
  val width: Float,
  val height: Float,
  val angle: Float,
) {
  fun getCorners(): List<PointF> {
    val angleRad = Math.toRadians(angle.toDouble())
    val cosA = cos(angleRad).toFloat()
    val sinA = sin(angleRad).toFloat()

    val halfW = width / 2f
    val halfH = height / 2f

    val corners = listOf(
      PointF(-halfW, -halfH),
      PointF(halfW, -halfH),
      PointF(halfW, halfH),
      PointF(-halfW, halfH),
    )

    return corners.map { corner ->
      PointF(
        centerX + corner.x * cosA - corner.y * sinA,
        centerY + corner.x * sinA + corner.y * cosA,
      )
    }
  }
}

data class Benchmark(
  val detectionTimeMs: Float = 0f,
  val recognitionTimeMs: Float = 0f,
  val totalTimeMs: Float = 0f,
  val fps: Float = 0f,
)
