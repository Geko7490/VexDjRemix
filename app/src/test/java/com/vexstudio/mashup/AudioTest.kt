package com.vexstudio.mashup

import com.vexstudio.mashup.audio.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun findsDifferentTemposAndMajorKeys() {
        for ((bpm, root) in listOf(101 to 60, 128 to 67)) {
            val file = folder.newFile("$bpm.raw")
            signal(file, 13.0) { t ->
                val beat = t * bpm / 60
                val age = (beat - floor(beat)) * 60 / bpm
                val drum = .35 * exp(-age * 40) * sin(2 * PI * 80 * age)
                val chord = listOf(root to .095, root + 4 to .06, root + 7 to .055).sumOf { (note, gain) ->
                    gain * sin(2 * PI * 440 * 2.0.pow((note - 69) / 12.0) * t)
                }
                drum + chord
            }
            val result = MusicAnalysis().analyze(file)
            assertEquals(bpm.toDouble(), result.bpm, 2.0)
            assertEquals(root % 12, result.key?.pitchClass)
            assertFalse(result.key!!.minor)
            assertTrue(result.stability > .25)
            assertTrue(result.onsetCount > 15)
        }
    }

    @Test fun silenceAndCancellationAreExplicit() {
        val file = folder.newFile("silent.raw")
        signal(file, 4.0) { 0.0 }
        val silent = MusicAnalysis().analyze(file)
        assertEquals(0.0, silent.bpm, 0.0)
        assertNull(silent.key)
        assertEquals(4.0, silent.silentSeconds, .01)
        val cancel = Cancellation()
        var terminated = false
        cancel.onNativeStop { terminated = true }
        cancel.cancel()
        assertTrue(terminated)
        assertThrows(java.util.concurrent.CancellationException::class.java) { MusicAnalysis().analyze(file, cancel::check) }
    }

    @Test fun timeStretchAndPitchAreIndependent() {
        val tone = folder.newFile("tone.raw")
        signal(tone, 2.5) { .20 * sin(2 * PI * 440 * it) }
        for (pitch in listOf(0.0, 3.0)) {
            val transformed = File(folder.root, "$pitch.raw")
            TimeWarp.process(tone, transformed, Track("source", Stem.VOCALS, 0.0, 2.5, speed = 1.2, pitch = pitch)) {}
            PcmReader(transformed).use {
                assertEquals(2.5 / 1.2, it.frames.toDouble() / RATE, .002)
                val count = RATE
                val samples = it.read(8000, count)
                val crossings = (1 until count).count { i -> samples[2 * (i - 1)] <= 0f && samples[2 * i] > 0f }
                assertEquals(440 * 2.0.pow(pitch / 12), crossings.toDouble(), 4.0)
            }
        }
    }

    @Test fun plannerUsesSpecifiedSingerAndProducesDistinctModeSettings() {
        val a = source("a", 111.0, 0)
        val b = source("b", 123.0, 2)
        val choices = MashupPlanner().plan(a, b, Mode.A_ON_B)
        assertEquals(5, choices.size)
        assertTrue(choices.all { it.tracks.first().source == "a" && it.tracks[1].source == "b" })
        val first = choices.first()
        assertEquals(123.0 / 111.0, first.tracks.first().speed, .00001)
        assertEquals(2.0, first.tracks.first().pitch, .00001)
        val club = MashupPlanner().plan(a, b, Mode.CLUB).first()
        assertNotEquals(club.tracks.first { it.stem == Stem.BASS }, first.tracks.first { it.stem == Stem.BASS })
        assertThrows(IllegalArgumentException::class.java) { MashupPlanner().plan(a, source("c", 160.0, 6), Mode.AUTO) }
    }

    @Test fun renderedWavHasValidHeaderAndMutedMixIsSilent() {
        val tone = folder.newFile("input.raw")
        signal(tone, 1.1) { .70 * sin(2 * PI * 440 * it) }
        val track = Track("a", Stem.VOCALS, 0.0, 1.0, gain = 3f)
        val arrangement = Arrangement(Variant.BEST, 120.0, 90, Section.CHORUS, Section.VERSE, listOf(track))
        val mixer = Mixer({ tone }, { File(folder.root, "warp.raw") })
        val rendered = File(folder.root, "rendered.raw")
        val stats = mixer.render(arrangement, Mode.AUTO, rendered, {}) { _, _ -> }
        assertTrue(stats.overloads > 0)
        assertTrue(stats.peakDb < 0)
        val out = ByteArrayOutputStream()
        WavOutput.write(rendered, out, WavQuality(48000, 24), {})
        val wav = out.toByteArray()
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(48000, header.getInt(24))
        assertEquals(24, header.getShort(34).toInt())
        assertEquals(48000 * 6, header.getInt(40))
        assertEquals(header.getInt(40) + 44, wav.size)
        val muted = File(folder.root, "mute.raw")
        mixer.render(arrangement.copy(tracks = listOf(track.copy(mute = true))), Mode.AUTO, muted, {}) { _, _ -> }
        PcmReader(muted).use { assertTrue(it.read(0, 1024).all { sample -> sample == 0f }) }
    }

    private fun source(id: String, bpm: Double, root: Int): Source {
        val energy = List(160) { .17f + it % 13 * .008f }
        val analysis = Analysis(40.0, bpm, 0.0, 0.0, .85, Tonality(root, false, .8), -18.0,
            List(180) { .5f }, energy, 80, 0.0)
        return Source(id, id, analysis, Stem.entries.associateWith { StemStats(-22.0, energy, analysis.waveform) })
    }

    private fun signal(file: File, duration: Double, generate: (Double) -> Double) {
        val frames = (duration * RATE).roundToInt()
        PcmWriter(file).use { writer ->
            for (i in 0 until frames step BLOCK) {
                val count = min(BLOCK, frames - i)
                writer.write(FloatArray(count * 2) { sample -> generate((i + sample / 2).toDouble() / RATE).toFloat() })
            }
        }
    }
}
