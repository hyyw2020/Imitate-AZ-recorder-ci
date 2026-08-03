package com.monkeycode.screenrecorder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecorderApp : Application() {

    companion object {
        const val CHANNEL_RECORDING = "recording_channel"
        const val PREFS_NAME = "recorder_prefs"

        var lastError: String? = null
        var pendingMediaProjection: android.media.projection.MediaProjection? = null
        var pendingResultCode: Int = 0
        var pendingResultData: Intent? = null
        var recordState: RecordState = RecordState.IDLE

        fun logDir(app: Application): File {
            val dir = File(app.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        setupGlobalExceptionHandler()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                "录制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕录制前台服务通知"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val crashLog = "Crash [${thread.name}] ${throwable.javaClass.name}: ${throwable.message}\n${sw}"

            try {
                val dir = logDir(this)
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "crash_${ts}.txt")
                file.writeText(crashLog)
                Log.e("ScreenRecorderApp", crashLog)
            } catch (_: Exception) {}

            lastError = "${throwable.javaClass.simpleName}: ${throwable.message}"

            // Restart clean
            recordState = RecordState.IDLE
            pendingMediaProjection = null
            pendingResultData = null

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
