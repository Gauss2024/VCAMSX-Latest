package com.wangyiheng.vcamsx.utils

import android.media.MediaPlayer
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook

/**
 * GaussVideoDecoder 通过 MediaPlayer 播放指定视频，并将解码后的视频输出到指定的 Surface。
 */
class GaussVideoDecoder {
    companion object {
        private var mediaPlayer: MediaPlayer? = null

        /**
         * 启动视频播放
         *
         * @param videoUrl 视频地址
         * @param surface 视频输出的目标 Surface
         */
        @JvmStatic
        fun start(videoUrl: String, surface: Surface) {
            if (mediaPlayer != null) {
                 HLog.d(MainHook.TAG, "MediaPlayer is already running.")
                return
            }
            try {
                mediaPlayer = MediaPlayer().apply {
                    setSurface(surface)
                    setDataSource(videoUrl)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        mp.start()
                         HLog.d(MainHook.TAG, "MediaPlayer started playing.")
                    }
                    setOnErrorListener { mp, what, extra ->
                         HLog.d(MainHook.TAG, "Error occurred: what=$what, extra=$extra")
                        true
                    }
                    prepareAsync()  // 异步准备
                }
            } catch (e: Exception) {
                 HLog.d(MainHook.TAG, "Error starting video decoder: ${e.message}")
            }
        }

        /**
         * 停止视频播放并释放资源
         */
        @JvmStatic
        fun stop() {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                 HLog.d(MainHook.TAG, "MediaPlayer stopped.")
            } catch (e: Exception) {
                 HLog.d(MainHook.TAG, "Error stopping video decoder: ${e.message}")
            }
        }
    }
}
