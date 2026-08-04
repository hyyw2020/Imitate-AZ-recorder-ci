package com.monkeycode.screenrecorder

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

object QualityCalculator {
    fun calculate(context: Context, preset: RecordingPreset): QualityParams {
        val displayMetrics = getDisplayMetrics(context)
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels
        val refreshRate = getRefreshRate(context)
        val dpi = getDisplayDensityDpi(context, displayMetrics)
        val targetRes = alignTo16(clampToResolution(displayWidth, displayHeight, preset))
        val calculatedFps = calculateFrameRate(refreshRate, preset)
        val videoBitrate = calculateBitrate(targetRes.first, targetRes.second, calculatedFps, preset)
        val audioBitrate = calculateAudioBitrate(preset)

        return QualityParams(targetRes.first, targetRes.second, calculatedFps, videoBitrate, audioBitrate, dpi)
    }

    private fun clampToResolution(w: Int, h: Int, preset: RecordingPreset): Pair<Int, Int> {
        val maxSide = maxOf(w, h)
        val limit = when (preset) {
            RecordingPreset.ECONOMY -> 1280
            RecordingPreset.STANDARD -> 1920
            RecordingPreset.HIGH -> maxSide
            RecordingPreset.ULTRA -> maxSide
        }
        return if (maxSide <= limit) Pair(w, h) else {
            val scale = limit.toFloat() / maxSide
            Pair((w * scale).toInt(), (h * scale).toInt())
        }
    }

    private fun alignTo16(res: Pair<Int, Int>): Pair<Int, Int> {
        var w = (res.first + 15) / 16 * 16
        var h = (res.second + 15) / 16 * 16
        w = w.coerceIn(320, 4096)
        h = h.coerceIn(240, 4096)
        return Pair(w, h)
    }

    private fun calculateFrameRate(refreshRate: Float, preset: RecordingPreset): Int {
        val base = when (preset) {
            RecordingPreset.ECONOMY -> 24
            RecordingPreset.STANDARD -> 30
            RecordingPreset.HIGH -> refreshRate.toInt()
            RecordingPreset.ULTRA -> refreshRate.toInt()
        }
        return base.coerceIn(15, 120)
    }

    private fun calculateBitrate(w: Int, h: Int, fps: Int, preset: RecordingPreset): Int {
        val bpp = when (preset) {
            RecordingPreset.ECONOMY -> 0.06
            RecordingPreset.STANDARD -> 0.08
            RecordingPreset.HIGH -> 0.10
            RecordingPreset.ULTRA -> 0.12
        }
        val raw = (w * h * fps * bpp).toInt()
        return raw.coerceIn(1_000_000, 200_000_000)
    }

    private fun calculateAudioBitrate(preset: RecordingPreset): Int = when (preset) {
        RecordingPreset.ECONOMY -> 96_000
        RecordingPreset.STANDARD -> 128_000
        RecordingPreset.HIGH -> 192_000
        RecordingPreset.ULTRA -> 256_000
    }

    private fun getDisplayMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
            val metrics = context.resources.displayMetrics
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics
        }
    }

    private fun getRefreshRate(context: Context): Float {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.mode.refreshRate
        } else {
            60f
        }
    }

    private fun getDisplayDensityDpi(context: Context, metrics: DisplayMetrics): Int {
        val dpi = metrics.densityDpi
        return if (dpi > 0) dpi else 160
    }
}
