package com.vexstudio.mashup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vexstudio.mashup.audio.*
import com.vexstudio.mashup.studio.StudioFiles
import java.io.File
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidDecoderOpensExportedWave() {
        val dir = File(context.cacheDir, "codec-test").apply { mkdirs() }
        try {
            val input = signal(dir)
            val wave = File(dir, "original.wav")
            wave.outputStream().use { WavOutput.write(input, it, WavQuality(48000, 16), {}) }
            val decoded = File(dir, "decoded.raw")
            MediaInput(context).decode(Uri.fromFile(wave), decoded, {}) { _, _ -> }
            PcmReader(decoded).use { assertTrue(abs(RATE - it.frames) <= 4) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun onDeviceFourStemModel() {
        val dir = File(context.cacheDir, "onnx-test").apply { mkdirs() }
        try {
            val model = StudioFiles(context).models
            assumeTrue("Install HTDemucs in the app to run this hardware test", model.installed)
            val cancel = Cancellation()
            val input = signal(dir)
            StemSeparator(model.verified(cancel), 2).use { separator ->
                separator.separate(input, dir, cancel) { done, total -> assertTrue(done in 1..total) }
            }
            val channels = Stem.entries.map { stem ->
                val file = File(dir, "${stem.name}.raw")
                assertEquals(input.length(), file.length())
                PcmReader(file).use { reader ->
                    val data = reader.read(RATE / 4, 4096)
                    assertTrue(data.all { it.isFinite() })
                    data.sumOf { abs(it).toDouble() }
                }
            }
            assertTrue(channels.max() > .01)
            assertTrue(channels.distinct().size > 1)
        } finally { dir.deleteRecursively() }
    }

    private fun signal(dir: File): File = File(dir, "signal.raw").also { file ->
        PcmWriter(file).use { writer ->
            writer.write(FloatArray(RATE * 2) { sample -> (.14 * sin(2 * PI * 220 * (sample / 2) / RATE)).toFloat() })
        }
    }
}
