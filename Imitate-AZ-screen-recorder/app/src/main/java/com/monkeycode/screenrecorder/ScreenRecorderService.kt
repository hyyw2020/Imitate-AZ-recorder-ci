package com.monkeycode.screenrecorder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

class ScreenRecorderService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.monkeycode.screenrecorder.PREPARE"
        const val ACTION_START = "com.monkeycode.screenrecorder.START"
        const val ACTION_STOP = "com.monkeycode.screenrecorder.STOP"
        const val ACTION_PAUSE = "com.monkeycode.screenrecorder.PAUSE"
        const val ACTION_RESUME = "com.monkeycode.screenrecorder.RESUME"

        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_CONFIG = "recorder_config"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_QUALITY_PARAMS = "quality_params"

        const val NOTIFICATION_ID_RECORDING = 2001
        const val NOTIFICATION_ID_COUNTDOWN = 2002
        private const val TAG = "ScreenRecorderSvc"
    }

    private val lock = ReentrantLock()
    @Volatile private var svcState = RecordState.IDLE
    @Volatile private var paused = false
    private var engine: RecorderEngine? = null
    private var floatingView: FloatingView? = null
    private var notificationManager: NotificationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var config: RecorderConfig = RecorderConfig()
    private var qualityParams: QualityParams? = null
    private var outputPath: String = ""
    private var diagLogFile: File? = null
    private var historyManager: RecordingHistoryManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        historyManager = RecordingHistoryManager(this)

        val logDir = ScreenRecorderApp.logDir(application)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        diagLogFile = File(logDir, "recorder_${ts}.log")
        writeLog("Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        lock.withLock {
            when (action) {
                ACTION_PREPARE -> handlePrepare(intent)
                ACTION_STOP -> handleStop()
                ACTION_PAUSE -> handlePause()
                ACTION_RESUME -> handleResume()
            }
        }
        return START_NOT_STICKY
    }

    private fun handlePrepare(intent: Intent) {
        if (svcState == RecordState.RECORDING) {
            writeLog("PREPARE 忽略: 已在录制")
            return
        }
        if (svcState == RecordState.STARTING) {
            writeLog("PREPARE 忽略: 已在启动中")
            return
        }
        svcState = RecordState.PREPARING
        writeLog("PREPARE: 启动前台通知")

        config = intent.getSerializableExtra(EXTRA_CONFIG) as? RecorderConfig ?: RecorderConfig()
        outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: defaultOutputPath()
        qualityParams = intent.getSerializableExtra(EXTRA_QUALITY_PARAMS) as? QualityParams
            ?: QualityCalculator.calculate(this, config.qualityPreset)

        val uriStr = intent.getStringExtra(EXTRA_OUTPUT_URI)
        if (!uriStr.isNullOrEmpty()) {
            config = config.copy(outputUri = Uri.parse(uriStr))
        }

        startForeground(NOTIFICATION_ID_COUNTDOWN, buildPrepareNotification())
        showFloatingView()

        // Transition to START immediately
        handleStart()
    }

    private fun handleStart() {
        svcState = RecordState.STARTING
        val qp = qualityParams ?: return writeLog("ERROR 质量参数缺失")
        val mp = ScreenRecorderApp.pendingMediaProjection
        val resultCode = ScreenRecorderApp.pendingResultCode
        val resultData = ScreenRecorderApp.pendingResultData
        if (mp == null || resultData == null) {
            writeLog("ERROR MediaProjection 数据缺失")
            svcState = RecordState.FAILED
            ScreenRecorderApp.lastError = "MediaProjection 数据缺失"
            stopSelf()
            return
        }

        writeLog("输出路径: $outputPath")
        writeLog("配置: audioMode=${config.audioMode} quality=${config.qualityPreset} qp=${qp.width}x${qp.height}@${qp.frameRate}fps")

        acquireWakeLock()
        showFloatingView()
        updateNotificationToRecording()

        engine = RecorderEngine(this, "recorder-${System.currentTimeMillis()}")
        engine?.setCallback(object : RecorderEngine.Callback {
            override fun onStarted(codecName: String) {
                svcState = RecordState.RECORDING
                writeLog("编码器: $codecName")
            }
            override fun onStopped(durationMs: Long, fileSize: Long, outputPath: String) {
                writeLog("引擎已停止, 输出: ${resolveOutputUri(outputPath)}")
                recordToHistory(durationMs, fileSize, outputPath)
                cleanupAndStop()
            }
            override fun onError(message: String, throwable: Throwable?) {
                writeLog("引擎报错: $message")
                ScreenRecorderApp.lastError = message
                svcState = RecordState.FAILED
                cleanupAndStop()
            }
            override fun onEncoderChanged(newCodec: String) {
                writeLog("编码器切换: $newCodec")
            }
        })

        try {
            engine?.start(RecorderEngine.StartConfig(mp, resultCode, resultData, outputPath, qp, config.audioMode))
            writeLog("录制引擎已启动")
        } catch (e: Exception) {
            writeLog("ERROR 启动录制异常: ${e.javaClass.simpleName}: ${e.message}")
            writeLog("堆栈: ${e.stackTrace.take(3).joinToString(" | ")}")
            ScreenRecorderApp.lastError = "${e.javaClass.simpleName}: ${e.message}"
            svcState = RecordState.FAILED
            try { engine?.stop() } catch (_: Exception) {}
            cleanupAndStop()
        }
    }

    private fun handleStop() {
        if (svcState != RecordState.RECORDING && svcState != RecordState.STARTING) {
            writeLog("STOP 忽略: svcState=$svcState")
            return
        }
        writeLog("===== 停止录制 =====")
        engine?.stop()
    }

    private fun handlePause() {
        if (svcState != RecordState.RECORDING) return
        paused = true
        svcState = RecordState.PAUSED
        writeLog("暂停录制")
        engine?.pause()
        updateNotificationToRecording()
    }

    private fun handleResume() {
        if (svcState != RecordState.PAUSED) return
        paused = false
        svcState = RecordState.RECORDING
        writeLog("恢复录制")
        engine?.resume()
        updateNotificationToRecording()
    }

    private fun recordToHistory(durationMs: Long, fileSize: Long, filePath: String) {
        val qp = qualityParams
        val codec = "c2.android.avc.encoder" // best effort
        historyManager?.add(
            RecordingEntry(
                fileName = File(filePath).name,
                path = filePath,
                durationMs = durationMs,
                sizeBytes = fileSize,
                width = qp?.width ?: 0,
                height = qp?.height ?: 0,
                timestamp = System.currentTimeMillis(),
                codecName = codec
            )
        )
    }

    private fun resolveOutputUri(localPath: String): String {
        val uri = config.outputUri
        return if (uri != null) uri.toString() else localPath
    }

    private fun cleanupAndStop() {
        releaseWakeLock()
        floatingView?.hide()
        floatingView = null
        notificationManager?.cancel(NOTIFICATION_ID_RECORDING)
        notificationManager?.cancel(NOTIFICATION_ID_COUNTDOWN)
        ScreenRecorderApp.recordState = RecordState.IDLE
        ScreenRecorderApp.pendingMediaProjection = null
        ScreenRecorderApp.pendingResultCode = 0
        ScreenRecorderApp.pendingResultData = null
        writeLog("===== 服务已停止 =====")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ScreenRecorder:WakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun showFloatingView() {
        if (!config.showFloatingBall || floatingView != null) return
        floatingView = FloatingView(this).apply {
            setOnStopListener { handleStop() }
            setOnPauseListener { handlePause() }
            setOnResumeListener { handleResume() }
        }
        floatingView?.show()
    }

    // ----- Notifications -----
    private fun buildPrepareNotification(): Notification {
        return NotificationCompat.Builder(this, ScreenRecorderApp.CHANNEL_RECORDING)
            .setContentTitle("准备录制")
            .setContentText("正在启动...")
            .setSmallIcon(R.drawable.ic_indicator)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationToRecording() {
        val isPaused = paused
        val actionIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val actionAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val title = if (isPaused) "录制已暂停" else "正在录制"

        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val pauseIntent = PendingIntent.getService(this, 1,
            Intent(this, ScreenRecorderService::class.java).setAction(actionAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, ScreenRecorderApp.CHANNEL_RECORDING)
            .setContentTitle(title)
            .setContentText("${qualityParams?.width}x${qualityParams?.height} @${qualityParams?.frameRate}fps")
            .setSmallIcon(R.drawable.ic_indicator)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(actionIcon, if (isPaused) "继续" else "暂停", pauseIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setColorized(true)
            .setColor(if (isPaused) Color.parseColor("#FF9800") else Color.parseColor("#E53935"))
            .build()

        notificationManager?.notify(NOTIFICATION_ID_RECORDING, notification)
    }

    // ----- Logging -----
    private fun writeLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "$ts $msg"
        Log.d(TAG, line)
        try {
            diagLogFile?.appendText("$line\n")
        } catch (_: Exception) {}
    }

    private fun defaultOutputPath(): String {
        val dir = File(getExternalFilesDir(null), "recordings")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "ScreenRecord_$ts.mp4").absolutePath
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        writeLog("Service onDestroy")
        releaseWakeLock()
        floatingView?.hide()
        super.onDestroy()
    }
}
