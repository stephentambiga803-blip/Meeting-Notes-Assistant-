package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isRecording = false

    fun startRecording(fileName: String): File? {
        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(outputDir, "$fileName.m4a")
        currentFile = file

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                Log.d("AudioRecorder", "Recording started: ${file.absolutePath}")
            } catch (e: IOException) {
                Log.e("AudioRecorder", "prepare() failed", e)
                currentFile = null
            } catch (e: IllegalStateException) {
                Log.e("AudioRecorder", "start() failed", e)
                currentFile = null
            }
        }
        return currentFile
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "stopRecording() failed", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
        val file = currentFile
        currentFile = null
        return file
    }

    fun getAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
