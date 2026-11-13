package com.vexstudio.mashup.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

const val RATE = 44100
const val BLOCK = 4096

class PcmReader(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private var bytes = ByteArray(0)
    val frames = (input.length() / 8).toInt()

    fun read(start: Int, count: Int): FloatArray {
        val result = FloatArray(count * 2)
        val first = start.coerceIn(0, frames)
        val last = (start + count).coerceIn(0, frames)
        if (last <= first) return result
        val size = (last - first) * 8
        if (bytes.size < size) bytes = ByteArray(size)
        input.seek(first * 8L)
        input.readFully(bytes, 0, size)
        ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            .get(result, (first - start) * 2, (last - first) * 2)
        return result
    }

    override fun close() = input.close()
}

class PcmWriter(file: File) : Closeable {
    private val output = file.outputStream().buffered()
    private var bytes = ByteBuffer.allocate(0)
    fun write(values: FloatArray, frames: Int = values.size / 2) {
        val size = frames * 8
        if (bytes.capacity() < size) bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asFloatBuffer().put(values, 0, frames * 2)
        output.write(bytes.array(), 0, size)
    }

    override fun close() = output.close()
}

/** Windowed sinc with quantized phases. The cutoff follows the conversion ratio. */
class ResamplingKernel(private val step: Double) {
    private val radius = 12
    private val phases = 128
    private val taps = Array(phases) { phase ->
        val cutoff = min(1.0, 1.0 / step) * .94
        DoubleArray(radius * 2) { index ->
            val x = index - radius + 1 - phase.toDouble() / phases
            val argument = PI * cutoff * x
            val sinc = if (abs(argument) < 1e-8) 1.0 else sin(argument) / argument
            val window = if (abs(x) < radius) .5 + .5 * cos(PI * x / radius) else 0.0
            cutoff * sinc * window
        }.let { weights -> val sum = weights.sum(); FloatArray(weights.size) { (weights[it] / sum).toFloat() } }
    }

    fun sample(buffer: FloatArray, position: Double, channel: Int): Float {
        val frame = floor(position).toInt()
        val weights = taps[((position - frame) * phases).toInt().coerceIn(0, phases - 1)]
        var sum = 0f
        for (i in weights.indices) {
            val index = (frame - radius + 1 + i).coerceIn(0, buffer.size / 2 - 1)
            sum += buffer[index * 2 + channel] * weights[i]
        }
        return sum
    }
}

fun resample(input: File, output: File, step: Double, check: () -> Unit) {
    require(step.isFinite() && step > 0)
    PcmReader(input).use { reader ->
        PcmWriter(output).use { writer ->
            val frames = (reader.frames / step).roundToInt()
            val kernel = ResamplingKernel(step)
            for (start in 0 until frames step BLOCK) {
                check()
                val count = min(BLOCK, frames - start)
                val first = floor(start * step).toInt() - 12
                val last = ceil((start + count) * step).toInt() + 12
                val source = reader.read(first, last - first)
                val values = FloatArray(count * 2) { i -> kernel.sample(source, (start + i / 2) * step - first, i % 2) }
                writer.write(values)
            }
        }
    }
}
