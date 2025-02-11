package com.wangyiheng.VirtuCam.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook
import com.wangyiheng.vcamsx.MainHook.Companion.TAG

class VideoDecoder(videoPath: String) {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var videoTrackIndex = -1
    private var frameInterval = 0L
    private var lastFrameTime = 0L
    init {
        extractor.setDataSource(videoPath)
        HLog.d(TAG,"aaa video init 00000 videoPath=$videoPath")
        // 查找视频轨道
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                videoTrackIndex = i
                break
            }
        }
        HLog.d(TAG,"aaa video init 1111")
        check(videoTrackIndex != -1) { "No video track found" }

        // 配置解码器
        val format = extractor.getTrackFormat(videoTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        decoder = MediaCodec.createDecoderByType(mime)
        HLog.d(TAG,"aaa video init 2222")
        // 强制输出YUV格式（Camera1通用格式）
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

        decoder.configure(format, null, null, 0)
        HLog.d(TAG,"aaa video init 333")
        decoder.start()
        extractor.selectTrack(videoTrackIndex)

        HLog.d(TAG,"aaa video init 完成")
    }
    fun getNextFrame(): ByteArray? {
        val currentTime = System.currentTimeMillis()
        //if (currentTime - lastFrameTime < frameInterval) return null

        while (true) {
            val inputBufferId = decoder.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferId)
                val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                if (sampleSize < 0) {
                    // 循环播放
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    continue
                }
                decoder.queueInputBuffer(
                    inputBufferId,
                    0,
                    sampleSize,
                    extractor.sampleTime,
                    0
                )
                extractor.advance()
            }
            val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            when {
                outputBufferId >= 0 -> {
                    // 计算帧间隔（按视频原始帧率）
                    frameInterval = (bufferInfo.presentationTimeUs / 1000).toLong()
                    lastFrameTime = currentTime

                    val outputBuffer = decoder.getOutputBuffer(outputBufferId)
                    val frameData = ByteArray(outputBuffer!!.remaining())
                    outputBuffer.get(frameData)
                    decoder.releaseOutputBuffer(outputBufferId, false)
                    HLog.d(MainHook.TAG, "aaa 000 解码成功，开始返回。。。。")
                    return frameData
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 处理格式变化
                }
            }
        }
    }
    fun release() {
        decoder.stop()
        decoder.release()
        extractor.release()
    }
}