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

package com.paddle.ocr

data class PaddleOCRConfig(
    val detImgMode: String = "BGR",
    val detLimitSideLen: Int = 64,
    val detLimitType: String = "min",
    val detMaxSideLimit: Int = 4000,
    val detThresh: Float = 0.3f,
    val detBoxThresh: Float = 0.6f,
    val detUnclipRatio: Float = 1.5f,
    // Match PP-OCRv5 mobile detector's official max_candidates setting.
    val detMaxCandidates: Int = 1000,
    val detUseDilation: Boolean = false,
    val detScoreMode: String = "fast",
    val detBoxType: String = "quad",
    // Match PaddleOCR's standard drop-score policy: do not pass uncertain OCR text into translation.
    val recScoreThresh: Float = 0.5f,
    val recBatchSize: Int = 1,
)
