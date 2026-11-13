package com.vexstudio.mashup.audio

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
enum class Mode { AUTO, A_ON_B, B_ON_A, HYBRID, CLUB, SMOOTH, CREATIVE }

@Serializable
enum class Variant { BEST, ENERGETIC, SMOOTH, VOCAL, INSTRUMENTAL }

@Serializable
enum class Section { INTRO, VERSE, CHORUS, BRIDGE, OUTRO }

@Serializable
data class StemStats(val rmsDb: Double, val energy: List<Float>, val waveform: List<Float>)

@Serializable
data class Source(val id: String, val title: String, val analysis: Analysis, val stems: Map<Stem, StemStats>)

@Serializable
data class Track(
    val source: String,
    val stem: Stem,
    val start: Double,
    val length: Double,
    val offset: Double = 0.0,
    val speed: Double = 1.0,
    val pitch: Double = 0.0,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val wet: Float = 0f,
    val highPass: Float = 0f,
    val lowPass: Float = 0f
)

@Serializable
data class Arrangement(
    val variant: Variant,
    val bpm: Double,
    val score: Int,
    val vocalSection: Section,
    val backingSection: Section,
    val tracks: List<Track>
)

@Serializable
data class Project(
    val id: String,
    val title: String,
    val created: Long,
    val mode: Mode,
    val a: Source,
    val b: Source,
    val arrangements: List<Arrangement>,
    val selected: Int = 0,
    val markers: List<Double> = emptyList()
) {
    val arrangement get() = arrangements[selected]
}

class MashupPlanner {
    private data class Phrase(val start: Double, val bars: Int, val vocal: Double, val energy: Double, val section: Section)
    private data class Candidate(
        val singer: Source, val backing: Source, val vocal: Phrase, val bed: Phrase,
        val tempo: Double, val speed: Double, val pitch: Int, val compatibility: Double
    )

    fun plan(a: Source, b: Source, mode: Mode): List<Arrangement> {
        require(a.analysis.bpm > 0 && b.analysis.bpm > 0) { "NO_BEAT" }
        require(min(a.analysis.stability, b.analysis.stability) >= .17) { "UNSTABLE" }
        val candidates = buildList {
            if (mode != Mode.B_ON_A) addAll(pair(a, b))
            if (mode != Mode.A_ON_B) addAll(pair(b, a))
        }
        require(candidates.isNotEmpty()) { "NO_MATCH" }
        val used = mutableSetOf<Candidate>()
        return Variant.entries.map { variant ->
            val best = candidates.maxBy { candidate ->
                val preference = when (variant) {
                    Variant.BEST -> 0.0
                    Variant.ENERGETIC -> .24 * candidate.bed.energy
                    Variant.SMOOTH -> .16 * (1 - candidate.bed.energy) - .18 * abs(ln(candidate.speed))
                    Variant.VOCAL -> .25 * candidate.vocal.vocal
                    Variant.INSTRUMENTAL -> .20 * (candidate.bed.energy + 1 - candidate.vocal.vocal)
                }
                candidate.compatibility + preference - if (candidate in used) .10 else 0.0
            }
            used += best
            arrangement(best, mode, variant)
        }
    }

    private fun pair(singer: Source, backing: Source): List<Candidate> {
        val tempo = backing.analysis.bpm
        val singerBpm = listOf(singer.analysis.bpm / 2, singer.analysis.bpm, singer.analysis.bpm * 2)
            .minBy { abs(ln(tempo / it)) }
        val speed = tempo / singerBpm
        if (speed !in .78..1.28) return emptyList()
        val (pitch, harmonic) = harmony(singer.analysis.key, backing.analysis.key)
        if (harmonic < .31) return emptyList()
        val vocals = singer.stems.getValue(Stem.VOCALS)
        if (vocals.rmsDb < -54) return emptyList()
        val tempoScore = (1 - abs(ln(speed)) * 2.4).coerceIn(0.0, 1.0)
        val stability = (singer.analysis.stability + backing.analysis.stability) * .5
        return phrases(singer, singerBpm).flatMap { v ->
            if (v.vocal < .12) return@flatMap emptyList()
            phrases(backing, tempo).mapNotNull { bed ->
                if (bed.energy < .10 || abs(v.bars - bed.bars) > 4) return@mapNotNull null
                val score = .31 * tempoScore + .27 * harmonic + .17 * stability + .17 * v.vocal +
                    .08 * (1 - abs(v.energy - bed.energy))
                if (score < .54) null else Candidate(singer, backing, v, bed, tempo, speed, pitch, score)
            }
        }
    }

    private fun harmony(vocal: Tonality?, bed: Tonality?): Pair<Int, Double> {
        if (vocal == null || bed == null || min(vocal.confidence, bed.confidence) < .2) return 0 to .54
        val v = if (vocal.minor) (vocal.pitchClass + 3) % 12 else vocal.pitchClass
        val b = if (bed.minor) (bed.pitchClass + 3) % 12 else bed.pitchClass
        return (-2..2).map { shift ->
            val distance = Math.floorMod(v + shift - b, 12)
            val harmonic = when (distance) {
                0 -> 1.0
                5, 7 -> .72
                else -> .22
            }
            shift to harmonic - .10 * abs(shift)
        }.maxBy { it.second }
    }

    private fun phrases(source: Source, bpm: Double): List<Phrase> {
        val analysis = source.analysis
        val bar = 240 / bpm
        val offset = analysis.downbeat % bar
        val total = floor((analysis.duration - offset) / bar).toInt()
        if (total < 2) return emptyList()
        val length = min(8, max(2, total / 2))
        val vocal = source.stems.getValue(Stem.VOCALS).energy
        val peak = analysis.energy.maxOrNull()?.coerceAtLeast(.0001f) ?: .0001f
        val vocalPeak = vocal.maxOrNull()?.coerceAtLeast(.0005f) ?: .0005f
        return (0..total - length step min(4, length)).map { position ->
            val start = offset + position * bar
            val end = start + length * bar
            val beginBin = (start * 4).toInt().coerceIn(0, vocal.lastIndex)
            val endBin = ceil(end * 4).toInt().coerceIn(beginBin + 1, vocal.size)
            val activity = vocal.subList(beginBin, endBin).count { it > max(.003f, vocalPeak * .13f) }.toDouble() / (endBin - beginBin)
            val energy = analysis.energy.subList(beginBin, endBin).average() / peak
            val section = when {
                position == 0 && activity < .48 -> Section.INTRO
                position + length >= total - 1 && activity < .48 -> Section.OUTRO
                energy > .42 && activity > .64 -> Section.CHORUS
                position.toDouble() / total > .61 && energy < .42 -> Section.BRIDGE
                else -> Section.VERSE
            }
            Phrase(start, length, activity, energy.coerceIn(0.0, 1.0), section)
        }
    }

    private fun arrangement(c: Candidate, mode: Mode, variant: Variant): Arrangement {
        val length = min(c.vocal.bars, c.bed.bars) * 240 / c.tempo
        fun gain(source: Source, stem: Stem, target: Double, minimum: Float): Float =
            10.0.pow((target - source.stems.getValue(stem).rmsDb) / 20).toFloat().coerceIn(minimum, 2.5f)
        val vocalGain = gain(c.singer, Stem.VOCALS, -20.0, .25f) * when (variant) {
            Variant.VOCAL -> 1.25f
            Variant.INSTRUMENTAL -> .63f
            else -> 1f
        }
        val rhythm = if (mode == Mode.HYBRID && c.singer.stems.getValue(Stem.DRUMS).rmsDb > -50) c.singer else c.backing
        val tracks = buildList {
            add(Track(c.singer.id, Stem.VOCALS, c.vocal.start, length * c.speed, speed = c.speed,
                pitch = c.pitch.toDouble(), gain = vocalGain, highPass = 90f,
                wet = when (mode) { Mode.CREATIVE -> .33f; Mode.SMOOTH -> .16f; Mode.CLUB -> .13f; else -> .07f }))
            add(Track(rhythm.id, Stem.DRUMS, if (rhythm == c.backing) c.bed.start else c.vocal.start,
                if (rhythm == c.backing) length else length * c.speed, speed = if (rhythm == c.backing) 1.0 else c.speed,
                gain = gain(rhythm, Stem.DRUMS, if (mode == Mode.CLUB || variant == Variant.ENERGETIC) -17.0 else -22.0, .18f),
                high = if (mode == Mode.SMOOTH) -2f else 1f, highPass = 32f))
            add(Track(c.backing.id, Stem.BASS, c.bed.start, length, gain = gain(c.backing, Stem.BASS,
                if (mode == Mode.CLUB) -19.0 else -23.0, .15f), low = if (mode == Mode.CLUB) 2f else 0f, highPass = 28f))
            add(Track(c.backing.id, Stem.OTHER, c.bed.start, length, gain = gain(c.backing, Stem.OTHER,
                if (variant == Variant.INSTRUMENTAL) -17.0 else -23.0, .16f), mid = -2f, highPass = 110f,
                lowPass = if (mode == Mode.SMOOTH || variant == Variant.SMOOTH) 9500f else 0f,
                wet = if (mode == Mode.CREATIVE) .26f else 0f))
        }
        return Arrangement(variant, c.tempo, (100 * c.compatibility).roundToInt(), c.vocal.section, c.bed.section, tracks)
    }
}
