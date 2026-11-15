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

package com.paddle.ocr.postprocess

object CTCDecoder {
    private const val BLANK_IDX = 0

    fun decode(output: FloatArray, shape: LongArray, characterList: List<String>): List<Pair<String, Float>> {
        require(shape.size == 3) { "Expected CTC output shape [batch,time,classes], got ${shape.contentToString()}" }
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()
        require(batchSize > 0 && timeSteps > 0 && numClasses > 1) {
            "Invalid CTC output dimensions: ${shape.contentToString()}"
        }
        require(numClasses == characterList.size + 1) {
            "CTC class count $numClasses does not match dictionary size ${characterList.size} + blank"
        }
        val expectedValues = batchSize.toLong() * timeSteps.toLong() * numClasses.toLong()
        require(expectedValues <= Int.MAX_VALUE && output.size == expectedValues.toInt()) {
            "CTC tensor size mismatch: expected $expectedValues floats, got ${output.size}"
        }
        val reverseArabic = characterList.any { token -> token.any { it.code in 0x0600..0x06FF } }

        val results = mutableListOf<Pair<String, Float>>()
        for (b in 0 until batchSize) {
            val baseOffset = b * timeSteps * numClasses

            val indices = IntArray(timeSteps)
            val probs = FloatArray(timeSteps)
            for (t in 0 until timeSteps) {
                val offset = baseOffset + t * numClasses
                var maxIdx = 0
                var maxVal = output[offset]
                for (c in 1 until numClasses) {
                    val v = output[offset + c]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                indices[t] = maxIdx
                probs[t] = maxVal
            }

            val keptProbs = mutableListOf<Float>()
            val sb = StringBuilder()
            var prevIdx = -1
            for (t in 0 until timeSteps) {
                val idx = indices[t]
                if (idx != BLANK_IDX && idx != prevIdx) {
                    val charIdx = idx - 1
                    if (charIdx >= 0 && charIdx < characterList.size) {
                        sb.append(characterList[charIdx])
                        keptProbs.add(probs[t])
                    }
                }
                prevIdx = idx
            }

            val decoded = sb.toString()
            val text = if (reverseArabic) reverseArabicVisualOrder(decoded) else decoded
            val confidence = if (keptProbs.isNotEmpty()) keptProbs.average().toFloat() else 0f
            results.add(Pair(text, confidence))
        }
        return results
    }

    /**
     * Paddle's Arabic recognizer emits glyphs in visual RTL order. Match its
     * official Arabic CTC post-processing: reverse Arabic runs while keeping
     * Latin/numeric runs internally readable.
     */
    internal fun reverseArabicVisualOrder(text: String): String {
        // English-only output must stay LTR even though the Arabic dictionary is loaded.
        if (text.none { it.code in 0x0600..0x06FF }) return text

        val runs = mutableListOf<String>()
        val latin = StringBuilder()

        fun flushLatin() {
            if (latin.isEmpty()) return
            val value = latin.toString()
            latin.clear()

            // Spaces at a script boundary are neutral. Keep spaces inside an English phrase
            // together, but detach edge spaces so reversing RTL runs does not glue
            // "abc مرحبا" into "abcمرحبا" (or leave a leading/trailing space).
            val leading = value.takeWhile { it.isWhitespace() }
            val trailing = value.takeLastWhile { it.isWhitespace() }
            val coreEnd = value.length - trailing.length
            val core = value.substring(leading.length, coreEnd)
            if (leading.isNotEmpty()) runs += leading
            if (core.isNotEmpty()) runs += core
            if (trailing.isNotEmpty()) runs += trailing
        }

        for (char in text) {
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                char in ":*./%+-" || char.isWhitespace()
            ) {
                latin.append(char)
            } else {
                flushLatin()
                runs += char.toString()
            }
        }
        flushLatin()
        return runs.asReversed().joinToString("").trim()
    }
}
