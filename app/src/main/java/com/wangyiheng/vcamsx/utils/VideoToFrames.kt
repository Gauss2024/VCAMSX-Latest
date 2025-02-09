package com.wangyiheng.vcamsx.utils

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook
import com.wangyiheng.vcamsx.MainHook.Companion.TAG
import com.wangyiheng.vcamsx.MainHook.Companion.context
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

    private var fakeSurface: Surface? = null

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
    fun setFakeSurface(surface: Surface) {
        fakeSurface = surface
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
            HLog.d(TAG,"aaa 000 ------开始解码------")
            videoFilePath?.let { videoDecode(it) }
        } catch (t: Throwable) {
            throwable = t
        }
    }

    private fun videoDecode(videoPath: Any) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        HLog.d(TAG,"aaa 000 000 videoDecode start ......")
        try {
            extractor = MediaExtractor().apply {
                when (videoPath) {
                    is String -> setDataSource(videoPath) // 当参数是 String 时
                    is Uri -> context?.let { setDataSource(it, videoPath, null) } // 当参数是 Uri 时
                    else -> throw IllegalArgumentException("Unsupported video path type")
                }
            }
            HLog.d(TAG,"aaa 000 111 videoDecode start ......")
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                HLog.d(TAG,"aaa 000 No video track found in ${videoFilePath}")
            }
            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            showSupportedColorFormat(decoder.codecInfo.getCapabilitiesForType(mime))
            if (isColorFormatSupported(decodeColorFormat, decoder.codecInfo.getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
                HLog.d(TAG,"aaa 000 set decode color format to type ${decodeColorFormat}")

            } else {
                HLog.d(TAG,"aaa 000 unable to set decode color format, color format type ${decodeColorFormat}  not supported")
            }

            decodeFramesToImage(decoder, extractor, mediaFormat)
            decoder.stop()
            while (!stopDecode) {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decodeFramesToImage(decoder, extractor, mediaFormat)
                decoder.stop()
            }
        } catch (e: Exception) {
            // Handle exceptions
            HLog.d(TAG, "aaa ❌ 视频解码失败: ${e.message}")
        } finally {
            if(decoder != null) {
                decoder.stop()
                decoder.release()
                decoder = null
            }
            if(extractor != null) {
                extractor.release()
                extractor = null
            }
        }
    }

//    private fun videoDecode(videoPath: Any) {
//        var extractor: MediaExtractor? = null
//        var decoder: MediaCodec? = null
//
//        try {
//            HLog.d(TAG, "aaa 解码视频路径: $videoFilePath")
//
//            extractor = MediaExtractor().apply {
//                when (videoPath) {
//                    is String -> {
//                        HLog.d(TAG, "aaa 使用字符串路径: $videoFilePath")
//                        try {
//                            context?.let {
//                                extractor?.setDataSource(it, Uri.parse(videoPath), null)
//                                HLog.d(TAG, "aaa ✅ 使用 `setDataSource(Context, Uri, null)` 成功加载视频")
//                            } ?: HLog.d(TAG, "aaa ❌ `context` 为空，无法使用 `setDataSource(Context, Uri, null)`")
//                        } catch (e: IOException) {
//                            HLog.d(TAG, "aaa ❌ `setDataSource(Context, Uri, null)` 失败: ${e.message}")
//                        }
//
//
//                        val inputStream = context?.contentResolver?.openInputStream(Uri.parse(videoPath))
//                        if (inputStream != null) {
//                            HLog.d(TAG, "aaa ✅ `content://` 文件可以被打开")
//                            inputStream.close()
//                        } else {
//                            HLog.d(TAG, "aaa ❌ `content://` 文件无法打开")
//                        }
//
//
//
//                    }
//                    is Uri -> {
//                        HLog.d(TAG, "aaa 使用 `Uri`: $videoFilePath")
//                        val resolver = context?.contentResolver
//                        val fd = resolver?.openFileDescriptor(videoPath, "r")?.fileDescriptor
//                        if (fd != null) {
//                            extractor?.setDataSource(fd)
//                        } else {
//                            HLog.d(TAG, "aaa 无法获取文件描述符")
//                        }
//
//                    }
//                    else -> {
//                        HLog.d(TAG, "aaa 错误: 不支持的视频路径类型")
//                        throw IllegalArgumentException("Unsupported video path type")
//                    }
//                }
//            }
//
//
//            val trackIndex = selectTrack(extractor)
//            HLog.d(TAG, "aaa trackIndex=$trackIndex")
//            if (trackIndex < 0) return
//            extractor.selectTrack(trackIndex)
//
//
//            val mediaFormat = extractor.getTrackFormat(trackIndex)
//            val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
//            HLog.d(TAG, "aaa mime=$mime")
//
//            decoder = MediaCodec.createDecoderByType(mime!!)
//            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat)
//            HLog.d(TAG, "aaa decoder=$decoder")
////            val rotation = mediaFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
////            HLog.d(TAG, "aaa 视频旋转角度: $rotation°")
//
//            if (fakeSurface == null) {
//                HLog.d(TAG, "aaa ⚠️ FakeSurface 为空，无法解码")
//                return
//            }
//
//            decoder.configure(mediaFormat, fakeSurface, null, 0)
//            decoder.start()
//            HLog.d(TAG, "aaa start    。。。。。。。。")
//            var sawInputEOS = false
//            var sawOutputEOS = false
//            val info = MediaCodec.BufferInfo()
//            HLog.d(TAG, "aaa start    。。。。。。。。info=$info")
//            while (!sawOutputEOS && !stopDecode) {
//                if (!sawInputEOS) {
//                    val inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US)
//                    if (inputBufferId >= 0) {
//                        val inputBuffer = decoder.getInputBuffer(inputBufferId)
//                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
//                        if (sampleSize < 0) {
//                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
//                            sawInputEOS = true
//                        } else {
//                            val presentationTimeUs = extractor.sampleTime
//                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0)
//                            extractor.advance()
//                        }
//                    }
//                }
//
//                val outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US)
//                if (outputBufferId >= 0) {
//                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
//                    decoder.releaseOutputBuffer(outputBufferId, true)
//                }
//            }
//        } catch (e: Exception) {
//            HLog.d(TAG, "aaa ❌ 视频解码失败: ${e.message}")
//        } finally {
//            decoder?.stop()
//            decoder?.release()
//            extractor?.release()
//        }
//    }

    private fun selectTrack(extractor: MediaExtractor): Int {
        val numTracks = extractor.trackCount
        HLog.d(TAG, "aaa 🎬 `MediaExtractor` 发现轨道数量: $numTracks")

        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            HLog.d(TAG, "aaa 🎬 轨道 $i -> MIME 类型: $mime")
            if (mime!!.startsWith("video/")) {
                HLog.d(TAG, "aaa ✅ 找到视频轨道: $i")
                return i
            }
        }

        HLog.d(TAG, "aaa ❌ 没有找到 `video/` 轨道，视频可能损坏或格式不受支持")
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
        HLog.d(TAG,"aaa 000 start decodeFramesToImage ")
        var isFirst = false
        var startWhen: Long = 0
        val info = MediaCodec.BufferInfo()
        decoder.configure(mediaFormat, play_surf, null, 0)
        var sawInputEOS = false
        var sawOutputEOS = false
        decoder.start()
        var outputFrameCount = 0

        while (!sawOutputEOS && !stopDecode) {
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
//                            MainHook.data_buffer  =bitmapToYUV( imageToBitmap(image))
                            MainHook.data_buffer = getDataFromImage(image)
                        }
                        image.close()
                    }

                    val sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen)
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime)
                        } catch (e: InterruptedException) {
                            HLog.d(TAG,"aaa 000 线程延迟出错 message=${e.toString()}")
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, true)
                }
            }
        }
        callback?.onFinishDecode()
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
        Log.d("vcamsx", "Image format is $formatString")
    }

    fun imageToBitmap(image: Image): Bitmap {
        Log.d("vcamsx",image.format.toString())
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


    private fun getDataFromImage(image: Image): ByteArray {
        HLog.d(MainHook.TAG, "aaa 000 getDataFromImage 🟡 解码输出: width=${image.width}, height=${image.height}, format=${image.format}")
        logImageFormat(image)
        if (!isImageFormatSupported(image)) {
            throw RuntimeException("can't convert Image to byte array, format ${image.format}")
        }

        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes
        val pixelFormatBits = ImageFormat.getBitsPerPixel(image.format)
        val data = ByteArray(width * height * pixelFormatBits / 8)
        val rowData = ByteArray(planes[0].rowStride)

        fun copyPlaneData(planeIndex: Int, buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, channelOffset: Int, outputStride: Int) {
            var outputOffset = channelOffset
            buffer.position(rowStride * (crop.top / 2) + pixelStride * (crop.left / 2))
            for (row in 0 until height) {
                val length = if (pixelStride == 1 && outputStride == 1) {
                    width
                } else {
                    (width - 1) * pixelStride + 1
                }
                if (length == rowStride && outputStride == 1) {
                    buffer.get(data, outputOffset, length)
                    outputOffset += length
                } else {
                    buffer.get(rowData, 0, length)
                    for (col in 0 until width) {
                        data[outputOffset] = rowData[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
                if (row < height - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }

        var channelOffset = 0
        val uvHeight = height / 2
        val uvWidth = width / 2

        // Y Plane
        copyPlaneData(0, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, width, height, channelOffset, 1)
        channelOffset += width * height
        copyPlaneData(1, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, uvWidth, uvHeight, channelOffset, 2)
        copyPlaneData(2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, uvWidth, uvHeight, channelOffset + 1, 2)


        return data
    }



    private fun isImageFormatSupported(image: Image): Boolean {
        val format = image.format
        Log.d("vcamsx", "format$format")
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

