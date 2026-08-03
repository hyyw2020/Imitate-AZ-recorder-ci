package com.monkeycode.screenrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.chip.Chip
import com.monkeycode.screenrecorder.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BATTERY_OPTIMIZATION = 1004
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var historyManager: RecordingHistoryManager
    private var config = RecorderConfig()
    private var safUri: Uri? = null
    private var qualityParams: QualityParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isFirstLaunch = true

    // ----- Permission Launchers -----
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) updatePermissionsAndStart()
        else Toast.makeText(this, "需要悬浮窗权限才能使用录制功能", Toast.LENGTH_SHORT).show()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestBatteryOptimization()
        else Toast.makeText(this, "需要通知权限才能后台录制", Toast.LENGTH_LONG).show()
    }

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val fgServiceLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) prepareServiceThenProjection()
        else showForegroundServicePermissionDialog()
    }

    private val safDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, flags)
            safUri = it
            config = config.copy(outputUri = it)
            preferencesManager.saveConfig(config)
            updateOutputDirDisplay(it)
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenRecorderApp.pendingResultCode = result.resultCode
            ScreenRecorderApp.pendingResultData = result.data
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            ScreenRecorderApp.pendingMediaProjection = pm.getMediaProjection(result.resultCode, result.data!!)
            startCountdownThenRecord()
        } else {
            ScreenRecorderApp.recordState = RecordState.IDLE
            ScreenRecorderApp.pendingResultCode = 0
            ScreenRecorderApp.pendingResultData = null
            stopService(Intent(this, ScreenRecorderService::class.java))
            Toast.makeText(this, "用户取消录屏授权", Toast.LENGTH_SHORT).show()
        }
    }

    // ----- Lifecycle -----
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager(this)
        historyManager = RecordingHistoryManager(this)
        config = preferencesManager.loadConfig()

        qualityParams = QualityCalculator.calculate(this, config.qualityPreset)

        setupQualityChips()
        setupAudioModeChips()
        setupCountdownSelector()
        setupThemeToggle()
        updateOutputDirDisplay(config.outputUri)
        updateFreeSpace()
        updateQualityPreview()
        bindButtons()

        if (isFirstLaunch) {
            isFirstLaunch = false
            loadRecentHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        updateFreeSpace()
        loadRecentHistory()

        when (ScreenRecorderApp.recordState) {
            RecordState.FAILED -> {
                val error = ScreenRecorderApp.lastError ?: "未知错误"
                Toast.makeText(this, "录制失败: $error", Toast.LENGTH_LONG).show()
                ScreenRecorderApp.recordState = RecordState.IDLE
                ScreenRecorderApp.lastError = null
            }
            RecordState.STARTING -> Toast.makeText(this, "录制启动中...", Toast.LENGTH_SHORT).show()
            else -> {}
        }
    }

    // ----- UI Setup -----
    private fun setupQualityChips() {
        binding.chipQualityGroup.removeAllViews()
        RecordingPreset.entries.forEach { preset ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = preset.label
                isCheckable = true
                tag = preset
            }
            binding.chipQualityGroup.addView(chip)
            if (preset == config.qualityPreset) binding.chipQualityGroup.check(chip.id)
        }
        binding.chipQualityGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val preset = binding.chipQualityGroup.findViewById<Chip>(checkedIds.first()).tag as RecordingPreset
                config = config.copy(qualityPreset = preset)
                preferencesManager.saveConfig(config)
                qualityParams = QualityCalculator.calculate(this, preset)
                updateQualityPreview()
            }
        }
    }

    private fun setupAudioModeChips() {
        binding.chipAudioAuto.removeAllViews()
        binding.chipAudioManual.removeAllViews()
        val hasQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val autoChip = Chip(this).apply {
            id = View.generateViewId()
            text = AudioMode.AUTO.label
            isCheckable = true
            tag = AudioMode.AUTO
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            setTextColor(android.graphics.Color.WHITE)
        }
        binding.chipAudioAuto.addView(autoChip)
        if (config.audioMode == AudioMode.AUTO) binding.chipAudioAuto.check(autoChip.id)

        AudioMode.entries.filter { it != AudioMode.AUTO }.forEach { mode ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = mode.label
                isCheckable = true
                tag = mode
                if ((mode == AudioMode.SPEAKER_ONLY || mode == AudioMode.MIXED) && !hasQ) {
                    isEnabled = false; alpha = 0.4f
                }
            }
            binding.chipAudioManual.addView(chip)
            if (mode == config.audioMode) binding.chipAudioManual.check(chip.id)
        }
        binding.chipAudioAuto.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                config = config.copy(audioMode = AudioMode.AUTO)
                preferencesManager.saveConfig(config)
                binding.chipAudioManual.clearCheck()
                updateQualityPreview()
            }
        }
        binding.chipAudioManual.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val mode = binding.chipAudioManual.findViewById<Chip>(checkedIds.first()).tag as AudioMode
                config = config.copy(audioMode = mode)
                preferencesManager.saveConfig(config)
                binding.chipAudioAuto.clearCheck()
                updateQualityPreview()
            }
        }
    }

    private fun setupCountdownSelector() {
        binding.segCountdown.removeAllViews()
        val options = listOf(0 to "立即", 3 to "3秒", 5 to "5秒", 10 to "10秒")
        options.forEach { (sec, label) ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                tag = sec
            }
            binding.segCountdown.addView(chip)
            if (sec == config.countdownSeconds) binding.segCountdown.check(chip.id)
        }
        binding.segCountdown.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                config = config.copy(countdownSeconds = binding.segCountdown.findViewById<Chip>(checkedIds.first()).tag as Int)
                preferencesManager.saveConfig(config)
            }
        }
    }

    private fun setupThemeToggle() {
        binding.switchDarkMode.isChecked = preferencesManager.isDarkMode()
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setDarkMode(isChecked)
            binding.root.postDelayed({
                Toast.makeText(this, "重启应用后生效", Toast.LENGTH_SHORT).show()
            }, 100)
        }
    }

    private fun bindButtons() {
        binding.btnStartRecording.setOnClickListener { handleStartRecording() }
        binding.btnSelectDir.setOnClickListener { openSafDirectory() }
        binding.btnExportLog.setOnClickListener { exportLogs() }
        binding.btnClearLog.setOnClickListener { clearLogs() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
    }

    // ----- Quality Preview -----
    private fun updateQualityPreview() {
        val qp = qualityParams ?: return
        val audioLabel = when (config.audioMode) {
            AudioMode.AUTO -> "自动音频"
            AudioMode.MUTE -> "静音"
            AudioMode.MIC_ONLY -> "麦克风"
            AudioMode.SPEAKER_ONLY -> "内部音频"
            AudioMode.MIXED -> "混合音频"
        }
        val estFileSize = estimateFileSizeSeconds(60, qp)
        binding.tvQualitySummary.text =
            "${qp.width}x${qp.height} @${qp.frameRate}fps | ${qp.videoBitRate / 1_000_000}Mbps | $audioLabel\n预计每分钟 ~${formatSize(estFileSize)}"
    }

    private fun estimateFileSizeSeconds(seconds: Int, qp: QualityParams): Long =
        ((qp.videoBitRate + qp.audioBitRate) / 8L) * seconds

    // ----- Recording Flow -----
    private fun handleStartRecording() {
        if (ScreenRecorderApp.recordState == RecordState.RECORDING) {
            Toast.makeText(this, "录制已在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (config.audioMode != AudioMode.MUTE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        checkOverlayAndProceed()
    }

    private fun checkOverlayAndProceed() {
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            updatePermissionsAndStart()
        }
    }

    private fun updatePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestBatteryOptimization()
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") },
                    REQUEST_BATTERY_OPTIMIZATION
                )
            } catch (e: ActivityNotFoundException) {
                prepareServiceThenProjection()
            }
        } else {
            prepareServiceThenProjection()
        }
    }

    private fun prepareServiceThenProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                != PackageManager.PERMISSION_GRANTED) {
                fgServiceLauncher.launch(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                return
            }
        }
        startMediaProjection()
    }

    private fun showForegroundServicePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("缺少权限")
            .setMessage("此设备(${Build.MANUFACTURER}/Android ${Build.VERSION.RELEASE})需手动开启「前台服务(媒体投影)」权限。\n\n请在 系统设置 → 应用 → ${getString(R.string.app_name)} → 权限 中启用。")
            .setPositiveButton("打开设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun startMediaProjection() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    // ----- Countdown -----
    private fun startCountdownThenRecord() {
        val seconds = config.countdownSeconds
        if (seconds <= 0) {
            startServiceWithProjection()
            return
        }
        showCountdownOverlay(seconds) { startServiceWithProjection() }
    }

    private fun showCountdownOverlay(remaining: Int, onComplete: () -> Unit) {
        if (remaining <= 0) {
            onComplete()
            binding.countdownOverlay.visibility = View.GONE
            return
        }
        binding.countdownOverlay.visibility = View.VISIBLE
        binding.countdownOverlay.alpha = 1f
        binding.countdownOverlay.bringToFront()
        binding.tvCountdownText.text = "$remaining"
        binding.tvCountdownText.animate().scaleX(1.5f).scaleY(1.5f).setDuration(500).withEndAction {
            binding.tvCountdownText.animate().scaleX(1f).scaleY(1f).setDuration(500).withEndAction {
                handler.postDelayed({
                    showCountdownOverlay(remaining - 1, onComplete)
                }, 200)
            }
        }
    }

    // ----- Service Start -----
    private fun startServiceWithProjection() {
        binding.countdownOverlay.visibility = View.GONE
        val outPath = resolveOutputPath()
        val intent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_PREPARE
            putExtra(ScreenRecorderService.EXTRA_OUTPUT_PATH, outPath)
            putExtra(ScreenRecorderService.EXTRA_CONFIG, config)
            putExtra(ScreenRecorderService.EXTRA_OUTPUT_URI, config.outputUri?.toString())
            putExtra(ScreenRecorderService.EXTRA_QUALITY_PARAMS, qualityParams)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        moveTaskToBack(true)
    }

    private fun resolveOutputPath(): String {
        val now = Date()
        val pattern = config.fileNamePattern
            .replace("%Y", SimpleDateFormat("yyyy", Locale.getDefault()).format(now))
            .replace("%m", SimpleDateFormat("MM", Locale.getDefault()).format(now))
            .replace("%d", SimpleDateFormat("dd", Locale.getDefault()).format(now))
            .replace("%H", SimpleDateFormat("HH", Locale.getDefault()).format(now))
            .replace("%M", SimpleDateFormat("mm", Locale.getDefault()).format(now))
            .replace("%S", SimpleDateFormat("ss", Locale.getDefault()).format(now))
        val dir = File(getExternalFilesDir(null), "recordings")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$pattern.mp4").absolutePath
    }

    // ----- SAF -----
    private fun openSafDirectory() {
        try { safDirLauncher.launch(null) } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOutputDirDisplay(uri: Uri?) {
        binding.tvOutputDir.text = uri?.lastPathSegment ?: "Movies/ScreenRecorder"
    }

    private fun updateFreeSpace() {
        val moviesDir = File(getExternalFilesDir(null), "recordings")
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val free = moviesDir.freeSpace
        binding.tvFreeSpace.text = "可用空间: ${formatSize(free)}"
    }

    // ----- History -----
    private fun loadRecentHistory() {
        val recent = historyManager.getRecent(3)
        if (recent.isEmpty()) {
            binding.tvHistoryPreview.visibility = View.GONE
            return
        }
        binding.tvHistoryPreview.visibility = View.VISIBLE
        binding.tvHistoryPreview.text = recent.joinToString("\n") {
            val ts = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$ts] ${it.formattedSize} ${it.formattedDuration} ${it.formattedResolution}"
        }
    }

    private fun showHistoryDialog() {
        val all = historyManager.getAll()
        if (all.isEmpty()) {
            Toast.makeText(this, "暂无录制记录", Toast.LENGTH_SHORT).show()
            return
        }
        val items = all.mapIndexed { i, e ->
            "${i + 1}. ${e.fileName}\n   ${e.formattedSize} | ${e.formattedDuration} | ${e.formattedResolution} | ${e.codecName}"
        }
        AlertDialog.Builder(this)
            .setTitle("录制历史 (${all.size})")
            .setItems(items.toTypedArray()) { _, idx ->
                val entry = all[idx]
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.path))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "分享视频"))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法分享: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("清空历史") { _, _ ->
                historyManager.clear()
                loadRecentHistory()
                Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    // ----- Logs -----
    private fun exportLogs() {
        val dir = ScreenRecorderApp.logDir(application)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.sortedByDescending { it.lastModified() }
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "没有日志文件", Toast.LENGTH_SHORT).show()
            return
        }
        val export = File(cacheDir, "screenrecorder_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
        try {
            export.outputStream().use { out ->
                files.forEach { f ->
                    out.write("=== ${f.name} ===\n".toByteArray())
                    f.inputStream().use { it.copyTo(out) }
                    out.write("\n\n".toByteArray())
                }
            }
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", export)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ScreenRecorder Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享日志"))
            Toast.makeText(this, "已导出 ${files.size} 个日志", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        val dir = ScreenRecorderApp.logDir(application)
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "没有日志文件", Toast.LENGTH_SHORT).show()
            return
        }
        files.forEach { it.delete() }
        Toast.makeText(this, "已清除 ${files.size} 个日志", Toast.LENGTH_SHORT).show()
    }

    // ----- Helpers -----
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BATTERY_OPTIMIZATION) startMediaProjection()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
