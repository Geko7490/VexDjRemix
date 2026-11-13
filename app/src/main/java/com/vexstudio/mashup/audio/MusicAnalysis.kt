package com.vexstudio.mashup.audio

import java.io.File
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class Tonality(val pitchClass: Int, val minor: Boolean, val confidence: Double) {
    fun label(): String = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[pitchClass] +
        if (minor) "m" else ""
}

@Serializable
data class Analysis(
    val duration: Double,
    val bpm: Double,
    val phase: Double,
    val downbeat: Double,
    val stability: Double,
    val key: Tonality?,
    val rmsDb: Double,
    val waveform: List<Float>,
    val energy: List<Float>,
    val onsetCount: Int,
    val silentSeconds: Double
)

class MusicAnalysis {
    fun analyze(file: File, check: () -> Unit = {}): Analysis = PcmReader(file).use { reader ->
        val duration = reader.frames.toDouble() / RATE
        val waveform = FloatArray(180)
        val energy = FloatArray(ceil(duration * 4).toInt().coerceAtLeast(1))
        val counts = IntArray(energy.size)
        val fft = Fft(4096)
        val hop = 512
        val window = DoubleArray(fft.size) { .5 - .5 * cos(2 * PI * it / fft.size) }
        val real = DoubleArray(fft.size)
        val imaginary = DoubleArray(fft.size)
        val previous = DoubleArray(fft.size / 2)
        val magnitudes = DoubleArray(previous.size)
        val chroma = DoubleArray(12)
        val flux = DoubleArray((reader.frames + hop - 1) / hop)
        val kick = DoubleArray(flux.size)
        var square = 0.0
        var silent = 0
        for (blockStart in 0 until reader.frames step BLOCK * 8) {
            check()
            val end = min(reader.frames, blockStart + BLOCK * 8)
            val audio = reader.read(blockStart, end - blockStart + fft.size)
            for (frame in blockStart until end step hop) {
                val offset = frame - blockStart
                val length = min(hop, end - frame)
                var power = 0.0
                for (i in 0 until length) {
                    val l = audio[(offset + i) * 2]
                    val r = audio[(offset + i) * 2 + 1]
                    val peak = max(abs(l), abs(r))
                    val bin = ((frame + i) * waveform.size.toLong() / reader.frames).toInt()
                    waveform[bin] = max(peak, waveform[bin])
                    power += (l.toDouble() * l + r.toDouble() * r) * .5
                }
                square += power
                if (power / length < 1e-7) silent += length
                val energyIndex = min(energy.lastIndex, frame / (RATE / 4))
                energy[energyIndex] += power.toFloat()
                counts[energyIndex] += length

                for (i in real.indices) {
                    val l = audio[(offset + i) * 2]
                    val r = audio[(offset + i) * 2 + 1]
                    real[i] = (l + r) * .5 * window[i]
                    imaginary[i] = 0.0
                }
                fft.transform(real, imaginary)
                var change = 0.0
                var low = 0.0
                for (bin in 3 until previous.size - 1) {
                    val magnitude = hypot(real[bin], imaginary[bin])
                    magnitudes[bin] = magnitude
                    val log = ln1p(magnitude)
                    val increase = max(0.0, log - previous[bin])
                    previous[bin] = log
                    if (bin < 16) low += increase
                    change += increase / sqrt(bin.toDouble())
                }
                if (frame / hop % 4 == 0 && power / length > 1e-7) {
                    for (bin in 5..375) {
                        val m = magnitudes[bin]
                        val left = magnitudes[bin - 1]
                        val right = magnitudes[bin + 1]
                        if (m <= left || m <= right || m < .08) continue
                        val delta = (.5 * (left - right) / (left - 2 * m + right)).coerceIn(-.5, .5)
                        val frequency = (bin + delta) * RATE / fft.size
                        val note = 69 + 12 * log2(frequency / 440)
                        val distance = abs(note - note.roundToInt())
                        if (distance < .42) chroma[Math.floorMod(note.roundToInt(), 12)] += sqrt(m) * (1 - distance)
                    }
                }
                flux[frame / hop] = change
                kick[frame / hop] = low
            }
        }
        for (i in energy.indices) energy[i] = sqrt(energy[i] / max(1, counts[i]))
        val beat = estimateBeat(flux, kick, hop.toDouble() / RATE)
        Analysis(duration, beat.bpm, beat.phase, beat.downbeat, beat.stability, estimateKey(chroma),
            10 * log10(max(1e-12, square / max(1, reader.frames))), waveform.toList(), energy.toList(),
            beat.onsets, silent.toDouble() / RATE)
    }

    private data class Beat(val bpm: Double, val phase: Double, val downbeat: Double, val stability: Double, val onsets: Int)

    private fun estimateBeat(flux: DoubleArray, kick: DoubleArray, step: Double): Beat {
        val average = flux.average().takeIf(Double::isFinite) ?: 0.0
        val onsets = flux.indices.filter { i ->
            i > 0 && i < flux.lastIndex && flux[i] > average * 1.45 && flux[i] > flux[i - 1] && flux[i] >= flux[i + 1]
        }
        val minLag = (60.0 / 180 / step).roundToInt()
        val maxLag = (60.0 / 68 / step).roundToInt()
        if (onsets.size < 5 || flux.size < maxLag * 3) return Beat(0.0, 0.0, 0.0, 0.0, onsets.size)
        val centered = DoubleArray(flux.size) { max(0.0, flux[it] - average * .72) }
        val norm = centered.sumOf { it * it }
        if (norm < 1e-7) return Beat(0.0, 0.0, 0.0, 0.0, onsets.size)
        fun correlation(lag: Int): Double {
            var sum = 0.0
            for (i in lag until centered.size) sum += centered[i] * centered[i - lag]
            return sum / norm
        }
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            val bpm = 60 / (lag * step)
            scores[lag] = (correlation(lag) + .35 * correlation(lag * 2)) * (1 - .10 * abs(ln(bpm / 112)))
        }
        val lag = (minLag..maxLag).maxBy { scores[it] }
        if (scores[lag] < .085) return Beat(0.0, 0.0, 0.0, 0.0, onsets.size)
        val curvature = scores[lag - 1] - 2 * scores[lag] + scores[lag + 1]
        val offset = if (curvature < -1e-9) (.5 * (scores[lag - 1] - scores[lag + 1]) / curvature).coerceIn(-.5, .5) else 0.0
        val provisional = lag + offset
        val histogram = DoubleArray(48)
        onsets.forEach { i ->
            val bin = ((i % provisional) / provisional * histogram.size).roundToInt() % histogram.size
            histogram[bin] += flux[i]
        }
        val phaseBin = histogram.indices.maxBy { i ->
            histogram[i] + .7 * histogram[(i + 1) % 48] + .7 * histogram[(i + 47) % 48]
        }
        val phase = phaseBin / 48.0 * provisional
        val aligned = onsets.mapNotNull { i ->
            val beat = ((i - phase) / provisional).roundToInt()
            if (abs(i - phase - beat * provisional) < provisional * .13) beat to i else null
        }.groupBy({ it.first }, { it.second }).map { (beat, hits) -> beat to hits.maxBy { flux[it] } }
        val meanBeat = aligned.map { it.first }.average()
        val meanOnset = aligned.map { it.second }.average()
        val variance = aligned.sumOf { (it.first - meanBeat).pow(2) }
        val refined = if (variance > 0) aligned.sumOf { (it.first - meanBeat) * (it.second - meanOnset) } / variance else provisional
        val period = refined.coerceIn(provisional - .65, provisional + .65)
        val refinedPhase = if (aligned.isEmpty()) phase else meanOnset - meanBeat * period
        val first = ((refinedPhase % period) + period) % period
        val accents = DoubleArray(4)
        val count = IntArray(4)
        for (i in 0 until (flux.size / period).toInt()) {
            val index = (first + i * period).roundToInt().coerceIn(kick.indices)
            val low = (-2..2).maxOf { kick[(index + it).coerceIn(kick.indices)] }
            accents[i % 4] += low
            count[i % 4]++
        }
        val barOffset = accents.indices.maxBy { accents[it] / max(1, count[it]) }
        val residual = if (aligned.isEmpty()) 1.0 else aligned.map { abs(it.second - refinedPhase - it.first * period) / period }.average()
        val stability = (scores[lag] * 1.3 + aligned.size.toDouble() / onsets.size * .35 - residual).coerceIn(0.0, 1.0)
        return Beat(60 / (period * step), max(0.0, first * step - .038), max(0.0, (first + barOffset * period) * step - .038), stability, onsets.size)
    }

    private fun estimateKey(chroma: DoubleArray): Tonality? {
        if (chroma.sum() < 1e-6) return null
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val centered = chroma.map { it - chroma.average() }
        val norm = sqrt(centered.sumOf { it * it })
        if (norm < 1e-6) return null
        val keys = (0..23).map { index ->
            val profile = if (index < 12) major else minor
            val shifted = profile.map { it - profile.average() }
            val denominator = sqrt(shifted.sumOf { it * it }) * norm
            val score = centered.indices.sumOf { shifted[Math.floorMod(it - index, 12)] * centered[it] } / denominator
            index to score
        }.sortedByDescending { it.second }
        val (index, score) = keys.first()
        if (score < .28) return null
        return Tonality(index % 12, index >= 12, ((score - keys[1].second) * 2.5 + score * .35).coerceIn(0.0, 1.0))
    }
}
