package com.vexstudio.mashup.audio

import kotlin.math.*

class Fft(val size: Int) {
    private val bits = Integer.numberOfTrailingZeros(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    private val cosine = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { sin(-2 * PI * it / size) }

    fun transform(real: DoubleArray, imag: DoubleArray) {
        for (i in real.indices) {
            val j = reversed[i]
            if (i < j) {
                val r = real[i]
                val v = imag[i]
                real[i] = real[j]
                imag[i] = imag[j]
                real[j] = r
                imag[j] = v
            }
        }
        var width = 2
        while (width <= size) {
            val half = width / 2
            for (start in 0 until size step width) {
                for (i in 0 until half) {
                    val lo = start + i
                    val hi = lo + half
                    val index = i * size / width
                    val r = real[hi] * cosine[index] - imag[hi] * sine[index]
                    val v = real[hi] * sine[index] + imag[hi] * cosine[index]
                    real[hi] = real[lo] - r
                    imag[hi] = imag[lo] - v
                    real[lo] += r
                    imag[lo] += v
                }
            }
            width *= 2
        }
    }
}
