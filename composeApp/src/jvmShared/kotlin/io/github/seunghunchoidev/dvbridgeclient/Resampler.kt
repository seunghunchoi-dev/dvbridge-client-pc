// SPDX-License-Identifier: MIT
// Copyright (c) 2026 최승훈 (Choi Seunghun, 6K2KZR)
package io.github.seunghunchoidev.dvbridgeclient

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** 스트림용 표본율 변환 (창 함수 sinc 보간). PC판 resampler.cpp와 같은 방식. 마이크가 8kHz를 못 열 때 사용 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val cutoff = 0.5 * 0.9 * min(1.0, outRate.toDouble() / inRate)
    private val half = ceil(8.0 / (2.0 * cutoff)).toInt()
    private var buf = FloatArray(0)
    private var len = 0
    private var pos = 0.0

    val passthrough get() = inRate == outRate

    init { reset() }

    fun reset() {
        buf = FloatArray(half * 4 + 4096)
        len = half
        pos = half.toDouble()
    }

    fun process(input: FloatArray, n: Int, out: MutableList<Float>) {
        if (passthrough) { for (i in 0 until n) out.add(input[i]); return }
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(input, 0, buf, len, n)
        len += n
        while (pos + half < len) {
            val c = floor(pos).toInt()
            val frac = pos - c
            var acc = 0.0
            var wsum = 0.0
            for (k in -half + 1..half) {
                val x = k - frac
                val a = 2.0 * cutoff * x
                val s = if (abs(a) < 1e-9) 1.0 else sin(PI * a) / (PI * a)
                val w = 0.5 + 0.5 * cos(PI * x / half)
                val g = s * w
                acc += g * buf[c + k]
                wsum += g
            }
            out.add(if (wsum != 0.0) (acc / wsum).toFloat() else 0f)
            pos += step
        }
        val drop = floor(pos).toInt() - half
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            pos -= drop
        }
    }
}
