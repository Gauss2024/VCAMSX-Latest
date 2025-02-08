package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
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
import android.view.WindowManager
import android.widget.Toast
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
import kotlin.math.min


class MainHook : IXposedHookLoadPackage {
    companion object {
        val TAG = "vcamsx"
        @Volatile
        var data_buffer = byteArrayOf(0)
        var context: Context? = null
        var origin_preview_camera: Camera? = null
        var fake_SurfaceTexture: SurfaceTexture? = null

        var sessionConfiguration: SessionConfiguration? = null
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null

        var original_preview_Surface: Surface? = null
        var original_c1_preview_SurfaceTexture:SurfaceTexture? = null
        var isPlaying:Boolean = false
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null
        var c2_reader_Surfcae: Surface? = null
        var camera_onPreviewFrame: Camera? = null
        var camera_callback_calss: Class<*>? = null
        var hw_decode_obj: VideoToFrames? = null
        var isFrontCamera:Boolean=true

    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null

    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }

        //获取context
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
                                    HLog.d(TAG, "aaa 启动失败：$ee")
                                }
                            }
                        }
                    }
                }
            }
        )

        // 支持bilibili摄像头替换
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture）")

                    if (param.args[0] == null) return
                    if (param.args[0] == fake_SurfaceTexture) return
                    if (origin_preview_camera != null && origin_preview_camera == param.thisObject) {
                        param.args[0] = fake_SurfaceTexture
                        return
                    }

                    origin_preview_camera = param.thisObject as Camera
                    original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture

                    val previewSize = origin_preview_camera!!.parameters.previewSize
                    HLog.d(TAG, "aaa 000 【setPreviewTexture】 摄像头预览尺寸: width=${previewSize.width}, height=${previewSize.height}")

                    fake_SurfaceTexture = if (fake_SurfaceTexture == null) {
                        SurfaceTexture(10)
                    } else {
                        fake_SurfaceTexture!!.release()
                        SurfaceTexture(10)
                    }

                    // **⚡ 确保 fake_SurfaceTexture 适配摄像头预览尺寸**
                    //fake_SurfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
                    fake_SurfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
                    HLog.d(TAG, "aaa 000【setPreviewTexture】 设置 fake_SurfaceTexture 默认缓冲区: ${previewSize.width}x${previewSize.height}")
                    param.args[0] = fake_SurfaceTexture
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {

                HLog.d(TAG,"aaa 333 findAndHookMethod,(android.hardware.Camera)  method （startPreview）")
                // 设置预览方向
                val camera = param?.thisObject as Camera
                val info = Camera.CameraInfo()
                HLog.d(TAG,"aaa 333 startPreview info.facing=${info.facing}")
                if(info?.facing==0){
                    isFrontCamera=true
                }
                else{
                    isFrontCamera=false
                }
                Camera.getCameraInfo(info.facing, info)
                val display = (context?.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                val rotation = display.rotation
                HLog.d(TAG,"aaa 333 startPreview rotation=${rotation}")

//                val degrees = when (rotation) {
//                    Surface.ROTATION_0 -> 0
//                    Surface.ROTATION_90 -> 90
//                    Surface.ROTATION_180 -> 180
//                    Surface.ROTATION_270 -> 270
//                    else -> 0
//                }
//                val result = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
//                    (info.orientation + degrees) % 360
//                    (360 - result) % 360  // 补偿镜像
//                } else {  // 后置摄像头
//                    (info.orientation - degrees + 360) % 360
//                }
//                camera.setDisplayOrientation(result)

                c1_camera_play()
            }
        })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 444 findAndHookMethod,(android.hardware.Camera)  method （setPreviewCallbackWithBuffer）")
                    if(videoStatus?.isVideoEnable == false) return
                    if (param.args[0] != null) {
                        process_callback(param)
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
            ByteArray::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    //HLog.d(TAG,"aaa 555 findAndHookMethod,(android.hardware.Camera)  method （addCallbackBuffer）")
                    if (param.args[0] != null) {
                        param.args[0] = ByteArray((param.args[0] as ByteArray).size)
                    }
                }
            })



        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 666 findAndHookMethod,(android.hardware.camera2.CameraManager)  method （openCamera）")
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
        val preview_cb_class: Class<*> = param.args[0].javaClass
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(paramd: MethodHookParam) {
                   // HLog.d(TAG,"aaa 777 findAndHookMethod,"+preview_cb_class.name+")  method （onPreviewFrame）")
                    val localcam = paramd.args[1] as Camera
                    if (localcam ==  camera_onPreviewFrame) {

                        while ( data_buffer == null) {
                            HLog.d(TAG,"aaa 777 非首次 data_buffer is null")
                        }
                        //HLog.d(TAG,"aaa 777 非首次 copy 数据")
                        if (data_buffer == null || paramd.args[0] == null) {
                            HLog.d(TAG, "aaa 📌 跳过 copy，data_buffer 或 目标 buffer 为空")
                           // return
                        }
                        //HLog.d(TAG, "aaa 000  非首次 复制数据到预览缓冲区，源长度: ${data_buffer.size}, 目标长度: ${(paramd.args[0] as ByteArray).size}")
                        if(data_buffer.size==(paramd.args[0] as ByteArray).size){
                            HLog.d(TAG, "aaa 000  非首次 长度一致，开始copy  源长度: ${data_buffer.size}, 目标长度: ${(paramd.args[0] as ByteArray).size}")
                            System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                        }

                    } else {

                        camera_callback_calss = preview_cb_class
                        camera_onPreviewFrame = paramd.args[1] as Camera
                        val mwidth = camera_onPreviewFrame!!.getParameters().getPreviewSize().width
                        val mhight = camera_onPreviewFrame!!.getParameters().getPreviewSize().height
                        hw_decode_obj?.stopDecode()
                        // MainHook.kt - 在 process_callback 中增加方向判断
                        val previewSize = camera_onPreviewFrame!!.parameters.previewSize
                        val isPortraitMode = previewSize.height > previewSize.width

                        // 若摄像头预览方向为竖屏，但视频是横屏，需调整解码尺寸
                        val decodeWidth = if (isPortraitMode) previewSize.height else previewSize.width
                        val decodeHeight = if (isPortraitMode) previewSize.width else previewSize.height


                        Toast.makeText(context, """
                                视频需要分辨率与摄像头完全相同
                                宽：${mwidth}
                                高：${mhight}
                                """.trimIndent(), Toast.LENGTH_SHORT).show()

                        val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
                        val videoPathUri = Uri.parse(videoUrl)

                        hw_decode_obj = VideoToFrames().apply {
                            setTargetResolution(decodeWidth, decodeHeight) // 强制设置解码尺寸匹配摄像头方向
                            setSaveFrames(OutputImageFormat.NV21)

                            decode(videoPathUri)
                        }

                        while ( data_buffer == null) {
                            HLog.d(TAG,"aaa 777 首次 data_buffer is null")
                        }
                        if (data_buffer == null || paramd.args[0] == null) {
                            HLog.d(TAG, "aaa 📌 跳过 copy，data_buffer 或 目标 buffer 为空")
                            //return
                        }
                        //HLog.d(TAG, "aaa 000 首次 复制数据到预览缓冲区，源长度: ${data_buffer.size}, 目标长度: ${(paramd.args[0] as ByteArray).size}")
                        if(data_buffer.size==(paramd.args[0] as ByteArray).size) {
                            HLog.d(TAG, "aaa 000  首次 长度一致，开始copy。。。。。源长度: ${data_buffer.size}, 目标长度: ${(paramd.args[0] as ByteArray).size}")
                            System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                        }
                    }
                }
            })
    }


    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 888 findAndHookMethod,process_camera2_init)  method （onOpend）")
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
                    HLog.d(TAG,"aaa 999 android.hardware.camera2.CaptureRequest.Builder)  method （addTarget）")
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
                HLog.d(TAG,"aaa  101010 android.hardware.camera2.CaptureRequest.Builder)  method （build）")
                camera2Play()
            }
        })
    }

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
}

