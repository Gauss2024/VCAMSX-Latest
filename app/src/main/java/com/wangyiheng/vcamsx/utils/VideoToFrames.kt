package com.wangyiheng.vcamsx.utils

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook
import com.wangyiheng.vcamsx.MainHook.Companion
import com.wangyiheng.vcamsx.MainHook.Companion.context
import com.wangyiheng.vcamsx.MainHook.Companion.data_buffer
import com.wangyiheng.vcamsx.MainHook.Companion.hw_decode_obj
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

class VideoToFrames : Runnable {

    private var stopDecode = false

    private var outputImageFormat: OutputImageFormat? = null
    private var videoFilePath: Any? = null
    private var childThread: Thread? = null
    private var throwable: Throwable? = null // 定义 throwable 变量
    private val decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var play_surf: Surface? = null
    private val DEFAULT_TIMEOUT_US: Long = 10000
    private val callback: Callback? = null
    private val mQueue: LinkedBlockingQueue<ByteArray>? = null
    private val COLOR_FormatI420 = 1
    private val COLOR_FormatNV21 = 2
    private val VERBOSE = false
    fun stopDecode() {
        stopDecode = true
    }

    interface Callback {
        fun onFinishDecode()
        fun onDecodeFrame(index: Int)
    }


    @Throws(IOException::class)
    fun setSaveFrames(imageFormat: OutputImageFormat) {
        outputImageFormat = imageFormat
    }

    fun set_surface(player_surface:Surface){
        if(player_surface != null){
            play_surf = player_surface
        }
    }

    fun decode(videoFilePath: Any) {
        this.videoFilePath = videoFilePath
        if (childThread == null) {
            childThread = Thread(this, "decode").apply {
                start()
            }
            throwable?.let { throw it }
        }
    }

    override fun run() {
        try {
            Log.d("vcamsxtoast","------开始解码------")
            HLog.d(MainHook.TAG,"aaa 000  开始解码 videoFilePath= ${videoFilePath}")
            videoFilePath?.let { videoDecode(it) }
        } catch (t: Throwable) {
            throwable = t
            HLog.d(MainHook.TAG,"aaa 000  run ${t.toString()}")
        }
    }

    private fun videoDecode(videoPath: Any) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        HLog.d(MainHook.TAG, "aaa 000 000 开始 videoDecode videoPath=${videoPath}")

        try {
            extractor = MediaExtractor().apply {
                when (videoPath) {
                    is String -> setDataSource(videoPath) // 当参数是 String 时
                    is Uri -> context?.let { setDataSource(it, videoPath, null) } // 当参数是 Uri 时
                    else -> throw IllegalArgumentException("Unsupported video path type")
                }
            }
            HLog.d(MainHook.TAG, "aaa 000 111 videoFilePath=${videoPath}")
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                HLog.d(MainHook.TAG, "aaa 000  No video track found in ${videoFilePath}")
                XposedBridge.log("&#8203;``【oaicite:5】``&#8203;&#8203;``【oaicite:4】``&#8203;No video track found in $videoFilePath")
            }
            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            HLog.d(MainHook.TAG, "aaa 000  mediaFormat=${mediaFormat} .....")
            //HLog.d(MainHook.TAG, "aaa 000  🟡 解码器颜色格式: ${mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)}")
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
            // 确保解码器在启动前配置完成
// 检查 `mime`
            if (!isMimeSupported(mime!!)) {
                HLog.d(MainHook.TAG, "aaa 000 decoder 创建失败：mime=${mime} 不受支持")
            } else {
                HLog.d(MainHook.TAG, "aaa 000 decoder 创建 mime=${mime}，准备创建解码器")
            }
            // 释放之前的 `decoder`
            if (decoder != null) {
                HLog.d(MainHook.TAG, "aaa 000 释放旧的解码器")
                decoder?.stop()
                decoder?.release()
                decoder = null
            }

            decoder = MediaCodec.createDecoderByType(mime!!)
            if (decoder == null) {
                HLog.d(
                    MainHook.TAG,
                    "aaa 000 decoder 创建 失败 Decoder creation failed, returned null."
                )

            }
            HLog.d(MainHook.TAG, "aaa 000 decoder 创建成功 mime=${mime}, 解码器状态=${decoder?.codecInfo?.name}")
            val decoderCapabilities = decoder.getCodecInfo().getCapabilitiesForType(mime)
            if (decoderCapabilities != null) {
                // 确认设备支持硬件解码
                HLog.d(MainHook.TAG, "aaa 000 Hardware decoder supported")
            } else {
                // 如果硬件解码不可用，尝试软件解码
                HLog.d(MainHook.TAG, "aaa 000 Switching to software decoder")
            }
//            HLog.d(
//                MainHook.TAG,
//                "aaa 000 222 videodecode 的时候解码器输出帧大小: ${data_buffer?.size ?: 0}, 格式: ${decoder?.outputFormat}"
//            )
            HLog.d(MainHook.TAG, "aaa 000 showSupportedColorFormat before.....")
            showSupportedColorFormat(decoder.codecInfo.getCapabilitiesForType(mime))
            if (isColorFormatSupported(
                    decodeColorFormat,
                    decoder.codecInfo.getCapabilitiesForType(mime)
                )
            ) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
                HLog.d(
                    MainHook.TAG,
                    "aaa 000  333 set decode color format to type ${decodeColorFormat}"
                )
            } else {
                HLog.d(
                    MainHook.TAG,
                    "aaa 000  444 unable to set decode color format, color format type ${decodeColorFormat} not supported"
                )
            }
            decodeFramesToImage(decoder, extractor, mediaFormat)
            decoder?.stop()
            while (!stopDecode) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decodeFramesToImage(decoder, extractor, mediaFormat)
                decoder.stop()
            }


        } catch (e: IOException) {
            HLog.d(
                MainHook.TAG,
                "aaa 000 decoder 创建 失败 IOException during createDecoderByType: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            HLog.d(
                MainHook.TAG,
                "aaa 000 decoder 创建 失败 IllegalArgumentException during createDecoderByType: ${e.message}"
            )
        } catch (e: Exception) {
            HLog.d(
                MainHook.TAG,
                "aaa 000 decoder 创建 失败 Unexpected error during createDecoderByType: ${e.message}"
            )
        } finally {
            if (decoder != null) {
                decoder.stop()
                decoder.release()
                decoder = null
            }
            if (extractor != null) {
                extractor.release()
                extractor = null
            }
        }
    }
    private fun isMimeSupported(mime: String): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) {
                for (type in codecInfo.supportedTypes) {
                    if (type.equals(mime, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
    private fun selectTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    private fun showSupportedColorFormat(caps: MediaCodecInfo.CodecCapabilities) {
        for (c in caps.colorFormats) {
            print("$c\t")
        }
        println()
    }

    fun isColorFormatSupported(colorFormat: Int, caps: MediaCodecInfo.CodecCapabilities): Boolean {
        return caps.colorFormats.any { it == colorFormat }
    }

    private fun decodeFramesToImage(decoder: MediaCodec, extractor: MediaExtractor, mediaFormat: MediaFormat) {
        HLog.d(MainHook.TAG, "aaa 000  222 decodeFramesToImage start.......")

        val info = MediaCodec.BufferInfo()

        try {

            try {
                decoder.configure(mediaFormat, play_surf, null, 0)
                decoder.start()
            } catch (e: IllegalArgumentException) {
                HLog.d(MainHook.TAG, "aaa 000 IllegalArgumentException: ${e.message}")

            } catch (e: IllegalStateException) {
                HLog.d(MainHook.TAG, "aaa 000 IllegalStateException: ${e.message}")

            } catch (e: Exception) {
                HLog.d(MainHook.TAG, "aaa 000 Unknown error during configure/start: ${e.message}")

            }


            HLog.d(MainHook.TAG, "aaa 000 decodeFramesToImage Decoder started")

            var sawInputEOS = false
            var sawOutputEOS = false
            var outputFrameCount = 0
            var isFirst = false
            var startWhen: Long = 0

            while (!sawOutputEOS && !stopDecode) {
                // 3. 处理输入
                if (!sawInputEOS) {
                    val inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
                    if (inputBufferId >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferId)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // 4. 处理输出
                val outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
                if (outputBufferId >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }

                    val doRender = info.size != 0
                    if (doRender) {
                        outputFrameCount++
                        callback?.onDecodeFrame(outputFrameCount)
                        if (!isFirst) {
                            startWhen = System.currentTimeMillis()
                            isFirst = true
                        }

                        if (play_surf == null) {
                            val image = decoder.getOutputImage(outputBufferId)
                            val buffer = image!!.planes[0].buffer
                            val arr = ByteArray(buffer.remaining())
                            buffer.get(arr)
                            mQueue?.put(arr)

                            if (outputImageFormat != null) {
                                //MainHook.data_buffer = getDataFromImage(image)
                                MainHook.data_buffer = yuv420888ToNV21(image)

                            }
                            image.close()
                        }

                        val sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen)
                        if (sleepTime > 0) {
                            try {
                                Thread.sleep(sleepTime)
                            } catch (e: InterruptedException) {
                                XposedBridge.log("Thread sleep error: ${e.toString()}")
                                HLog.d(MainHook.TAG, "aaa 000  222 decodeFramesToImage sleep error ${e.toString()}")
                            }
                        }

                        decoder.releaseOutputBuffer(outputBufferId, true)
                    }
                } else {
                    // Log if dequeueOutputBuffer() fails
                    if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        HLog.d(MainHook.TAG, "aaa 000 decodeFramesToImage Output format changed: decoder.outputFormat=${decoder.outputFormat}")
                    } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        HLog.d(MainHook.TAG, "aaa 000 decodeFramesToImage No output buffer available, try again later.")
                    }
                }
            }

            callback?.onFinishDecode()
        } catch (e: Exception) {
            // 捕捉并记录错误
            HLog.d(MainHook.TAG, "aaa 000  decodeFramesToImage Error during decoding: ${e.message}")

        } finally {
            // 确保释放解码器
            decoder.stop()
            decoder.release()
            HLog.d(MainHook.TAG, "aaa 000 decodeFramesToImage Decoder stopped and released")
        }
    }



    fun logImageFormat(image: Image) {
        val format = image.format
        val formatString = when (format) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.NV21 -> "NV21"
            ImageFormat.YV12 -> "YV12"
            ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.RAW12 -> "RAW12"
            ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
            ImageFormat.DEPTH16 -> "DEPTH16"
            ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
            // 添加更多格式根据需要
            else -> "Unknown format: $format"
        }
       // HLog.d(MainHook.TAG, "aaa 000  logImageFormat, Image format=${formatString}")
    }

    fun imageToBitmap(image: Image): Bitmap {
        HLog.d(MainHook.TAG, "aaa 000  imageToBitmap, image.format=${image.format.toString()}")
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // YUV_420_888数据转NV21
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

//    fun bitmapToByteArray(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
//        val stream = ByteArrayOutputStream()
//        bitmap.compress(format, quality, stream)
//        return stream.toByteArray()
//    }

    fun bitmapToYUV(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val intArray = IntArray(width * height)
        bitmap.getPixels(intArray, 0, width, 0, 0, width, height)

        val yuvArray = ByteArray(width * height * 3)

        var index = 0
        intArray.forEach { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Apply the RGB to YUV formula
            val y = (0.257 * r) + (0.504 * g) + (0.098 * b) + 16
            val u = -(0.148 * r) - (0.291 * g) + (0.439 * b) + 128
            val v = (0.439 * r) - (0.368 * g) - (0.071 * b) + 128

            // Assuming the YUV format is YUV444, store each Y, U, and V value sequentially
            yuvArray[index++] = y.toInt().toByte()
            yuvArray[index++] = u.toInt().toByte()
            yuvArray[index++] = v.toInt().toByte()
        }

        return yuvArray
    }

    fun yuv420888ToNV21(image: Image): ByteArray {
        HLog.d(MainHook.TAG, "aaa 000 yuv420888ToNV21 解码输出: width=${image.width}, height=${image.height}, format=${image.format}")
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2

        val yuv = ByteArray(ySize + uvSize)
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        // 复制 Y 平面
        yBuffer.get(yuv, 0, ySize)

        // 交换 UV 顺序
        val uRowStride = image.planes[1].rowStride
        val vRowStride = image.planes[2].rowStride
        val pixelStride = image.planes[1].pixelStride

        var uvIndex = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uRowStride + col * pixelStride
                val vIndex = row * vRowStride + col * pixelStride
                yuv[uvIndex++] = vBuffer.get(vIndex) // V
                yuv[uvIndex++] = uBuffer.get(uIndex) // U
            }
        }
        return yuv
    }


    private fun getDataFromImage(image: Image): ByteArray {

        HLog.d(MainHook.TAG, "aaa 000 🟡 解码输出: width=${image.width}, height=${image.height}, format=${image.format}")

        val displayMetrics = context?.resources?.displayMetrics
        val screenWidth = displayMetrics?.widthPixels ?: 0
        val screenHeight = displayMetrics?.heightPixels ?: 0
        HLog.d(MainHook.TAG, "aaa 000 🟡 Surface 分辨率: width=${screenWidth}, height=${screenHeight}")


        logImageFormat(image)

        if (!isImageFormatSupported(image)) {
            HLog.d(MainHook.TAG, "aaa 000 can't convert Image to byte array, format ${image.format}")
            throw RuntimeException("can't convert Image to byte array, format ${image.format}")
        }

        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes

        val ySize = width * height
        val uvSize = ySize / 2
        val data = ByteArray(ySize + uvSize)  // NV21 格式存储

        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val rowStrideY = planes[0].rowStride
        val rowStrideUV = planes[1].rowStride
        val pixelStrideUV = planes[1].pixelStride

        // ✅ **拷贝 Y 通道数据**
        for (row in 0 until height) {
            yBuffer.position(row * rowStrideY)
            yBuffer.get(data, row * width, width)  // 每行拷贝
        }

        // ✅ **拷贝 U/V 数据，并确保 NV21 格式 (V 在前，U 在后)**
        val uvStart = ySize
        var uvPos = 0

        for (row in 0 until height / 2) {
            var uRowStart = row * rowStrideUV
            var vRowStart = row * rowStrideUV

            for (col in 0 until width / 2) {
                val uValue = uBuffer[uRowStart + col * pixelStrideUV]
                val vValue = vBuffer[vRowStart + col * pixelStrideUV]

                // 🔥 **确保 NV21 格式 (V 在前，U 在后)**
                data[uvStart + uvPos] = vValue  // V 通道
                data[uvStart + uvPos + 1] = uValue  // U 通道

                uvPos += 2
            }
        }

        return data
    }




    private fun isImageFormatSupported(image: Image): Boolean {
        val format = image.format
       // HLog.d(MainHook.TAG, "aaa 000 isImageFormatSupported, image.format=${format}")
        return when (format) {
            ImageFormat.YUV_420_888, ImageFormat.NV21, ImageFormat.YV12 -> true
            else -> false
        }
    }
}


enum class OutputImageFormat(val friendlyName: String) {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    override fun toString() = friendlyName
}

