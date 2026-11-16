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

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class RecPreprocessResult(
    val tensorData: FloatArray,
    val shape: LongArray,
)

object RecPreprocessor {
    private const val FIXED_HEIGHT = 48
    private const val BASE_WIDTH = 320
    private const val MAX_IMG_W = 3200

    fun preprocessBatch(crops: List<Mat>): RecPreprocessResult {
        require(crops.isNotEmpty()) { "Recognition batch must not be empty" }
        require(crops.all { !it.empty() && it.rows() > 0 && it.cols() > 0 }) {
            "Recognition crops must all be non-empty"
        }
        require(crops.all { it.channels() == 3 }) {
            "Recognition crops must use three-channel BGR input"
        }

        // These Mats are created here; input crops remain owned by OCREngine.
        // Keep one cleanup list so failures at any later stage cannot leak native memory.
        val ownedMats = ArrayList<Mat>(crops.size * 3)
        try {
            val resizedMats = crops.map { crop ->
                val newW = RecResizePolicy.contentWidth(
                    sourceWidth = crop.cols(),
                    sourceHeight = crop.rows(),
                    targetHeight = FIXED_HEIGHT,
                    maxWidth = MAX_IMG_W
                )
                val dst = Mat()
                ownedMats += dst
                Imgproc.resize(
                    crop, dst,
                    Size(newW.toDouble(), FIXED_HEIGHT.toDouble()),
                    0.0, 0.0, Imgproc.INTER_LINEAR
                )
                dst
            }

            // Convert to float and normalize: (x / 255 - 0.5) / 0.5 = x / 127.5 - 1.
            val floatMats = resizedMats.map { mat ->
                val floatMat = Mat(mat.rows(), mat.cols(), CvType.CV_32FC3)
                ownedMats += floatMat
                mat.convertTo(floatMat, CvType.CV_32F)
                // Use Scalar with all 3 channels set — single-value Scalar only sets val[0]!
                Core.divide(floatMat, org.opencv.core.Scalar(127.5, 127.5, 127.5), floatMat)
                Core.subtract(floatMat, org.opencv.core.Scalar(1.0, 1.0, 1.0), floatMat)
                floatMat
            }

            // PP-OCRv5's Arabic recognizer uses a 320px base input width. Short crops
            // occupy their aspect-preserving prefix; remaining pixels are zero-padded.
            val maxW = RecResizePolicy.batchWidth(
                contentWidths = floatMats.map { it.cols() },
                baseWidth = BASE_WIDTH,
                maxWidth = MAX_IMG_W
            )
            val batchSize = floatMats.size
            val paddedMats = floatMats.map { mat ->
                if (mat.cols() == maxW) {
                    mat
                } else {
                    val padded = Mat(
                        FIXED_HEIGHT,
                        maxW,
                        CvType.CV_32FC3,
                        org.opencv.core.Scalar(0.0)
                    )
                    ownedMats += padded
                    val roi = padded.submat(0, FIXED_HEIGHT, 0, mat.cols())
                    try {
                        mat.copyTo(roi)
                    } finally {
                        roi.release()
                    }
                    padded
                }
            }

            val channelSize = Math.multiplyExact(FIXED_HEIGHT, maxW)
            val totalValues = Math.multiplyExact(Math.multiplyExact(batchSize, 3), channelSize)
            val tensorData = FloatArray(totalValues)
            for (batchIndex in 0 until batchSize) {
                val mat = paddedMats[batchIndex]
                val channels = mutableListOf<Mat>()
                try {
                    Core.split(mat, channels)
                    for (channelIndex in 0..2) {
                        val buffer = FloatArray(channelSize)
                        channels[channelIndex].get(0, 0, buffer)
                        System.arraycopy(
                            buffer,
                            0,
                            tensorData,
                            (batchIndex * 3 + channelIndex) * channelSize,
                            channelSize
                        )
                    }
                } finally {
                    channels.forEach { runCatching { it.release() } }
                }
            }

            return RecPreprocessResult(
                tensorData = tensorData,
                shape = longArrayOf(batchSize.toLong(), 3, FIXED_HEIGHT.toLong(), maxW.toLong()),
            )
        } finally {
            ownedMats.asReversed().forEach { runCatching { it.release() } }
        }
    }
}
