package com.vexstudio.mashup.audio

import java.io.File
import kotlin.math.*

/** Stereo-linked waveform similarity overlap-add. The two channels share every search offset. */
object TimeWarp {
    private const val HOP = 768
    private const val SEARCH = 352
    private val blend = FloatArray(HOP) { (.5 - .5 * cos(PI * it / HOP)).toFloat() }

    fun process(input: File, target: File, track: Track, check: () -> Unit) {
        val start = (track.start * RATE).roundToInt()
        val length = (track.length * RATE).roundToInt()
        val pitch = 2.0.pow(track.pitch / 12)
        val temp = File(target.parentFile, "${target.name}.stretch")
        try {
            PcmReader(input).use { reader ->
                if (abs(track.speed - 1) < 1e-6 && abs(track.pitch) < 1e-6) {
                    PcmWriter(target).use { writer ->
                        for (frame in 0 until length step BLOCK) {
                            check()
                            writer.write(reader.read(start + frame, min(BLOCK, length - frame)))
                        }
                    }
                    return
                }
                val step = track.speed / pitch
                val frames = (length / step).roundToInt()
                PcmWriter(if (abs(track.pitch) < 1e-6) target else temp).use { writer ->
                    var tail = FloatArray(HOP * 2)
                    for (out in 0 until frames step HOP) {
                        check()
                        val expected = (out * step).roundToInt()
                        val lower = max(0, expected - SEARCH)
                        val upper = min(max(0, length - 1), expected + SEARCH)
                        val source = reader.read(start + lower, max(0, upper - lower) + HOP * 2)
                        val cropEnd = ((length - lower) * 2).coerceIn(0, source.size)
                        source.fill(0f, cropEnd)
                        val offset = if (out == 0 || lower >= upper) 0 else bestOffset(source, tail, upper - lower, expected - lower)
                        val count = min(HOP, frames - out)
                        val values = FloatArray(count * 2)
                        for (i in values.indices) {
                            val weight = if (out == 0) 1f else blend[i / 2]
                            values[i] = source[offset * 2 + i] * weight + tail[i] * (1f - weight)
                        }
                        writer.write(values)
                        tail = source.copyOfRange((offset + HOP) * 2, (offset + 2 * HOP) * 2)
                    }
                }
            }
            if (temp.isFile) resample(temp, target, pitch, check)
        } finally {
            temp.delete()
        }
    }

    private fun bestOffset(source: FloatArray, tail: FloatArray, maxOffset: Int, expected: Int): Int {
        var best = expected.coerceIn(0, maxOffset)
        var score = Double.NEGATIVE_INFINITY
        for (offset in 0..maxOffset step 4) {
            var dot = 0.0
            var a2 = 1e-9
            var b2 = 1e-9
            for (i in 0 until HOP step 6) {
                val a = (tail[i * 2] + tail[i * 2 + 1]) * .5
                val start = (offset + i) * 2
                val b = (source[start] + source[start + 1]) * .5
                dot += a * b
                a2 += a * a
                b2 += b * b
            }
            val similarity = dot / sqrt(a2 * b2) - .09 * abs(offset - expected) / SEARCH
            if (similarity > score) {
                best = offset
                score = similarity
            }
        }
        return best
    }
}
