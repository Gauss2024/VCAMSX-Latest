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
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.utils.InfoProcesser.videoStatus
import com.wangyiheng.vcamsx.utils.OutputImageFormat
import com.wangyiheng.vcamsx.utils.VideoPlayer.c1_camera_play
import com.wangyiheng.vcamsx.utils.VideoPlayer.camera2Play
import com.wangyiheng.vcamsx.utils.VideoPlayer.ijkMediaPlayer
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


        //Camera1
        @Volatile
        var data_buffer = byteArrayOf(0)
        var context: Context? = null
        var origin_preview_camera: Camera? = null
        var fake_SurfaceTexture: SurfaceTexture? = null
        var c1FakeTexture: SurfaceTexture? = null
        var c1FakeSurface: Surface? = null
        var mcamera1: Camera? = null
        var oriHolder: SurfaceHolder? = null
        var original_c1_preview_SurfaceTexture:SurfaceTexture? = null
        var original_c1_preview_SurfaceTexture_value:Long=0
        var camera_onPreviewFrame: Camera? = null
        var camera_callback_calss: Class<*>? = null
        var hw_decode_obj: VideoToFrames? = null


        private const val VIDEO_PATH = "/sdcard/10.mp4" // 替换视频路径

        //Camera2
        var sessionConfiguration: SessionConfiguration? = null
        var outputConfiguration: OutputConfiguration? = null
        var fake_sessionConfiguration: SessionConfiguration? = null
        var needRecreate: Boolean = false
        var c2VirtualSurfaceTexture: SurfaceTexture? = null
        var c2_reader_Surfcae: Surface? = null

        //RTMP 网络流
        var original_preview_Surface: Surface? = null

        //RTMP,MediaPlayer Camere2 使用，播放声音使用，在VideoPlayer中使用
        var isPlaying:Boolean = false

    }

    private var c2_virtual_surface: Surface? = null
    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null


    // 维护 Camera 实例与 SurfaceTexture 的关联
    val cameraSurfaceMap = WeakHashMap<Camera, SurfaceTexture>()



    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }

//        //每次都会调用的
//        if (lpparam.packageName.equals("com.smile.gifmaker")) {
//           // HLog.d(TAG,"aaa 快手。。。。。。。")
//
//
//        }
                if (lpparam.packageName.equals("androidx.camera.core")) {
           // HLog.d(TAG,"aaa androidx.camera.core。。。。。。。")
        }
        if (lpparam.packageName.equals("androidx.camera.lifecycle")) {
            // HLog.d(TAG,"aaa androidx.camera.lifecycle。。。。。。。")
        }






        //获取context，camera1与Camera2都会用
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    HLog.d(TAG,"aaa 111 findAndHookMethod,(android.app.Instrumentation) method （callApplicationOnCreate）")
                    HLog.d(TAG,"aaa 111  callApplicationOnCreate before param.result=${param!!.result}")
                    printParams(param!!)

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



//        XposedHelpers.findAndHookMethod(
//            "android.hardware.Camera",
//            lpparam.classLoader,
//            "setPreviewCallbackWithBuffer",
//            "android.hardware.Camera\$PreviewCallback",
//            object : XC_MethodHook() {
//                override fun afterHookedMethod(param: MethodHookParam) {
//                    HLog.d(MainHook.TAG, "aaa 000 setPreviewCallbackWithBuffer afterHookedMethod PreviewCallback")
//
////                    param.args[0] = CustomPreviewCallback(originalCallback)
//                    val currentCamera = param.thisObject as Camera
//                    val currentSurface = cameraSurfaceMap[currentCamera]
//                    HLog.d(MainHook.TAG,"setPreviewCallbackWithBuffer PreviewCallback触发，当前SurfaceTexture: $currentSurface")
//
//                   // currentSurface?.let { analyzeFields(it) }
//
//                    var currentTextureValue =getSurefaceTextureNativeValue(currentSurface!!)
//                    HLog.d(TAG,"aaa 0000  在PreviewCall中   currentTextureValue=$currentTextureValue，original_c1_preview_SurfaceTexture_value=$original_c1_preview_SurfaceTexture_value")
//
//                    if(original_c1_preview_SurfaceTexture_value!=currentTextureValue){
//                        HLog.d(TAG,"aaa 0000  在PreviewCall中   重新创建了surfaceTexture或camera。。。")
//                        original_c1_preview_SurfaceTexture = currentSurface
//                        origin_preview_camera = currentCamera
//                        cameraSurfaceMap[origin_preview_camera] = original_c1_preview_SurfaceTexture
//                    }
//
//                }
//            })


        // Hook Camera.open()
//        XposedHelpers.findAndHookMethod(
//            Camera::class.java,
//            "open",
//            Int::class.javaPrimitiveType,
//            object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    HLog.d(MainHook.TAG, "aaa 000  Camera open before param.result=${param!!.result}")
//                    printParams(param!!)
//                }
//                override fun afterHookedMethod(param: MethodHookParam) {
//                    HLog.d(MainHook.TAG, "aaa 000  Camera open after param.result=${param!!.result}")
//                    val cameraId = param.args[0] as Int
//                    printParams(param!!)
//                    val curentCamera = param.result as Camera
//                    HLog.d(MainHook.TAG, "aaa 000 Camera opened (id=$cameraId)   width=${curentCamera.parameters.previewSize.width},height=${curentCamera.parameters.previewSize.height}")
//                }
//            })

        // Hook Camera.release()
//        XposedHelpers.findAndHookMethod(
//            Camera::class.java,
//            "release",
//            object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    HLog.d(MainHook.TAG, "aaa 000  Camera released before param.result=${param.result}")
//                    printParams(param!!)
//
//                }
//
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    HLog.d(MainHook.TAG, "aaa 000  Camera released after param.result=${param!!.result} ")
//                    printParams(param!!)
//                }
//            })

        // 支持bilibili摄像头替换
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
            SurfaceTexture::class.java, object : XC_MethodHook() {
                @SuppressLint("NewApi")
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 获取 SurfaceTexture 的缓冲区大小
                    HLog.d(TAG,"aaa 222   findAndHookMethod,(android.hardware.Camera) method （setPreviewTexture）  param.args[0]=${param.args[0]} fake_SurfaceTexture=${fake_SurfaceTexture}")
                    if (param.args[0] == null) {
                        return
                    }
                    if (param.args[0] == fake_SurfaceTexture) {
                        return
                    }
                    if (origin_preview_camera != null && origin_preview_camera == param.thisObject) {
                        param.args[0] = fake_SurfaceTexture
                        return
                    }
                    HLog.d(TAG,"aaa 222  startViewTexture 标准工作  重新创建了surfaceTexture或camera。。。")
                    origin_preview_camera = param.thisObject as Camera
                    original_c1_preview_SurfaceTexture = param.args[0] as SurfaceTexture

                    var currentTextureValue =getSurefaceTextureNativeValue(original_c1_preview_SurfaceTexture!!)
                    original_c1_preview_SurfaceTexture_value = currentTextureValue
                    HLog.d(TAG,"aaa 0000  startViewTexture中 beforeHook  original_c1_preview_SurfaceTexture_value=$currentTextureValue")

                    cameraSurfaceMap[origin_preview_camera] = original_c1_preview_SurfaceTexture

                    fake_SurfaceTexture = if (fake_SurfaceTexture == null) {
                        SurfaceTexture(10)
                    } else {
                        fake_SurfaceTexture!!.release()
                        SurfaceTexture(10)
                    }
                    param.args[0] = fake_SurfaceTexture

                }

//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    HLog.d(TAG,"aaa 222 333  afterHookedMethod。。。")
//                   // orig?.let { analyzeFields(it) }
//                    var currentTextureValue =getSurefaceTextureNativeValue(original_c1_preview_SurfaceTexture!!)
//                    HLog.d(TAG,"aaa 0000  startViewTexture中   afterHook original_c1_preview_SurfaceTexture_value=$currentTextureValue")
//                }


            })






        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                HLog.d(TAG,"aaa 333 findAndHookMethod,(android.hardware.Camera)  method （startPreview）mediaPlayer准备播放 ")
                //播放声音
                c1_camera_play()
            }
        })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 000 setPreviewCallbackWithBuffer。。。。。。。。。。。")
                    //替换摄像头数据
                    //param.args[0] = null // 禁用原始预览回调
                    if(videoStatus?.isVideoEnable == false) return

                    if (param.args[0] != null) {
                      // process_callback(param)
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback",
            PreviewCallback::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    HLog.d(TAG,"aaa 000 setPreviewCallback。。。。。。。。。。。")

                    //param.args[0] = null // 禁用原始预览回调
                }
            })

        //这里的param只有一个，0，数组
        var addbufferPint=5
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
            ByteArray::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
//                    if(addbufferPint<5){
//                        HLog.d(TAG,"aaa 555 findAndHookMethod,(android.hardware.Camera)  method （addCallbackBuffer）resut=${param.result}")
//                        printParams(param!!)
//                        addbufferPint--
//                    }

                   // val localcam = param.args[1] as Camera

                    //这里干什么呢？，给预览使用，替换预览画面,一直会添加数据的
               // HLog.d(TAG,"aaa 555 findAndHookMethod,(android.hardware.Camera)  method （addCallbackBuffer）")
                    //param.args[0] = null // 禁用原始预览回调
//                    if (param.args[0] != null) {
//                        param.args[0] = ByteArray((param.args[0] as ByteArray).size)
//                    }
                }
            })

//测试Media         //android.media.MediaRecorder  findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback)
        XposedHelpers.findAndHookMethod(
            MediaRecorder::class.java, "setVideoSource", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {



                    HLog.d(TAG,"aaa  xxx android.media.MediaRecorder method （setVideoSource）")

                }
            })
//getInstance         //android.media.MediaRecorder
// Hook ProcessCameraProvider.getInstance 方法
        XposedHelpers.findAndHookMethod(
            ProcessCameraProvider::class.java,
            "getInstance",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {

                    HLog.d(TAG,"aaa xxx ProcessCameraProvider################")

                    // Hook CameraSelector.create 方法
                    XposedHelpers.findAndHookMethod(
                        CameraSelector::class.java,
                        "create",
                        CameraSelector.Builder::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam?) {
                                super.afterHookedMethod(param)

                                HLog.d(TAG,"aaa xxx CameraSelector create################")


                                // Hook Camera.get 方法
                                XposedHelpers.findAndHookMethod(
                                    Camera::class.java,
                                    "get",
                                    ImageCapture::class.java,
                                    object : XC_MethodHook() {
                                        override fun afterHookedMethod(param: MethodHookParam?) {
                                            super.afterHookedMethod(param)

                                            HLog.d(TAG,"aaa xxx Camera get################")

                                            // 替换ImageCapture的实现，返回视频流
                                            val mediaRecorder = MediaRecorder()
                                            // 配置MediaRecorder...
//                                            imageCapture.setSessionProcessor(object  : ImageCapture.SessionProcessor {
//                                                override fun process(imageProxy: ImageProxy) {
//                                                    // 将图像转换为视频帧，并写入MediaRecorder
//                                                    mediaRecorder.writeVideoFrame(imageProxy.image)
//                                                    imageProxy.close()
//                                                }
//                                            })
                                        }
                                    })
                            }
                        })
                }
            })

        //快手不使用对应orgiHolder
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(TAG,"aaa 666 findAndHookMethod,(android.hardware.Camera)  method （setPreviewDisplay）")
                mcamera1 = param.thisObject as Camera
                oriHolder = param.args[0] as SurfaceHolder
                if (c1FakeTexture == null) {
                    c1FakeTexture = SurfaceTexture(11)
                } else {
                    c1FakeTexture!!.release()
                    c1FakeTexture = SurfaceTexture(11)
                }

                if (c1FakeSurface == null) {
                    c1FakeSurface = Surface(c1FakeTexture)
                } else {
                    c1FakeSurface!!.release()
                    c1FakeSurface = Surface(c1FakeTexture)
                }
                mcamera1!!.setPreviewTexture(c1FakeTexture)
                param.result = null
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
    var printtime=5
    private fun process_callback(param: MethodHookParam) {
        val preview_cb_class: Class<*> = param.args[0].javaClass
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame",
            ByteArray::class.java,
            Camera::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(paramd: MethodHookParam) {
                    HLog.d(TAG,"aaa  findAndHookMethod,"+preview_cb_class.name+")  method （onPreviewFrame）")
                    val currentCamera = paramd.args[1] as Camera

                    if(printtime>0){
                        printParams(param)
                        val currentSurface = cameraSurfaceMap[currentCamera]
                        var currentTextureValue =getSurefaceTextureNativeValue(currentSurface!!)
                        HLog.d(TAG,"aaa 0000  在PonPreviewFrame中   currentTextureValue=$currentTextureValue，original_c1_preview_SurfaceTexture_value=$original_c1_preview_SurfaceTexture_value")

                        printtime--
                    }



                    if (currentCamera ==  camera_onPreviewFrame) {
                        while ( data_buffer == null) {
                        }
                        System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                    } else {
                        camera_callback_calss = preview_cb_class
                        camera_onPreviewFrame = currentCamera
                        val mwidth = camera_onPreviewFrame!!.getParameters().getPreviewSize().width
                        val mhight = camera_onPreviewFrame!!.getParameters().getPreviewSize().height
                        if ( hw_decode_obj != null) {
                            hw_decode_obj!!.stopDecode()
                        }
                        Toast.makeText(context, """
                                视频需要分辨率与摄像头完全相同
                                宽：${mwidth}
                                高：${mhight}
                                """.trimIndent(), Toast.LENGTH_SHORT).show()
                        hw_decode_obj = VideoToFrames()
                        hw_decode_obj!!.setSaveFrames(OutputImageFormat.NV21)

                        val videoUrl = "content://com.wangyiheng.vcamsx.videoprovider"
                        val videoPathUri = Uri.parse(videoUrl)
                        //update gauss
                        hw_decode_obj!!.decode( videoPathUri )
                        while ( data_buffer == null) {
                        }
                        System.arraycopy(data_buffer, 0, paramd.args[0], 0, min(data_buffer.size.toDouble(), (paramd.args[0] as ByteArray).size.toDouble()).toInt())
                    }
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


    private fun analyzeFields(analyObj: Any) {
        try {
            val analyClass = analyObj.javaClass
            val fields = analyClass.declaredFields

            HLog.d(MainHook.TAG,"className=${ analyClass.name} =====Fields =====")
            fields.forEach { field ->
                try {
                    field.isAccessible = true
                    val name = field.name
                    val value = field.get(analyObj)
                    val type=field.type
                    HLog.d(MainHook.TAG,"Field: $name | type: $type  | Value: $value")
                } catch (e: IllegalAccessException) {
                    HLog.d(MainHook.TAG,"无法访问字段 ${field.name}")
                }
            }
        } catch (e: Exception) {
            HLog.d(MainHook.TAG,"分析回调失败: ${e.message}")
        }
    }
    private fun getSurefaceTextureNativeValue(analyObj: Any): Long {
        try {
            val analyClass = analyObj.javaClass
            val fieldsByName = analyClass.declaredFields.filter  { it.name  == "mSurfaceTexture" }
            fieldsByName.forEach  {
                field ->
                field.isAccessible = true
                return field.get(analyObj) as Long
            }
        } catch (e: Exception) {
            HLog.d(MainHook.TAG,"分析回调失败: ${e.message}")
        }
        return 0
    }
    private fun printParams(param:MethodHookParam){
        HLog.d(MainHook.TAG,"printParams........................................")
        for (i in param.args.indices) {
            HLog.d(TAG, "#### printParams pram Argument $i: ${param.args[i]}")
        }
    }



}

