package com.wangyiheng.vcamsx

import android.annotation.SuppressLint
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
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.GaussVideoDecoder
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import com.wangyiheng.vcamsx.utils.VideoPlayer.ijkMediaPlayer
import com.wangyiheng.vcamsx.utils.VideoPlayer.camera2Play
import com.wangyiheng.vcamsx.utils.VideoPlayer.initializeTheStateAsWellAsThePlayer
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class MainHook : IXposedHookLoadPackage {
    companion object {
        val TAG = "vcamsx"
        @Volatile var context: Context? = null

        //Camera1
        // 通过 SurfaceTexture 创建一个用于视频输出的 fake Surface
        val fakeSurfaceTexture: SurfaceTexture = SurfaceTexture(0)
        val fakeSurface: Surface = Surface(fakeSurfaceTexture)
        // 标记是否已经启动视频解码器，避免重复启动
        var videoDecoderStarted = false

        private val DECODER_FIELD = "video_decoder_field"
        private const val VIDEO_PATH = "/sdcard/10.mp4" // 替换视频路径

        //Camera2
        var sessionConfiguration: SessionConfiguration? = null
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null

        //RTMP 网络流
        var original_preview_Surface: Surface? = null

        //RTMP,MediaPlayer Camere2 使用
        var isPlaying:Boolean = false



    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null

    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }

        //获取context，camera1与Camera2都会用
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

        // Hook Camera.open()
        XposedHelpers.findAndHookMethod(
            Camera::class.java,
            "open",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cameraId = param.args[0] as Int
                    val camera = param.result as Camera
                     HLog.d(MainHook.TAG, "Camera opened (id=$cameraId)")

                }
            })

        // Hook Camera.release()
        XposedHelpers.findAndHookMethod(
            Camera::class.java,
            "release",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val camera = param.thisObject as Camera
                     HLog.d(MainHook.TAG, "Camera released")
                }
            })

        // 支持bilibili摄像头替换
//        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
//            SurfaceTexture::class.java, object : XC_MethodHook() {
//                @SuppressLint("NewApi")
//                @Throws(Throwable::class)
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture） fakeSurfaceTexture=$fakeSurfaceTexture")
//                    param.args[0] = fakeSurfaceTexture
//                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture） fakeSurfaceTexture 替换成功")
//                }
//            })
//
//        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                HLog.d(TAG,"aaa 333 findAndHookMethod,(android.hardware.Camera)  method （startPreview） ")
//                if (!videoDecoderStarted) {
//                    try {
//                        HLog.d(TAG,"MainHook: Starting GaussVideoDecoder with video URL: $VIDEO_PATH")
//                        // 启动视频解码器，将视频输出到 fakeSurface
//                        GaussVideoDecoder.start(VIDEO_PATH, fakeSurface)
//                        videoDecoderStarted = true
//                    } catch (ex: Exception) {
//                        HLog.d(TAG,"MainHook: Error starting GaussVideoDecoder: ${ex.message}")
//                    }
//                }
//            }
//        })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"setPreviewCallbackWithBuffer。。。。。。。。。。。")
                    param.args[0] = null // 禁用原始预览回调
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"setPreviewCallback。。。。。。。。。。。")
                    param.args[0] = null // 禁用原始预览回调
                }
            })



        // Camera2 第二步调用
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa camera2 step2 findAndHookMethod,(android.hardware.camera2.CameraManager)  method （openCamera）")
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


  


    // Camera2 第3步调用
    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa camera2 step3 findAndHookMethod,process_camera2_init)  method （onOpend）")
                needRecreate = true
                createVirtualSurface()

                original_preview_Surface = null

                //非抖音调用
                if(lpparam.packageName != "com.ss.android.ugc.aweme" ){
                    XposedHelpers.findAndHookMethod(param.args[0].javaClass, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, object : XC_MethodHook() {
                        @Throws(Throwable::class)
                        override fun beforeHookedMethod(paramd: MethodHookParam) {
                            HLog.d(TAG,"aaa 888 111 findAndHookMethod,process_camera2_init)  method （onOpend）")
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
                                        HLog.d(TAG,"aaa 888 222 findAndHookMethod,process_camera2_init)  method （onOpend）")
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

        // Camera2 第4步调用
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa camera2 step4  android.hardware.camera2.CaptureRequest.Builder)  method （addTarget）")
                    if (param.args[0] != null) {
                        if(param.args[0] == c2_virtual_surface)return
                        val surfaceInfo = param.args[0].toString()
                        if (!surfaceInfo.contains("Surface(name=null)")) {
                            if(original_preview_Surface != param.args[0] as Surface ){
                                original_preview_Surface = param.args[0] as Surface
                            }
                        }else{

                        }
                        if(lpparam.packageName != "com.ss.android.ugc.aweme"){
                            param.args[0] = c2_virtual_surface
                        }
                    }
                }
            })
        // Camera2 第5步调用
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa  camera2 step5  android.hardware.camera2.CaptureRequest.Builder)  method （build）")
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

