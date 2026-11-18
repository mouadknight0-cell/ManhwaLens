// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.preprocess

import android.graphics.Bitmap
import com.paddle.ocr.util.BitmapUtils
import com.paddle.ocr.util.ImageUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class DetPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
    val originalH: Int,
    val originalW: Int,
)

object DetPreprocessor {
    private val mean = doubleArrayOf(0.485, 0.456, 0.406)
    private val std = doubleArrayOf(0.229, 0.224, 0.225)
    private const val scale = 1.0 / 255.0

    fun preprocess(
        bitmap: Bitmap,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        imgMode: String,
    ): DetPreprocessResult {
        val src = BitmapUtils.bitmapToBGRMat(bitmap)
        return try {
            preprocess(src, limitSideLen, limitType, maxSideLimit, imgMode)
        } finally {
            src.release()
        }
    }

    fun preprocess(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
        imgMode: String,
    ): DetPreprocessResult {
        require(!src.empty() && src.rows() > 0 && src.cols() > 0) {
            "Detection input image must be non-empty"
        }

        val originalH = src.rows()
        val originalW = src.cols()
        var convertedInput: Mat? = null
        var resized: Mat? = null
        var floatMat: Mat? = null
        val channels = mutableListOf<Mat>()

        try {
            val input = if (imgMode.uppercase() == "RGB") {
                Mat().also {
                    convertedInput = it
                    Imgproc.cvtColor(src, it, Imgproc.COLOR_BGR2RGB)
                }
            } else {
                src
            }

            val resizedInput = ImageUtils.resizeToMultipleOf32(
                input,
                limitSideLen,
                limitType,
                maxSideLimit
            ).also { resized = it }

            val h = resizedInput.rows()
            val w = resizedInput.cols()
            val converted = Mat(h, w, CvType.CV_32FC3).also { floatMat = it }
            resizedInput.convertTo(converted, CvType.CV_32F)

            Core.split(converted, channels)
            for (channelIndex in 0..2) {
                Core.multiply(channels[channelIndex], Scalar(scale), channels[channelIndex])
                Core.subtract(channels[channelIndex], Scalar(mean[channelIndex]), channels[channelIndex])
                Core.divide(channels[channelIndex], Scalar(std[channelIndex]), channels[channelIndex])
            }

            val channelSize = Math.multiplyExact(h, w)
            val tensorSize = Math.multiplyExact(3, channelSize)
            val tensorData = FloatArray(tensorSize)
            for (channelIndex in 0..2) {
                val channel = channels[channelIndex]
                val buffer = FloatArray(channelSize)
                channel.get(0, 0, buffer)
                System.arraycopy(buffer, 0, tensorData, channelIndex * channelSize, channelSize)
            }

            return DetPreprocessResult(
                tensorData = tensorData,
                shape = longArrayOf(1, 3, h.toLong(), w.toLong()),
                originalH = originalH,
                originalW = originalW,
            )
        } finally {
            channels.forEach { runCatching { it.release() } }
            floatMat?.release()
            resized?.release()
            convertedInput?.release()
        }
    }
}
