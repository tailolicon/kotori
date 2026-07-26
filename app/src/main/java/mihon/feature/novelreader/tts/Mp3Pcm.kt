package mihon.feature.novelreader.tts

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * Decodes one MP3 clip to the mono float PCM the sentence pipeline plays.
 *
 * Synchronous by design: the caller is the synthesis worker thread, which already exists to keep
 * slow work off the player, and a sentence clip is a few seconds of audio — small enough to decode
 * whole rather than stream.
 */
internal object Mp3Pcm {

    class DecodedPcm(val samples: FloatArray, val sampleRate: Int)

    fun decode(mp3: ByteArray): DecodedPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(ByteArraySource(mp3))
        check(extractor.trackCount > 0) { "No audio track in clip" }
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) { "Track has no mime type" }

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ArrayList<ShortArray>()

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder's real output layout arrives here, not in the input format.
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            val shorts = ShortArray(info.size / 2)
                            buffer.position(info.offset)
                            buffer.asShortBuffer().get(shorts)
                            pcm += shorts
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        return DecodedPcm(pcm.toMonoFloats(channels.coerceAtLeast(1)), sampleRate)
    }

    /** Interleaved 16-bit chunks to one mono float track, averaging channels if there are two. */
    private fun List<ShortArray>.toMonoFloats(channels: Int): FloatArray {
        val totalFrames = sumOf { it.size } / channels
        val mono = FloatArray(totalFrames)
        var frame = 0
        forEach { chunk ->
            var i = 0
            while (i + channels <= chunk.size) {
                var sum = 0f
                repeat(channels) { channel -> sum += chunk[i + channel] / 32768f }
                mono[frame++] = sum / channels
                i += channels
            }
        }
        return mono
    }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    private const val TIMEOUT_US = 10_000L
}
