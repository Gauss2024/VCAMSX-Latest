package com.wangyiheng.vcamsx.utils

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

    private var targetWidth: Int = 0
    private var targetHeight: Int = 0
    // 新增旋转角度字段（例如 90 度）
    private var rotationDegrees: Int = 0 // 默认顺时针旋转90度



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



    fun stopDecode() {
        stopDecode = true
    }

    interface Callback {
        fun onFinishDecode()
        fun onDecodeFrame(index: Int)
    }

    // 新增方法：强制设定解码目标分辨率
    fun setTargetResolution(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        HLog.d(TAG, "aaa 000 解码目标分辨率已设置: $width x $height")
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
            HLog.d(TAG,"aaa 000 ------开始解码------")
            videoFilePath?.let { videoDecode(it) }
        } catch (t: Throwable) {
            throwable = t
        }
    }

    private fun videoDecode(videoPath: Any) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            extractor = MediaExtractor().apply {
                when (videoPath) {
                    is String -> setDataSource(videoPath) // 当参数是 String 时
                    is Uri -> context?.let { setDataSource(it, videoPath, null) } // 当参数是 Uri 时
                    else -> throw IllegalArgumentException("Unsupported video path type")
                }
            }
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
        } finally {
            if(decoder != null) {
                decoder.stop()
                decoder.release()
            }
            if(extractor != null) {
                extractor.release()
            }
        }
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
        HLog.d(TAG,"aaa 000 start decodeFramesToImage ")
// 覆盖原始视频分辨率（如果已设置目标分辨率）
        if (targetWidth > 0 && targetHeight > 0) {
            mediaFormat.setInteger(MediaFormat.KEY_WIDTH, targetWidth)
            mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, targetHeight)
            HLog.d(TAG, "aaa 000 动态调整解码器输出尺寸 -> ${targetWidth}x${targetHeight}")
        }

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
                        HLog.d(TAG,"aaa 000   outputImageFormat=${outputImageFormat}")
                        HLog.d(TAG, "aaa 000   【解码帧】 frameCount=$outputFrameCount, size=${info.size}, presentationTimeUs=${info.presentationTimeUs}")

                        if (outputImageFormat != null) {

                            val scaledNV21 = scaleYUVImageToTarget(image, targetWidth, targetHeight)
                            val expectedSize = targetWidth * targetHeight * 3 / 2

                            if (scaledNV21?.size != expectedSize) {
                                HLog.d(TAG, "aaa 000 ❌ NV21数据大小不匹配，预期: $expectedSize, 实际: ${scaledNV21?.size}")
                            } else {
                                MainHook.data_buffer = scaledNV21
                                //MainHook.data_buffer = getDataFromImage(image)
                            }
                            //MainHook.data_buffer  =bitmapToYUV( imageToBitmap(image))
                            //MainHook.data_buffer = getDataFromImage(image)
                            // **检查 data_buffer 是否真的填充成功**
                            if (MainHook.data_buffer.isEmpty()) {
                                HLog.d(TAG, "aaa 000  ❌ `data_buffer` 为空，转换失败")
                            } else {
                                HLog.d(TAG, "aaa 000 ✅ `data_buffer` 填充成功，长度=${MainHook.data_buffer.size}")
                            }
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

    private fun scaleYUVImageToTarget(image: Image, targetWidth: Int, targetHeight: Int): ByteArray? {


        if (image.width == 0 || image.height == 0) {
            HLog.d(TAG, "aaa 000 ❌ 源Bitmap尺寸异常: ${image.width}x${image.height}")
            return null
        }
        //判断是否旋转
        val yuanwidth = image.width
        val yuanheight = image.height
        if(yuanwidth<targetWidth){
            rotationDegrees=90
            HLog.d(TAG, "aaa 000 需要旋转90 视频尺寸$yuanwidth*$yuanheight，目标尺寸:$targetWidth*$targetHeight")
        }
        else{
            rotationDegrees = 0
            HLog.d(TAG, "aaa 000 不需要旋转 视频尺寸$yuanwidth*$yuanheight，目标尺寸:$targetWidth*$targetHeight")
        }

        return processImageYUV(image,targetWidth,targetHeight,rotationDegrees)

//        // 1. 将 YUV_420_888 转换为 Bitmap
//        val srcBitmap = imageToBitmap(image)
//        // 2. 旋转 Bitmap
//        val rotatedBitmap = rotateBitmap(srcBitmap, rotationDegrees)
//        // 3. 缩放旋转后的 Bitmap 到目标尺寸
//        val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)
//        rotatedBitmap.recycle()
//        // 4. 转换为 NV21 格式
//        return bitmapToNV21(scaledBitmap, targetWidth, targetHeight)
    }

    /**
     * 将 YUV_420_888 格式的 Image 转换为 NV21 格式数据。
     * 注意：本方法假设 image 的 planes 顺序为 [Y, U, V]，且 U/V 的 pixelStride 为 1（独立存储）。
     * 如果你的设备 U/V 排列有差异，请根据实际情况调整。
     */
    fun yuv420888ToNV21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2
        val nv21 = ByteArray(ySize + uvSize)

        val planes = image.planes
        // 假设 planes[0] 为 Y，planes[1] 为 U，planes[2] 为 V
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val rowStrideY = yPlane.rowStride
        // 逐行拷贝 Y 数据，跳过行内填充
        var outputIndex = 0
        val rowData = ByteArray(rowStrideY)
        for (row in 0 until height) {
            yBuffer.position(row * rowStrideY)
            yBuffer.get(rowData, 0, rowStrideY)
            // 只拷贝有效宽度的像素
            System.arraycopy(rowData, 0, nv21, outputIndex, width)
            outputIndex += width
        }

        // 处理 UV 数据：NV21 格式要求交错存储：V 在前，U 在后
        val rowStrideUV = uPlane.rowStride
        val pixelStrideUV = uPlane.pixelStride  // 根据日志，通常为1
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // 为简单起见，先将 U/V 数据全部读取到数组中
        val uData = ByteArray(uBuffer.remaining())
        val vData = ByteArray(vBuffer.remaining())
        uBuffer.get(uData)
        vBuffer.get(vData)

        // NV21 UV 平面占用 uvSize 字节，理论上每行有效长度为 width，实际有效 UV 像素数量为 width/2（每个像素占2字节）
        var uvOutputIndex = ySize
        // 遍历 UV 平面：有效行数 = height/2, 每行有效像素个数 = width/2
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // 计算原始数据索引，注意考虑 rowStrideUV 和 pixelStrideUV
                val index = row * rowStrideUV + col * pixelStrideUV
                if (index < vData.size && index < uData.size) {
                    nv21[uvOutputIndex++] = vData[index] // V
                    nv21[uvOutputIndex++] = uData[index] // U
                }
            }
        }
        return nv21
    }

    /**
     * 旋转 NV21 数据 90° 顺时针。
     *
     * @param data NV21 格式数据，长度应为 width*height*3/2。
     * @param width 原图的 Y 平面宽度。
     * @param height 原图的 Y 平面高度。
     * @return 旋转后的 NV21 数据，新图像的 Y 平面尺寸为 height×width，UV 部分对应旋转后的分辨率。
     */
    fun rotateNV21_90(data: ByteArray, width: Int, height: Int): ByteArray {
        // 原 Y 平面大小
        val frameSize = width * height
        // UV 部分大小
        // 注意：NV21 格式总长度应为 frameSize + frameSize/2
        val output = ByteArray(data.size)

        // --------- 旋转 Y 平面 -----------
        // 对于 Y 平面，原坐标 (x, y) 旋转 90° 顺时针后，新坐标为 (y, width - x - 1)
        // 新图像 Y 平面的尺寸：newWidth = height, newHeight = width
        var index = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                output[index++] = data[y * width + x]
            }
        }

        // --------- 旋转 UV 平面 -----------
        // 原 UV 平面分辨率：
        val origUVWidth = width / 2    // 列数
        val origUVHeight = height / 2  // 行数
        // 旋转后，新 UV 平面的尺寸：
        val newUVWidth = origUVHeight  // 新列数
        // 每个 UV 像素组占 2 字节，存储顺序 NV21 为 (V, U)
        // 原始 UV 数据区域从下标 frameSize 开始
        // 对于每个原始 UV 块，索引计算：srcIndex = frameSize + (y * origUVWidth + x) * 2
        // 旋转 90° 后，新坐标为：
        // newX = y, newY = origUVWidth - 1 - x
        // 新线性索引：dstIndex = frameSize + ((newY * newUVWidth) + newX) * 2
        for (y in 0 until origUVHeight) {
            for (x in 0 until origUVWidth) {
                val srcIndex = frameSize + (y * origUVWidth + x) * 2
                // 读取原始 UV 组
                val v = data[srcIndex]      // V 分量
                val u = data[srcIndex + 1]  // U 分量

                // 计算旋转后新位置（对 UV 矩阵旋转 90° 顺时针）
                val newX = y
                val newY = origUVWidth - 1 - x
                val dstIndex = frameSize + ((newY * newUVWidth) + newX) * 2

                output[dstIndex] = v
                output[dstIndex + 1] = u
            }
        }
        return output
    }



    /**
     * 对 NV21 格式数据进行最近邻缩放。
     * srcWidth, srcHeight: 源图像尺寸（旋转后尺寸）
     * dstWidth, dstHeight: 目标图像尺寸
     */
    fun scaleNV21(data: ByteArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): ByteArray {
        val srcYSize = srcWidth * srcHeight
        val dstYSize = dstWidth * dstHeight
        val dstUVSize = dstYSize / 2
        val dstData = ByteArray(dstYSize + dstUVSize)

        // 缩放 Y 平面
        for (j in 0 until dstHeight) {
            val srcJ = j * srcHeight / dstHeight
            for (i in 0 until dstWidth) {
                val srcI = i * srcWidth / dstWidth
                dstData[j * dstWidth + i] = data[srcJ * srcWidth + srcI]
            }
        }

        // 缩放 UV 平面
        // 原 UV 平面：每行长度 = srcWidth, 但有效像素数为 srcWidth/2
        for (j in 0 until dstHeight / 2) {
            val srcJ = j * (srcHeight / 2) / (dstHeight / 2)
            for (i in 0 until dstWidth / 2) {
                val srcI = i * (srcWidth / 2) / (dstWidth / 2)
                val srcIndex = srcYSize + srcJ * srcWidth + srcI * 2
                val dstIndex = dstYSize + j * dstWidth + i * 2
                dstData[dstIndex] = data[srcIndex]         // V
                dstData[dstIndex + 1] = data[srcIndex + 1]   // U
            }
        }
        return dstData
    }

    /**
     * 综合处理：直接操作 YUV 数据（YUV_420_888格式），转换为 NV21，
     * 然后根据给定的旋转角度进行旋转（目前支持 90°），再进行缩放到目标尺寸，
     * 返回最终的 NV21 数据。
     *
     * 注意：如果旋转为 90° 或 270°，原图的宽高会互换，缩放时需注意调整。
     */
    fun processImageYUV(image: Image, targetWidth: Int, targetHeight: Int, rotation: Int): ByteArray {


        // 第一步：转换为 NV21 格式
        val nv21 = yuv420888ToNV21(image)
        HLog.d(MainHook.TAG, "aaa 000 开始旋转 NV21 数据，原始尺寸: ${image.width}x${image.height}, data 长度: ${nv21.size}")
        printFirst10Bytes(nv21)
        // 第二步：旋转（目前只提供 90° 的旋转示例）
        val rotated: ByteArray = when (rotation) {
            90 -> rotateNV21_90(nv21, image.width, image.height)
            180 -> {/* 可扩展 180° 旋转方法 */ nv21 }
            270 -> {/* 可扩展 270° 旋转方法 */ nv21 }
            else -> nv21
        }

        HLog.d(MainHook.TAG, "aaa 000 旋转后 NV21 数据，img尺寸: ${image.width}x${image.height}, data 长度: ${rotated.size}")
        printFirst10Bytes(rotated)
        // 对于 90° 旋转，旋转后新宽度 = image.height, 新高度 = image.width
        val srcWidth = if (rotation % 180 == 0) image.width else image.height
        val srcHeight = if (rotation % 180 == 0) image.height else image.width
        // 第三步：缩放到目标尺寸（使用最近邻法）
        HLog.d(MainHook.TAG, "aaa 000 缩放前，原始尺寸: ${srcWidth}x${srcHeight}, 目标: ${targetWidth}*$targetHeight")
        val scaled = scaleNV21(rotated, srcWidth, srcHeight, targetWidth, targetHeight)
        return scaled
    }


    fun printFirst10Bytes(data: ByteArray) {
        // 如果长度不足 10，则打印全部，否则打印前 10 个字节
        val count = if (data.size >= 10) 10 else data.size
        val bytes = data.slice(0 until count)
        // 将字节转换为整型（避免打印负数）后再打印
        HLog.d(MainHook.TAG, "aaa 000 First 10 bytes: ${bytes.joinToString(", ") { it.toInt().and(0xFF).toString() }}")
    }

    // YUV 转换核心逻辑（优化版）
    private fun encodeYUV420SP(yuv: ByteArray, argb: IntArray, width: Int, height: Int) {

        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        HLog.d(TAG, "aaa 000 encodeYUV420SP width=$width,height=$height")

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (argb[y * width + x] shr 16) and 0xFF
                val g = (argb[y * width + x] shr 8) and 0xFF
                val b = argb[y * width + x] and 0xFF

                // RGB 转 YUV 公式
                val Y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                val U = (-0.169 * r - 0.331 * g + 0.500 * b + 128).toInt().coerceIn(0, 255)
                val V = (0.500 * r - 0.419 * g - 0.081 * b + 128).toInt().coerceIn(0, 255)

                yuv[yIndex++] = Y.toByte()

                // 每隔一行和一列采样一次 UV（NV21 格式）
                if (y % 2 == 0 && x % 2 == 0) {
                    yuv[uvIndex++] = V.toByte()
                    yuv[uvIndex++] = U.toByte()
                }
            }
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
        Log.d("vcamsx", "Image format is $formatString")
    }

    fun imageToBitmap(image: Image): Bitmap {

        HLog.d(TAG, "aaa 000 imageToBitmap image.format=${image.format.toString()}")
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

