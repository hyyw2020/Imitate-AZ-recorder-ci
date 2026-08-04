package com.monkeycode.screenrecorder

import android.net.Uri
import java.io.Serializable

enum class RecordState {
    IDLE, PREPARING, STARTING, RECORDING, PAUSED, STOPPING, FAILED
}

enum class AudioMode(val label: String, val desc: String) {
    AUTO("自动", "智能选择"),
    MUTE("静音", "不录制音频"),
    MIC_ONLY("麦克风", "仅麦克风"),
    SPEAKER_ONLY("内部音频", "系统内部音频 (Android 10+)"),
    MIXED("混合", "麦克风+内部音频 (Android 10+)")
}

enum class RecordingPreset(val label: String, val desc: String) {
    ECONOMY("节能", "省空间"),
    STANDARD("标准", "均衡"),
    HIGH("高质量", "推荐"),
    ULTRA("超清", "大文件")
}

data class QualityParams(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val videoBitRate: Int,
    val audioBitRate: Int,
    val dpi: Int
) : Serializable

data class RecorderConfig(
    val audioMode: AudioMode = AudioMode.AUTO,
    val qualityPreset: RecordingPreset = RecordingPreset.HIGH,
    val countdownSeconds: Int = 3,
    val showFloatingBall: Boolean = true,
    val fileNamePattern: String = "ScreenRecord_%Y%m%d_%H%M%S",
    @kotlin.jvm.Transient val outputUri: Uri? = null
) : Serializable
