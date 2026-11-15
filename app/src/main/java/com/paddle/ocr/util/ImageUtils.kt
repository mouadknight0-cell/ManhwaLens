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

package com.paddle.ocr.util

import com.paddle.ocr.preprocess.DetResizeCalculator
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageUtils {

    fun resizeToMultipleOf32(
        src: Mat,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int,
    ): Mat {
        val dimensions = DetResizeCalculator.calculate(
            originalWidth = src.cols(),
            originalHeight = src.rows(),
            limitSideLen = limitSideLen,
            limitType = limitType,
            maxSideLimit = maxSideLimit
        )
        val dst = Mat()
        Imgproc.resize(
            src, dst,
            Size(dimensions.width.toDouble(), dimensions.height.toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR
        )
        return dst
    }
}
