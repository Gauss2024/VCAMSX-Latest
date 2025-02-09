package com.wangyiheng.vcamsx

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.VideoDecoder
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
        private var fakeSurfaceTexture: SurfaceTexture? = null
        private var fakeSurface: Surface? = null
        private val isDecoding = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())

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

                                    initializeFakeSurface()

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

        // 支持bilibili摄像头替换
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @SuppressLint("NewApi")
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture） fakeSurfaceTexture=$fakeSurfaceTexture")
                    if (fakeSurfaceTexture == null) {
                        Handler(Looper.getMainLooper()).post {
                            if (fakeSurfaceTexture?.isReleased != false) {
                                initializeFakeSurface()
                            }
                            param.args[0] = fakeSurfaceTexture

                        }
                    }
                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture） fakeSurfaceTexture 替换成功")
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                HLog.d(TAG,"aaa 333 findAndHookMethod,(android.hardware.Camera)  method （startPreview） ")
                if (isDecoding.compareAndSet(false, true)) {
                    GlobalScope.launch(Dispatchers.IO) {
                        VideoDecoder.startDecoding(context, fakeSurface)
                    }
                }
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


    private fun initializeFakeSurface() {
        mainHandler.post {
            releaseFakeSurface()
            fakeSurfaceTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(1920, 1080)
                detachFromGLContext()
            }
            fakeSurface = Surface(fakeSurfaceTexture)
            HLog.d(TAG, "FakeSurface initialized")
        }
    }

    private fun releaseFakeSurface() {
        fakeSurface?.release()
        fakeSurfaceTexture?.release()
        fakeSurface = null
        fakeSurfaceTexture = null
        HLog.d(TAG, "FakeSurface released")
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

