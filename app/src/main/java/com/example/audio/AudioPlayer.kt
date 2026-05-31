package com.example.audio

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    var isPlaying by mutableStateOf(false)
        private set

    var currentProgress by mutableStateOf(0f) // 0f to 1f
        private set

    var currentDuration by mutableStateOf(0) // total duration in ms
        private set

    var currentPositionState by mutableStateOf(0) // current elapsed ms
        private set

    private var progressThread: Thread? = null

    fun playAudio(filePath: String, onCompletion: () -> Unit) {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e("AudioPlayer", "File does not exist: $filePath")
            return
        }

        stopAudio()

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(filePath)
                prepare()
                start()
                this@AudioPlayer.isPlaying = true
                this@AudioPlayer.currentDuration = duration
                
                startProgressTracker(onCompletion)
            } catch (e: Exception) {
                Log.e("AudioPlayer", "play() failed", e)
            }
        }
    }

    fun pauseAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isPlaying = false
            } else {
                it.start()
                isPlaying = true
            }
        }
    }

    fun stopAudio() {
        progressThread?.interrupt()
        progressThread = null
        
        mediaPlayer?.apply {
            if (isPlaying) {
                try {
                    stop()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            release()
        }
        mediaPlayer = null
        isPlaying = false
        currentProgress = 0f
        currentPositionState = 0
    }

    private fun startProgressTracker(onCompletion: () -> Unit) {
        progressThread = Thread {
            try {
                while (mediaPlayer != null && isPlaying) {
                    val current = mediaPlayer?.currentPosition ?: 0
                    val total = currentDuration
                    if (total > 0) {
                        currentProgress = current.toFloat() / total.toFloat()
                        currentPositionState = current
                    }
                    Thread.sleep(100)
                }
            } catch (e: InterruptedException) {
                // Thread stopped
            }
        }.apply { start() }

        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            currentProgress = 0f
            currentPositionState = 0
            onCompletion()
        }
    }
}
