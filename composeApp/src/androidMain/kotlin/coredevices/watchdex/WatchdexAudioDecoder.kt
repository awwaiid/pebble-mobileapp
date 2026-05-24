package coredevices.watchdex

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import co.touchlab.kermit.Logger
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the watchdex01 watch's AAC-in-MP4 recording into the same shape
 * RingSync passes around: 16-bit mono PCM as a [ShortArray] plus the sample
 * rate it was decoded at.
 *
 * The watch records AAC LC at 16 kHz mono / 64 kbps, so on the happy path
 * `sampleRate == 16000` and the resulting samples are already pipeline-ready
 * — no resampling needed before writing to `RecordingStorage`.
 */
internal object WatchdexAudioDecoder {

    private val logger = Logger.withTag("WatchdexAudioDecoder")

    data class Decoded(val samples: ShortArray, val sampleRate: Int)

    fun decodeFile(aacFile: File): Decoded? {
        val extractor = MediaExtractor()
        extractor.setDataSource(aacFile.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            logger.e { "no audio track in ${aacFile.name}" }
            return null
        }
        extractor.selectTrack(trackIndex)
        val inFormat = extractor.getTrackFormat(trackIndex)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        val out = ArrayList<Short>(sampleRate * 4)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset).limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            shorts.forEach { out.add(it) }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignored */ }
                }
            }
        } catch (e: Exception) {
            logger.e(e) { "decode failed for ${aacFile.name}" }
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }

        if (out.isEmpty()) {
            logger.w { "decoded 0 samples from ${aacFile.name}" }
            return null
        }
        return Decoded(samples = ShortArray(out.size) { out[it] }, sampleRate = sampleRate)
    }

    fun decodeBytes(aacBytes: ByteArray, cacheDir: File): Decoded? {
        val tmp = File(cacheDir, "watchdex-in-${System.nanoTime()}.m4a")
        return try {
            tmp.outputStream().use { it.write(aacBytes) }
            decodeFile(tmp)
        } finally {
            tmp.delete()
        }
    }
}
