package com.vexstudio.mashup.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class MediaInput(private val context: Context) {
    fun decode(uri: Uri, output: File, check: () -> Unit, progress: (Long, Long) -> Unit) {
        val extractor = MediaExtractor()
        val scratch = File(output.parentFile, "decode.raw")
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("NO_AUDIO")
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            require(duration <= 1_800_000_000L) { "TOO_LONG" }
            extractor.selectTrack(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = pcmEncoding(format)
            require(channels in 1..8 && rate in 8000..192000) { "FORMAT_CHANGED" }
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_RAW) {
                decodeRaw(extractor, scratch, rate, channels, encoding, duration, check, progress)
                finalizePcm(scratch, output, rate, check)
                return
            }
            var framesWritten = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                PcmWriter(scratch).use { writer ->
                    while (!outputDone) {
                        check()
                        if (!inputDone) {
                            val index = codec.dequeueInputBuffer(5_000)
                            if (index >= 0) {
                                val input = checkNotNull(codec.getInputBuffer(index))
                                val size = extractor.readSampleData(input, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "DRM" }
                                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val index = codec.dequeueOutputBuffer(info, 5_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val pcm = codec.outputFormat
                            val nextRate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            require(framesWritten == 0L || rate == nextRate) { "FORMAT_CHANGED" }
                            rate = nextRate
                            channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = pcmEncoding(pcm)
                            require(channels in 1..8 && rate in 8000..192000) { "FORMAT_CHANGED" }
                        }
                        if (index >= 0) {
                            try {
                                val data = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    framesWritten += writeStereo(data, channels, encoding, writer)
                                    require(framesWritten <= 1800L * rate) { "TOO_LONG" }
                                    progress(info.presentationTimeUs, duration)
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
            } finally {
                codec.release()
            }
            require(framesWritten > 0) { "NO_AUDIO" }
            finalizePcm(scratch, output, rate, check)
        } finally {
            extractor.release()
            scratch.delete()
        }
    }

    private fun decodeRaw(extractor: MediaExtractor, scratch: File, rate: Int, channels: Int, encoding: Int,
        duration: Long, check: () -> Unit, progress: (Long, Long) -> Unit) {
        var frames = 0L
        var buffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        PcmWriter(scratch).use { writer ->
            while (true) {
                check()
                val size = if (android.os.Build.VERSION.SDK_INT >= 28) extractor.sampleSize else -1L
                require(size <= 16 * 1024 * 1024) { "FORMAT_CHANGED" }
                if (size > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                }
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "DRM" }
                require(count <= buffer.capacity()) { "FORMAT_CHANGED" }
                buffer.position(0)
                buffer.limit(count)
                frames += writeStereo(buffer, channels, encoding, writer)
                require(frames <= 1800L * rate) { "TOO_LONG" }
                progress(extractor.sampleTime, duration)
                extractor.advance()
            }
        }
        require(frames > 0) { "NO_AUDIO" }
    }

    private fun finalizePcm(scratch: File, output: File, rate: Int, check: () -> Unit) {
        if (rate == RATE) check(scratch.renameTo(output)) { "STORAGE" }
        else resample(scratch, output, rate.toDouble() / RATE, check)
    }

    private fun writeStereo(data: ByteBuffer, channels: Int, encoding: Int, writer: PcmWriter): Int {
        val width = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> error("FORMAT_CHANGED")
        }
        val frames = data.remaining() / (channels * width)
        val stereo = FloatArray(min(BLOCK, frames) * 2)
        for (start in 0 until frames step BLOCK) {
            val count = min(BLOCK, frames - start)
            for (frame in 0 until count) {
                var mono = 0f
                for (channel in 0 until channels) {
                    val value = when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> data.float.takeIf(Float::isFinite) ?: 0f
                        AudioFormat.ENCODING_PCM_32BIT -> data.int / 2147483648f
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            val a = data.get().toInt() and 255
                            val b = data.get().toInt() and 255
                            (a or (b shl 8) or (data.get().toInt() shl 16)) / 8388608f
                        }
                        AudioFormat.ENCODING_PCM_8BIT -> ((data.get().toInt() and 255) - 128) / 128f
                        else -> data.short / 32768f
                    }
                    if (channel < 2) stereo[frame * 2 + channel] = value
                    mono += value
                }
                if (channels != 2) {
                    stereo[frame * 2] = mono / channels
                    stereo[frame * 2 + 1] = mono / channels
                }
            }
            writer.write(stereo, count)
        }
        return frames
    }

    private fun pcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT
}
