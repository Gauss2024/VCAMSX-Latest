package com.wangyiheng.vcamsx.utils

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook
import java.io.IOException

object VideoMediaPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentSurface: Surface? = null

    fun setupMediaPlayer(surfaceTexture: SurfaceTexture) {
        release() // 释放之前的资源

        mediaPlayer = MediaPlayer().apply {
            setSurface(Surface(surfaceTexture))
            try {
                setDataSource("/sdcard/10.mp4")
                isLooping = true
                prepareAsync()
                setOnPreparedListener { start() }
            } catch (e: IOException) {
                 HLog.d(MainHook.TAG, "Error setting up media player e=${e.message}")
            }
        }
        currentSurface = Surface(surfaceTexture)
    }

    private fun release() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        currentSurface?.release()
        mediaPlayer = null
        currentSurface = null
    }
}