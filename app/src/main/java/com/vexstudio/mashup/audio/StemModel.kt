package com.vexstudio.mashup.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
enum class Stem { DRUMS, BASS, OTHER, VOCALS }

class Cancellation {
    @Volatile private var cancelled = false
    private var stopNative: (() -> Unit)? = null

    @Synchronized fun cancel() {
        cancelled = true
        stopNative?.invoke()
    }
    fun check() {
        if (cancelled) throw java.util.concurrent.CancellationException()
    }
    @Synchronized fun onNativeStop(callback: (() -> Unit)?) {
        checkIfStarting(callback)
        stopNative = callback
    }
    private fun checkIfStarting(callback: (() -> Unit)?) {
        if (callback != null) check()
    }
}

class ModelStore(root: File) {
    val file = File(root, "htdemucs-${SHA.take(12)}.onnx")
    val installed get() = file.isFile && file.length() == BYTES

    fun install(cancel: Cancellation, progress: (Long, Long) -> Unit) {
        file.parentFile?.mkdirs()
        require((file.parentFile?.usableSpace ?: 0) > BYTES + 32_000_000) { "STORAGE" }
        val part = File(file.parentFile, "download.partial")
        val connection = URL(URL).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 12_000
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            check(connection.responseCode == 200) { "DOWNLOAD" }
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        cancel.check()
                        val count = input.read(buffer)
                        if (count == -1) break
                        done += count
                        require(done <= BYTES) { "CHECKSUM" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress(done, BYTES)
                    }
                    require(done == BYTES && digest.digest().hex() == SHA) { "CHECKSUM" }
                }
            }
            cancel.check()
            check(part.renameTo(file)) { "STORAGE" }
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    fun verified(cancel: Cancellation): File {
        require(installed) { "MODEL_MISSING" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                cancel.check()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        require(digest.digest().hex() == SHA) { "CHECKSUM" }
        return file
    }

    companion object {
        const val SHA = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a"
        const val BYTES = 165612636L
        const val URL = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/d54ed9eb60e258ea82131c6ee14578628816456a/htdemucs_fp16weights.onnx"
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

class StemSeparator(model: File, threads: Int) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(false)
        environment.createSession(model.absolutePath, options)
    }

    init {
        try {
            val input = session.inputInfo["mix"]?.info as? TensorInfo
            val output = session.outputInfo["stems"]?.info as? TensorInfo
            require(input?.shape?.contentEquals(longArrayOf(1, 2, SEGMENT.toLong())) == true &&
                output?.shape?.contentEquals(longArrayOf(1, 4, 2, SEGMENT.toLong())) == true) { "MODEL_CONTRACT" }
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    fun separate(input: File, directory: File, cancel: Cancellation, progress: (Long, Long) -> Unit) {
        val overlap = SEGMENT / 4
        val stride = SEGMENT - overlap
        PcmReader(input).use { reader ->
            val chunks = 1 + ceil(max(0, reader.frames - SEGMENT).toDouble() / stride).toInt()
            val output = mutableListOf<PcmWriter>()
            val tails = Array(Stem.entries.size) { FloatArray(overlap * 2) }
            val planar = ByteBuffer.allocateDirect(SEGMENT * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
            try {
                Stem.entries.forEach { output += PcmWriter(File(directory, "${it.name}.raw")) }
                OrtSession.RunOptions().use { options ->
                    cancel.onNativeStop { options.setTerminate(true) }
                    try {
                        repeat(chunks) { chunk ->
                            cancel.check()
                            val start = chunk * stride
                            val count = min(SEGMENT, reader.frames - start)
                            val stereo = reader.read(start, count)
                            planar.clear()
                            for (channel in 0..1) {
                                for (i in 0 until SEGMENT) planar.put(if (i < count) stereo[i * 2 + channel] else 0f)
                            }
                            planar.flip()
                            val framesToWrite = if (chunk == chunks - 1) count else stride
                            OnnxTensor.createTensor(environment, planar, longArrayOf(1, 2, SEGMENT.toLong())).use { tensor ->
                                session.run(mapOf("mix" to tensor), options).use { result ->
                                    val data = (result[0] as OnnxTensor).floatBuffer
                                    for (stem in Stem.entries.indices) {
                                        val base = stem * SEGMENT * 2
                                        val values = FloatArray(framesToWrite * 2)
                                        for (i in 0 until framesToWrite) {
                                            val blend = if (chunk > 0 && i < overlap) i.toFloat() / overlap else 1f
                                            for (channel in 0..1) {
                                                val value = data[base + channel * SEGMENT + i]
                                                check(value.isFinite()) { "MODEL_CONTRACT" }
                                                val old = if (blend < 1f) tails[stem][i * 2 + channel] else 0f
                                                values[i * 2 + channel] = value * blend + old * (1f - blend)
                                            }
                                        }
                                        output[stem].write(values)
                                        if (chunk < chunks - 1) {
                                            for (i in 0 until overlap) {
                                                for (channel in 0..1) {
                                                    tails[stem][i * 2 + channel] = data[base + channel * SEGMENT + stride + i]
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            progress((chunk + 1).toLong(), chunks.toLong())
                        }
                    } catch (failure: Exception) {
                        cancel.check()
                        throw failure
                    } finally {
                        cancel.onNativeStop(null)
                    }
                }
            } finally {
                output.forEach { it.close() }
            }
        }
    }

    override fun close() = session.close()

    companion object { const val SEGMENT = 343980 }
}
