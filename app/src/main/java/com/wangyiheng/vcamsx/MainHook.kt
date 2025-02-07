package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import com.wangyiheng.vcamsx.utils.OutputImageFormat
import com.wangyiheng.vcamsx.utils.VideoPlayer.c1_camera_play
import com.wangyiheng.vcamsx.utils.VideoPlayer.ijkMediaPlayer
import com.wangyiheng.vcamsx.utils.VideoPlayer.camera2Play
import com.wangyiheng.vcamsx.utils.VideoPlayer.initializeTheStateAsWellAsThePlayer
import com.wangyiheng.vcamsx.utils.VideoToFrames
import de.robv.android.xposed.*
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*


class MainHook : IXposedHookLoadPackage {
    companion object {
        val TAG = "vcamsx"
        @Volatile
        //解码数据
        var data_buffer = byteArrayOf(0)
        var context: Context? = null
        //camera1使用，替换真实摄像头
        var fake_SurfaceTexture: SurfaceTexture? = null

        //camera2 使用
        var sessionConfiguration: SessionConfiguration? = null
        //camera2 使用
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null

        //camera2 使用，RTMP使用
        var original_preview_Surface: Surface? = null
        //camera1使用
        var original_c1_preview_SurfaceTexture:SurfaceTexture? = null
        var isPlaying:Boolean = false
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null
        var c2_reader_Surfcae: Surface? = null
        var camera_onPreviewFrame: Camera? = null
        var camera_callback_calss: Class<*>? = null
        var hw_decode_obj: VideoToFrames? = null

        var oriHolder: SurfaceHolder? = null

    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null


    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }


        //获取context  camera1 与camera2 都要调用。
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    HLog.d(TAG,"aaa 111 findAndHookMethod,(android.app.Instrumentation) method （callApplicationOnCreate）")
                    param?.args?.firstOrNull()?.let { arg ->
                        if (arg is Application) {
                            val applicationContext = arg.applicationContext
                            if (context != applicationContext) {
                                try {
                                    context = applicationContext
                                    if (!isPlaying) {
                                        isPlaying = true
                                        ijkMediaPlayer ?: initializeTheStateAsWellAsThePlayer()
                                    }
                                } catch (ee: Exception) {
                                    HLog.d(TAG, "$ee")
                                }
                            }
                        }
                    }
                }
            }
        )

        // camera1 调用
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 222 findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture）")
                    if (param.args[0] == null) {
                        return
                    }
                    if (param.args[0] == fake_SurfaceTexture) {
                        return
                    }

                    original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture
                    //add by gauss
                    original_c1_preview_SurfaceTexture?.setDefaultBufferSize(1080,1920)

                    fake_SurfaceTexture = if (fake_SurfaceTexture == null) {
                        SurfaceTexture(10)
                    } else {
                        fake_SurfaceTexture!!.release()
                        SurfaceTexture(10)
                    }
                    fake_SurfaceTexture?.setDefaultBufferSize(1080,1920)

                    param.args[0] = fake_SurfaceTexture

                    HLog.d(TAG,"aaa 222 初始话 fake_SurfaceTexture 成功 fake_SurfaceTexture=${fake_SurfaceTexture?.toString()}")
                }
            })

        // 在 SurfaceHolder.Callback 中处理 surfaceCreated
        XposedHelpers.findAndHookMethod(
            SurfaceHolder.Callback::class.java,
            "surfaceCreated",
            SurfaceHolder::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    super.beforeHookedMethod(param)
                    HLog.d(TAG, "aaa 666 Surface before ready.")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val surfaceHolder = param.args[0] as SurfaceHolder
                    // 标记 Surface 已准备好
                    HLog.d(TAG, "aaa 666 Surface after ready.")
                }
            }
        )

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
            ByteArray::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 333 findAndHookMethod,(android.hardware.Camera)  method （addCallbackBuffer）")
                    if (param.args[0] != null) {
                        param.args[0] = ByteArray((param.args[0] as ByteArray).size)
                    }
                }
            })
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer", PreviewCallback::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 444 findAndHookMethod,(android.hardware.Camera)  method （setPreviewCallbackWithBuffer） videostatus=${videoStatus?.isVideoEnable }")



                if(videoStatus?.isVideoEnable == false) return
                if (param.args[0] != null) {
                    process_callback(param)
                }
            }
        })
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                HLog.d(TAG,"aaa 555 findAndHookMethod,(android.hardware.Camera)  method （startPreview）视频开始播放。。。。。")
                c1_camera_play()
            }
        })




        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera\$Parameters",  // 目标类
            lpparam.classLoader,
            "setPreviewSize",  // 目标方法
            Int::class.java, Int::class.java,  // 参数类型：width 和 height
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val originalWidth = param.args[0] as Int
                    val originalHeight = param.args[1] as Int
                    //HLog.d(TAG, "aaa 666 Hook setPreviewSize() 被调用: width=$originalWidth, height=$originalHeight")

                    // 交换宽高，使 width = 720, height = 1280
                    val newWidth = 1080//originalHeight
                    val newHeight = 1920//originalWidth
                    param.args[0] = newWidth
                    param.args[1] = newHeight

                    //HLog.d(TAG, "aaa 666 Hook setPreviewSize() 修改为: width=$newWidth, height=$newHeight")
                }
            }
        )

//好像走不到
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 666 findAndHookMethod,(android.hardware.Camera)  method （setPreviewDisplay）")

                oriHolder = param.args[0] as SurfaceHolder

                param.result = null
            }

            }
        )

        //不在使用
        XposedHelpers.findAndHookMethod(
            "android.hardware.Camera",
            lpparam.classLoader,
            "getParameters",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val parameters = param.result as Camera.Parameters

                    try {
                        // 正确获取 `getPreviewSize()` 方法
                        val getPreviewSizeMethod =
                            parameters::class.java.getMethod("getPreviewSize")
                        val previewSize = getPreviewSizeMethod.invoke(parameters)

                        if (previewSize != null) {
                            val widthField = previewSize::class.java.getDeclaredField("width")
                            val heightField = previewSize::class.java.getDeclaredField("height")
                            widthField.isAccessible = true
                            heightField.isAccessible = true

                            val originalWidth = widthField.getInt(previewSize)
                            val originalHeight = heightField.getInt(previewSize)

                            // 交换宽高
                            widthField.setInt(previewSize, 1080)
                            heightField.setInt(previewSize, 1920)

                            HLog.d(
                                TAG,
                                "aaa 333 Hook getParameters(): 交换后 width=${
                                    widthField.get(previewSize)
                                }, height=${heightField.get(previewSize)}"
                            )
                        }
                    } catch (e: Exception) {
                        HLog.d(TAG, "aaa 333 修改 previewSize 失败: $e")
                    }
                }
            }
        )


//camera 2 需要
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 777 findAndHookMethod,(android.hardware.camera2.CameraManager)  method （openCamera）")
                    try {
                        if(param.args[1] == null){
                            return
                        }
                        if(param.args[1] == c2_state_callback){
                            return
                        }
                        c2_state_callback = param.args[1] as CameraDevice.StateCallback
                        c2_state_callback_class = param.args[1]?.javaClass
                        process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                    }catch (e:Exception){
                        HLog.d("android.hardware.camera2.CameraManager报错了", "openCamera")
                    }
                }
            })
    }

    private fun process_callback(param: MethodHookParam) {
        if (param.args[0] == null) {
            HLog.d(TAG, "aaa 888 param.args[0] is null, skipping process_callback")
            return
        }

        val preview_cb_class: Class<*> = param.args[0].javaClass
        XposedHelpers.findAndHookMethod(
            preview_cb_class, "onPreviewFrame",
            ByteArray::class.java, Camera::class.java,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(paramd: MethodHookParam) {
                    HLog.d(TAG, "aaa 888 Hooked onPreviewFrame in ${preview_cb_class.name}")

                    val camera = paramd.args[1] as Camera
                    val parameters = camera.parameters
                    val previewSize = parameters.previewSize
                    val expectedSize  = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
                    HLog.d(TAG, "aaa 888 摄像头预览分辨率: width=${previewSize.width}, height=${previewSize.height}, 帧大小 expectedSize=${expectedSize}  data_buffer==${data_buffer?.size}")

                    val localcam = paramd.args[1] as Camera
                    if (localcam == camera_onPreviewFrame) {
                        if (data_buffer == null || data_buffer.size < expectedSize) {
                            HLog.d(TAG, "aaa 888 非首次 data_buffer 为空或大小不足: ${data_buffer?.size ?: 0}, 需要: ${expectedSize}")
                            //修正大小
                            return
                        }

                        var targetBuffer = paramd.args[0] as? ByteArray
                        if (targetBuffer == null || targetBuffer.size < expectedSize) {
                            HLog.d(TAG, "aaa 888 非首次 目标缓冲区为空或大小不足: ${targetBuffer?.size ?: 0}, 需要: ${expectedSize}")
                            //return
                            return
                        }

                        // 旋转帧数据
                        val rotatedData = rotateNV21(data_buffer, previewSize.width, previewSize.height, 90)
                        System.arraycopy(rotatedData, 0, targetBuffer, 0, rotatedData.size)
                    } else {
                        HLog.d(TAG, "aaa 888 首次 localcam != camera_onPreviewFrame Initializing new VideoToFrames instance")
                        camera_callback_calss = preview_cb_class
                        camera_onPreviewFrame = localcam

                        hw_decode_obj?.stopDecode()
                        hw_decode_obj = VideoToFrames().apply {
                            setSaveFrames(OutputImageFormat.NV21)
                        }

                        val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
                        val videoPathUri = Uri.parse(videoUrl)
                        hw_decode_obj?.decode(videoPathUri)

                        if (data_buffer == null || data_buffer.size < expectedSize) {
                            HLog.d(TAG, "aaa 888 首次 data_buffer 为空或大小不足: ${data_buffer?.size ?: 0}, 需要: ${expectedSize}")
                            //修正大小
                            return
                        }

                        var targetBuffer = paramd.args[0] as? ByteArray
                        if (targetBuffer == null || targetBuffer.size < expectedSize) {
                            HLog.d(TAG, "aaa 888 首次 目标缓冲区为空或大小不足: ${targetBuffer?.size ?: 0}, 需要: ${expectedSize}")
                            //return
                            return
                        }

                        // 旋转帧数据
                        val rotatedData = rotateNV21(data_buffer, previewSize.width, previewSize.height, 90)
                        System.arraycopy(rotatedData, 0, targetBuffer, 0, rotatedData.size)
                    }
                }
            }
        )
    }



//camera 2使用
    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 999 findAndHookMethod,process_camera2_init)  method （onOpend）")
                needRecreate = true
                createVirtualSurface()

                c2_reader_Surfcae = null
                original_preview_Surface = null

                if(lpparam.packageName != "com.ss.android.ugc.aweme" ){
                    XposedHelpers.findAndHookMethod(param.args[0].javaClass, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun beforeHookedMethod(paramd: MethodHookParam) {
                            if (paramd.args[0] != null) {
                                paramd.args[0] = listOf(c2_virtual_surface)
                            }
                        }
                    })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        XposedHelpers.findAndHookMethod(param.args[0].javaClass, "createCaptureSession",
                            SessionConfiguration::class.java, object : XC_MethodHook() {
                                @Throws(Throwable::class)
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    super.beforeHookedMethod(param)
                                    if (param.args[0] != null) {
                                        sessionConfiguration = param.args[0] as SessionConfiguration
                                        outputConfiguration = OutputConfiguration(c2_virtual_surface!!)
                                        fake_sessionConfiguration = SessionConfiguration(
                                            sessionConfiguration!!.getSessionType(),
                                            Arrays.asList<OutputConfiguration>(outputConfiguration),
                                            sessionConfiguration!!.getExecutor(),
                                            sessionConfiguration!!.getStateCallback()
                                        )
                                        param.args[0] = fake_sessionConfiguration
                                    }
                                }
                            })
                    }
                }
            }
        })


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 101010 android.hardware.camera2.CaptureRequest.Builder)  method （addTarget）")
                    if (param.args[0] != null) {
                        if(param.args[0] == c2_virtual_surface)return
                        val surfaceInfo = param.args[0].toString()
                        if (!surfaceInfo.contains("Surface(name=null)")) {
                            if(original_preview_Surface != param.args[0] as Surface ){
                                original_preview_Surface = param.args[0] as Surface
                            }
                        }else{
                            if(c2_reader_Surfcae == null && lpparam.packageName != "com.ss.android.ugc.aweme"){
                                c2_reader_Surfcae = param.args[0] as Surface
                            }
                        }
                        if(lpparam.packageName != "com.ss.android.ugc.aweme"){
                            param.args[0] = c2_virtual_surface
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 11 11 11  android.hardware.camera2.CaptureRequest.Builder)  method （build）")
                camera2Play()
            }
        })
    }

    //camera2 使用
    private fun createVirtualSurface(): Surface? {
        if (needRecreate) {
            c2VirtualSurfaceTexture?.release()
            c2VirtualSurfaceTexture = null

            c2_virtual_surface?.release()
            c2_virtual_surface = null

            c2VirtualSurfaceTexture = SurfaceTexture(15)
            c2_virtual_surface = Surface(c2VirtualSurfaceTexture)
            needRecreate = false
        } else if (c2_virtual_surface == null) {
            needRecreate = true
            c2_virtual_surface = createVirtualSurface()
        }
        return c2_virtual_surface
    }


    fun rotateNV21(input: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        val output = ByteArray(input.size)
        val frameSize = width * height
        val swap = rotation == 90 || rotation == 270
        val newWidth = if (swap) height else width
        val newHeight = if (swap) width else height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val xOut = when (rotation) {
                    90 -> y
                    180 -> width - x - 1
                    270 -> height - y - 1
                    else -> x
                }
                val yOut = when (rotation) {
                    90 -> width - x - 1
                    180 -> height - y - 1
                    270 -> x
                    else -> y
                }
                output[yOut * newWidth + xOut] = input[y * width + x]
                val uvIndex = frameSize + (y / 2) * width + (x and 1.inv())
                val uvOutIndex = frameSize + (yOut / 2) * newWidth + (xOut and 1.inv())
                output[uvOutIndex] = input[uvIndex]
            }
        }
        return output
    }
}

