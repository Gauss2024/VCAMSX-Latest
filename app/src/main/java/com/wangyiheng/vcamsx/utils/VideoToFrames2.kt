// VideoToFrames.kt
package com.wangyiheng.vcamsx.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook.Companion.TAG
import java.util.concurrent.atomic.AtomicBoolean

object VideoDecoder {
    private const val TIMEOUT_US = 10_000L
    private val isRunning = AtomicBoolean(false)
    private var mainHandler = Handler(Looper.getMainLooper())

    fun startDecoding(context: Context?, surface: Surface?) {
        if (context == null || surface == null) {
            HLog.d(TAG, "aaa Invalid decoding parameters")
            return
        }

        if (!isRunning.compareAndSet(false, true)) {
            HLog.d(TAG, "aaa Decoder already running")
            return
        }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, Uri.parse("content://com.wangyiheng.vcamsx.videoprovider"), null)
            }

            val trackIndex = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IllegalStateException("No video track found")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }

            var sawInputEOS = false
            var sawOutputEOS = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (isRunning.get() && !sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufferId = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val buffer = decoder.getInputBuffer(inputBufferId) ?: continue
                        val sampleSize = extractor.readSampleData(buffer, 0)

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferId, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferId, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        HLog.d(TAG, "aaa Output format changed: $newFormat")
                    }
                    else -> {
                        if (outputBufferId >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                            decoder.releaseOutputBuffer(outputBufferId, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HLog.d(TAG, "aaa Decoding failed: ${e.message}")
        } finally {
            mainHandler.post {
                decoder?.apply {
                    try {
                        stop()
                    } catch (e: IllegalStateException) {
                        HLog.d(TAG, "aaa Decoder stop error: ${e.message}")
                    }
                    release()
                }
                extractor?.release()
                isRunning.set(false)
                HLog.d(TAG, "aaa Decoder fully released")
            }
        }
    }

    fun stopDecoding() {
        isRunning.set(false)
    }
}