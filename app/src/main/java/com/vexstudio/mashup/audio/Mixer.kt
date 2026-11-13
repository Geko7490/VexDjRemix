package com.vexstudio.mashup.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random

data class RenderStats(val rmsDb: Double, val peakDb: Double, val overloads: Long)

data class WavQuality(val rate: Int, val bits: Int) {
    init { require(rate in listOf(44100, 48000) && bits in listOf(16, 24)) }
}

object WavOutput {
    fun write(pcm: File, output: OutputStream, quality: WavQuality, check: () -> Unit, progress: (Long, Long) -> Unit = { _, _ -> }) {
        PcmReader(pcm).use { reader ->
            val ratio = RATE.toDouble() / quality.rate
            val frames = (reader.frames / ratio).roundToInt()
            val bytesPerFrame = quality.bits / 4
            val data = frames.toLong() * bytesPerFrame
            require(data <= 0xffff_ffffL - 36)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVEfmt ".toByteArray())
            header.putInt(16).putShort(1).putShort(2).putInt(quality.rate)
            header.putInt(quality.rate * bytesPerFrame).putShort(bytesPerFrame.toShort()).putShort(quality.bits.toShort())
            header.put("data".toByteArray()).putInt(data.toInt())
            output.write(header.array())
            val kernel = ResamplingKernel(ratio)
            val quantizer = 1 shl (quality.bits - 1)
            val random = Random(0x564558)
            for (start in 0 until frames step BLOCK) {
                check()
                val count = min(BLOCK, frames - start)
                val origin = if (ratio == 1.0) start else floor(start * ratio).toInt() - 12
                val source = reader.read(origin, if (ratio == 1.0) count else ceil((start + count) * ratio).toInt() + 12 - origin)
                val bytes = ByteArray(count * bytesPerFrame)
                for (i in 0 until count * 2) {
                    val value = if (ratio == 1.0) source[i] else kernel.sample(source, (start + i / 2) * ratio - origin, i % 2)
                    val dither = random.nextFloat() - random.nextFloat()
                    val sample = (value.coerceIn(-.985f, .985f) * quantizer + dither).roundToInt()
                        .coerceIn(-quantizer, quantizer - 1)
                    val at = i * quality.bits / 8
                    bytes[at] = sample.toByte()
                    bytes[at + 1] = (sample shr 8).toByte()
                    if (quality.bits == 24) bytes[at + 2] = (sample shr 16).toByte()
                }
                output.write(bytes)
                progress((start + count).toLong(), frames.toLong())
            }
        }
    }
}

class Mixer(private val stems: (Track) -> File, private val warps: (Track) -> File) {
    fun render(arrangement: Arrangement, mode: Mode, out: File, check: () -> Unit, progress: (Long, Long) -> Unit): RenderStats {
        val tracks = arrangement.tracks.filter { !it.mute && (!arrangement.tracks.any { t -> t.solo } || it.solo) }
        val frames = arrangement.tracks.maxOf { ((it.length / it.speed + it.offset) * RATE).roundToInt() }.coerceAtLeast(1)
        val processors = mutableListOf<Channel>()
        val raw = File(out.parentFile, "${out.name}.premaster")
        var power = 0.0
        var overload = 0L
        try {
            for (track in tracks) {
                check()
                val warp = warps(track)
                if (!warp.exists()) {
                    val partial = File(warp.parentFile, "${warp.name}.partial")
                    try {
                        TimeWarp.process(stems(track), partial, track, check)
                        check(partial.renameTo(warp)) { "STORAGE" }
                    } finally {
                        partial.delete()
                    }
                }
                processors += Channel(track, warp, arrangement.bpm)
            }
            val ordered = processors.sortedBy { it.track.stem != Stem.VOCALS }
            val beat = 60 / arrangement.bpm
            var vocalEnvelope = 0f
            PcmWriter(raw).use { writer ->
                for (start in 0 until frames step BLOCK) {
                    check()
                    val count = min(BLOCK, frames - start)
                    val values = FloatArray(count * 2)
                    val voice = FloatArray(count)
                    for (channel in ordered) {
                        val source = channel.reader.read(start - channel.offset, count)
                        for (i in 0 until count) {
                            val position = start + i
                            val local = position - channel.offset
                            if (local < 0 || local >= channel.reader.frames) continue
                            val time = position.toDouble() / RATE
                            val phase = time % beat / beat
                            val fadeDuration = if (mode == Mode.SMOOTH) .24 else .035
                            val fade = min(1.0, min(local, channel.reader.frames - 1 - local) / (RATE * fadeDuration)).toFloat()
                            val automation = when {
                                channel.track.stem == Stem.BASS && mode == Mode.CLUB -> (.68 + .32 * min(1.0, phase * 4)).toFloat()
                                channel.track.stem == Stem.OTHER && mode == Mode.CREATIVE && time % (8 * beat) > 6 * beat ->
                                    if ((phase * 4).toInt() % 2 == 0) 1f else .25f
                                channel.track.stem == Stem.OTHER -> 1f - voice[i] * .26f
                                else -> 1f
                            }
                            val dryLeft = source[i * 2] * fade * automation
                            val dryRight = source[i * 2 + 1] * fade * automation
                            val l = channel.apply(dryLeft, 0) * channel.left
                            val r = channel.apply(dryRight, 1) * channel.right
                            values[i * 2] += l
                            values[i * 2 + 1] += r
                            if (channel.track.stem == Stem.VOCALS) {
                                val level = min(1f, max(abs(l), abs(r)) * 6f)
                                vocalEnvelope += (level - vocalEnvelope) * (if (level > vocalEnvelope) .012f else .0004f)
                                voice[i] = vocalEnvelope
                            }
                        }
                    }
                    for (i in values.indices) {
                        val value = values[i]
                        power += value.toDouble() * value
                        if (abs(value) >= 1f) overload++
                        // Soft-knee style bus compression before the output normalization pass.
                        val amplitude = abs(value)
                        if (amplitude > .45f) values[i] = sign(value) * (.45f + (amplitude - .45f) * .36f)
                    }
                    writer.write(values)
                    progress((start + count).toLong(), frames * 2L)
                }
            }
            val target = when (mode) { Mode.CLUB -> -13.0; Mode.SMOOTH -> -17.0; else -> -15.0 }
            val rms = 10 * log10(max(1e-12, power / (frames * 2.0)))
            val scale = 10.0.pow((target - rms).coerceIn(-18.0, 12.0) / 20).toFloat()
            var outputPeak = 0f
            var outputPower = 0.0
            PcmReader(raw).use { reader ->
                PcmWriter(out).use { writer ->
                    var limiter = 1f
                    for (start in 0 until frames step BLOCK) {
                        check()
                        val count = min(BLOCK, frames - start)
                        val samples = reader.read(start, count)
                        for (i in 0 until count) {
                            val l = samples[2 * i] * scale
                            val r = samples[2 * i + 1] * scale
                            val required = min(1f, .94f / max(.001f, max(abs(l), abs(r))))
                            limiter = if (required < limiter) required else min(required, limiter + .00028f)
                            samples[2 * i] = l * limiter
                            samples[2 * i + 1] = r * limiter
                            outputPeak = max(outputPeak, max(abs(l), abs(r)) * limiter)
                            outputPower += (l.toDouble() * l + r.toDouble() * r) * limiter * limiter
                        }
                        writer.write(samples)
                        progress(frames + (start + count).toLong(), frames * 2L)
                    }
                }
            }
            return RenderStats(10 * log10(max(1e-12, outputPower / (frames * 2.0))),
                20 * log10(max(1e-6f, outputPeak).toDouble()), overload)
        } finally {
            processors.forEach { it.reader.close() }
            raw.delete()
        }
    }

    private class Channel(val track: Track, file: File, bpm: Double) {
        val reader = PcmReader(file)
        val offset = (track.offset * RATE).roundToInt()
        val left = track.gain * sqrt(1f - max(0f, track.pan))
        val right = track.gain * sqrt(1f + min(0f, track.pan))
        private val filters = List(2) {
            listOfNotNull(
                if (track.highPass > 0) Biquad(Filter.HIGH_PASS, track.highPass.toDouble()) else null,
                if (track.lowPass > 0) Biquad(Filter.LOW_PASS, track.lowPass.toDouble()) else null,
                if (track.low != 0f) Biquad(Filter.PEAK, 120.0, track.low.toDouble()) else null,
                if (track.mid != 0f) Biquad(Filter.PEAK, 1800.0, track.mid.toDouble()) else null,
                if (track.high != 0f) Biquad(Filter.PEAK, 7500.0, track.high.toDouble()) else null
            )
        }
        private val echoes = if (track.wet > 0) List(2) { index ->
            listOf(FloatArray((RATE * 60 / bpm * (.48 + index * .04)).roundToInt()), FloatArray(1579 + index * 362),
                FloatArray(2633 + index * 330))
        } else emptyList()
        private val positions = Array(2) { IntArray(3) }

        fun apply(value: Float, side: Int): Float {
            var sample = value
            for (filter in filters[side]) sample = filter.process(sample)
            if (echoes.isEmpty()) return sample
            var wet = 0f
            for ((i, delay) in echoes[side].withIndex()) {
                val index = positions[side][i]
                val delayed = delay[index]
                delay[index] = sample + delayed * if (i == 0) .32f else .52f
                positions[side][i] = (index + 1) % delay.size
                wet += delayed * if (i == 0) .62f else .19f
            }
            return sample + wet * track.wet
        }
    }
}

private enum class Filter { LOW_PASS, HIGH_PASS, PEAK }

private class Biquad(type: Filter, hz: Double, db: Double = 0.0) {
    private val omega = 2 * PI * hz / RATE
    private val cos = cos(omega)
    private val alpha = sin(omega) / (2 * .7071)
    private val a = 10.0.pow(db / 40)
    private val divisor = 1 + if (type == Filter.PEAK) alpha / a else alpha
    private val b0 = when (type) {
        Filter.LOW_PASS -> (1 - cos) / 2
        Filter.HIGH_PASS -> (1 + cos) / 2
        Filter.PEAK -> 1 + alpha * a
    } / divisor
    private val b1 = when (type) {
        Filter.LOW_PASS -> 1 - cos
        Filter.HIGH_PASS -> -1 - cos
        Filter.PEAK -> -2 * cos
    } / divisor
    private val b2 = if (type == Filter.PEAK) (1 - alpha * a) / divisor else b0
    private val a1 = -2 * cos / divisor
    private val a2 = (1 - if (type == Filter.PEAK) alpha / a else alpha) / divisor
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(value: Float): Float {
        val output = b0 * value + z1
        z1 = b1 * value - a1 * output + z2
        z2 = b2 * value - a2 * output
        return output.toFloat()
    }
}
