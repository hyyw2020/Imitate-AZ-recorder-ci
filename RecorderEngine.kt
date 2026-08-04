package com.monkeycode.screenrecorder

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Range
import android.view.Surface
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class RecorderEngine(
    private val context: Context,
    private val logTag: String = "RecorderEngine"
) {
    companion object {
        private const val TAG = "RecorderEngine"
        private const val MIME_VIDEO = "video/avc"
        private const val MIME_AUDIO = "audio/mp4a-latm"
        private const val TIMEOUT_USEC = 10_000L
        private const val MAX_ENCODER_RETRY = 2
        private const val MAX_CAP_PRUNE_ATTEMPTS = 3
    }

    // ----- Internal State -----
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioCapture: AudioCaptureManager? = null
    private var muxer: MediaMuxer? = null

    @Volatile private var videoTrackIndex = -1
    @Volatile private var audioTrackIndex = -1
    @Volatile private var tracksAdded = 0
    @Volatile private var muxerStarted = false
    @Volatile private var stopping = false
    @Volatile private var paused = false

    private var actualVideoWidth = 0
    private var actualVideoHeight = 0
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var forceStop: AtomicBoolean = AtomicBoolean(false)

    private var outputFilePath: String = ""
    private var callback: Callback? = null
    private var qualityParams: QualityParams? = null
    private var audioMode: AudioMode = AudioMode.AUTO
    private var startTimeMs: Long = 0

    interface Callback {
        fun onStarted(codecName: String) {}
        fun onStopped(durationMs: Long, fileSize: Long, outputPath: String) {}
        fun onError(message: String, throwable: Throwable? = null) {}
        fun onEncoderChanged(newCodec: String) {}
    }

    fun setCallback(cb: Callback?) { callback = cb }

    data class StartConfig(
        val projection: MediaProjection,
        val resultCode: Int,
        val resultData: Intent,
        val outputPath: String,
        val quality: QualityParams,
        val audioMode: AudioMode
    )

    fun start(config: StartConfig) {
        audioMode = config.audioMode
        qualityParams = config.quality
        outputFilePath = config.outputPath
        mediaProjection = config.projection

        try {
            // 1. Force-stop any leftover encoder instances
            forceStop.set(false)
            stopping = false
            tracksAdded = 0
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false

            // 2. Create Muxer
            muxer = safelyCreateMuxer(config.outputPath)

            // 3. Detect & clamp encoder capabilities
            val clamped = clampConfigToEncoderLimits(config.quality)
            actualVideoWidth = clamped.width
            actualVideoHeight = clamped.height

            Log.i(logTag, "质量参数(裁剪后): ${clamped.width}x${clamped.height} @${clamped.frameRate}fps ${clamped.videoBitRate / 1_000_000}Mbps audio=${clamped.audioBitRate / 1000}kbps dpi=${clamped.dpi}")

            // 4. Setup video encoder
            videoEncoder = createVideoEncoder(clamped)

            // 5. Setup audio if needed
            if (config.audioMode != AudioMode.MUTE) {
                audioEncoder = createAudioEncoder(clamped.audioBitRate)
                audioCapture = AudioCaptureManager(context, config.audioMode)
                audioCapture?.prepare()
                audioCapture?.start()
            }

            // 6. Start video encoder
            videoEncoder?.start()
            audioEncoder?.start()

            // 7. Create VirtualDisplay
            val dpi = if (clamped.dpi > 0) clamped.dpi else context.resources.displayMetrics.densityDpi
            val displayFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    if (Build.VERSION.SDK_INT >= 27) 1 shl 3 else 0  // AUTO_MIRROR = 1<<3
            } else {
                0
            }

            inputSurface = videoEncoder?.createInputSurface()
            if (inputSurface == null) throw IllegalStateException("编码器 InputSurface 为空")

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder-$logTag",
                actualVideoWidth, actualVideoHeight, dpi, displayFlags,
                inputSurface, null, null
            )

            // 8. Start encode thread
            encodeThread = HandlerThread("EncodeThread-$logTag", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            encodeThread?.start()
            encodeHandler = Handler(encodeThread!!.looper)
            startTimeMs = System.currentTimeMillis()
            encodeHandler?.post { encodeLoop() }

            callback?.onStarted(videoEncoder?.name ?: "c2.android.avc.encoder")

        } catch (e: Exception) {
            Log.e(logTag, "启动录制引擎失败", e)
            forceStopAll()
            callback?.onError("启动录制引擎失败: ${e.message}", e)
        }
    }

    fun stop() {
        stopping = true
        forceStop.set(true)

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            audioCapture?.stop()
            audioCapture = null

            drainRemaining()
            muxer?.let {
                if (muxerStarted) {
                    try { it.stop() } catch (_: Exception) {}
                }
                try { it.release() } catch (_: Exception) {}
            }
            muxer = null

            videoEncoder?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            videoEncoder = null

            audioEncoder?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            audioEncoder = null

            encodeHandler?.removeCallbacksAndMessages(null)
            encodeThread?.quitSafely()
            encodeThread = null
            encodeHandler = null

            val durationMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
            val fileSize = File(outputFilePath).length()
            callback?.onStopped(durationMs, fileSize, outputFilePath)

        } catch (e: Exception) {
            Log.e(logTag, "停止异常", e)
            callback?.onError("停止录制异常: ${e.message}", e)
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    // ----- Encoder Capability Clamping -----
    private fun clampConfigToEncoderLimits(qp: QualityParams): QualityParams {
        try {
            val encoder = createTempEncoderForCapCheck()
            val caps = encoder.codecInfo.getCapabilitiesForType(MIME_VIDEO)?.videoCapabilities
            encoder.release()

            if (caps == null) {
                Log.w(logTag, "无法获取编码器能力，使用原始参数")
                return qp
            }

            var w = qp.width.coerceIn(320, caps.supportedWidths.upper.toInt())
            var h = qp.height.coerceIn(240, caps.supportedHeights.upper.toInt())

            // Align to 16
            w = (w + 15) / 16 * 16
            h = (h + 15) / 16 * 16

            // Ensure w/h ratio
            val capW = caps.supportedWidths
            val capH = caps.supportedHeights
            w = w.coerceIn(capW.lower.toInt(), capW.upper.toInt())
            h = h.coerceIn(capH.lower.toInt(), capH.upper.toInt())

            // Bitrate
            val maxBitrate = caps.bitrateRange.upper.toInt()
            val minBitrate = caps.bitrateRange.lower.toInt()
            val br = qp.videoBitRate.coerceIn(minBitrate, maxBitrate)

            // Frame rate
            val capFps = caps.supportedFrameRates
            val fps = if (capFps != null) {
                var best = qp.frameRate
                for (r in capFps) {
                    if (best in r.lower.toInt()..r.upper.toInt()) {
                        best = qp.frameRate.coerceIn(r.lower.toInt(), r.upper.toInt())
                        break
                    }
                }
                // Fallback: use highest supported
                qp.frameRate.coerceIn(capFps.first().lower.toInt(), capFps.first().upper.toInt())
            } else {
                qp.frameRate.coerceIn(1, 120)
            }

            val clamped = QualityParams(w, h, fps, br, qp.audioBitRate, qp.dpi)
            Log.i(logTag, "编码器能力裁剪: ${qp.width}x${qp.height}→${w}x${h}, fps=${qp.frameRate}→${fps}, br=${qp.videoBitRate}→${br}")
            return clamped
        } catch (e: Exception) {
            Log.w(logTag, "编码器能力查询失败，使用原始参数", e)
            return qp
        }
    }

    private fun createTempEncoderForCapCheck(): MediaCodec {
        val codecName = selectBestVideoEncoder()
        return MediaCodec.createByCodecName(codecName)
    }

    // ----- Encoder Creation -----
    private fun createVideoEncoder(qp: QualityParams): MediaCodec {
        val codecName = selectBestVideoEncoder()
        val format = MediaFormat.createVideoFormat(MIME_VIDEO, qp.width, qp.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, qp.videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, qp.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }
        return createEncoderWithRetry(codecName, format)
    }

    private fun createAudioEncoder(bitRate: Int): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MIME_AUDIO)
        val format = MediaFormat.createAudioFormat(MIME_AUDIO, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun createEncoderWithRetry(codecName: String, format: MediaFormat): MediaCodec {
        var codec: MediaCodec? = null
        var lastError: Exception? = null

        for (attempt in 0..MAX_ENCODER_RETRY) {
            try {
                codec = MediaCodec.createByCodecName(codecName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(logTag, "编码器创建成功: $codecName (尝试${attempt + 1})")
                return codec
            } catch (e: Exception) {
                lastError = e
                Log.w(logTag, "编码器 $codecName 创建失败(尝试${attempt + 1}): ${e.message}")
                codec?.release()
                codec = null

                // Try fallback: CBR mode
                if (attempt == 0 && e.message?.contains("bitrate") == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    continue
                }
                break
            }
        }
        throw RuntimeException("编码器初始化失败: ${lastError?.message}", lastError)
    }

    private fun selectBestVideoEncoder(): String {
        val encoders = listAllVideoEncoders()
        Log.i(logTag, "可用编码器: $encoders")

        // Priority: c2.android.avc.encoder → Google SW → OEM SW → any SW → any
        val priorityPatterns = listOf(
            "c2.android.avc.encoder",
            "OMX.google.h264.encoder",
            "OMX.hisi.video.encoder.avc",
            "OMX.Exynos.avc.encoder",
            "OMX.MTK.VIDEO.ENCODER.AVC",
            "OMX.sprd.h264.encoder",
            "c2.android.avc.encoder.secure"
        )

        for (pattern in priorityPatterns) {
            val match = encoders.find { it.equals(pattern, ignoreCase = true) || it.contains(pattern, ignoreCase = true) }
            if (match != null) return match
        }

        // Fallback: any SW encoder
        val sw = encoders.find { "sw" in it.lowercase() || "software" in it.lowercase() || "google" in it.lowercase() }
        if (sw != null) return sw

        // Last resort: first encoder (excluding explicitly bad ones)
        val safe = encoders.filterNot { "qti" in it.lowercase() || "secure" in it.lowercase() }
        if (safe.isNotEmpty()) return safe.first()

        if (encoders.isEmpty()) throw RuntimeException("未找到可用的视频编码器")
        return encoders.first()
    }

    private fun listAllVideoEncoders(): List<String> {
        val names = mutableListOf<String>()
        val codecList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
        } else {
            MediaCodecList(MediaCodecList.ALL_CODECS)
        }

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.contains(MIME_VIDEO)) continue
            if (info.name.lowercase().contains("vp")) continue
            names.add(info.name)
        }
        return names
    }

    // ----- Encode Loop -----
    private fun encodeLoop() {
        val videoBufferInfo = MediaCodec.BufferInfo()
        while (!forceStop.get()) {
            try {
                val vc = videoEncoder ?: break
                val vOutIdx = vc.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_USEC)

                when {
                    vOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = vc.outputFormat
                        if (actualVideoWidth == 0) {
                            newFormat.getInteger(MediaFormat.KEY_WIDTH)?.let { actualVideoWidth = it }
                            newFormat.getInteger(MediaFormat.KEY_HEIGHT)?.let { actualVideoHeight = it }
                        }
                        videoTrackIndex = muxer?.addTrack(newFormat) ?: -1
                        tracksAdded++
                        Log.i(logTag, "视频轨添加: idx=$videoTrackIndex, fmt=${newFormat}")
                        tryStartMuxer()
                    }
                    vOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available, brief sleep
                    }
                    vOutIdx >= 0 -> {
                        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buf = vc.getOutputBuffer(vOutIdx)
                            if (buf != null && muxerStarted && videoTrackIndex >= 0) {
                                buf.position(videoBufferInfo.offset)
                                buf.limit(videoBufferInfo.offset + videoBufferInfo.size)
                                videoBufferInfo.presentationTimeUs = correctPts(videoBufferInfo.presentationTimeUs)
                                try { muxer?.writeSampleData(videoTrackIndex, buf, videoBufferInfo) }
                                catch (e: Exception) { Log.w(logTag, "写视频帧失败: ${e.message}") }
                            }
                        }
                        vc.releaseOutputBuffer(vOutIdx, false)
                    }
                }

                // Audio encode
                encodeAudioFrame()

            } catch (e: IllegalStateException) {
                Log.w(logTag, "编码循环 IllegalStateException, 退出", e)
                forceStop.set(true)
            } catch (e: Exception) {
                Log.w(logTag, "编码循环异常", e)
                forceStop.set(true)
            }
        }
    }

    private fun encodeAudioFrame() {
        val ac = audioCapture ?: return
        val ae = audioEncoder ?: return
        if (audioTrackIndex >= 0) return // already added and muxer started with audio track

        val audioData = ac.readFrame()
        if (audioData == null || audioData.isEmpty()) return

        val aInIdx = ae.dequeueInputBuffer(TIMEOUT_USEC)
        if (aInIdx < 0) return

        val abuf = ae.getInputBuffer(aInIdx) ?: return
        abuf.clear()
        abuf.put(audioData)
        ae.queueInputBuffer(aInIdx, 0, audioData.size, System.nanoTime() / 1000, 0)

        val audioBInfo = MediaCodec.BufferInfo()
        val aOutIdx = ae.dequeueOutputBuffer(audioBInfo, TIMEOUT_USEC)
        when {
            aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                audioTrackIndex = muxer?.addTrack(ae.outputFormat) ?: -1
                tracksAdded++
                Log.i(logTag, "音频轨添加: idx=$audioTrackIndex")
                tryStartMuxer()
            }
            aOutIdx >= 0 -> {
                if (muxerStarted && audioTrackIndex >= 0) {
                    val buf = ae.getOutputBuffer(aOutIdx)
                    if (buf != null) {
                        buf.position(audioBInfo.offset)
                        buf.limit(audioBInfo.offset + audioBInfo.size)
                        try { muxer?.writeSampleData(audioTrackIndex, buf, audioBInfo) }
                        catch (e: Exception) { Log.w(logTag, "写音频帧失败: ${e.message}") }
                    }
                }
                ae.releaseOutputBuffer(aOutIdx, false)
            }
        }
    }

    private fun tryStartMuxer() {
        val needed = if (audioMode != AudioMode.MUTE) 2 else 1
        if (tracksAdded >= needed && !muxerStarted) {
            muxer?.start()
            muxerStarted = true
            Log.i(logTag, "Muxer 已启动 (tracks=$tracksAdded)")
        }
    }

    private fun drainRemaining() {
        val videoBufInfo = MediaCodec.BufferInfo()
        val vc = videoEncoder
        if (vc != null && muxerStarted) {
            try {
                vc.signalEndOfInputStream()
            } catch (_: Exception) {}

            repeat(100) {
                val idx = vc.dequeueOutputBuffer(videoBufInfo, 100_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return@repeat
                    idx < 0 -> return@repeat
                    idx >= 0 -> {
                        val buf = vc.getOutputBuffer(idx)
                        if (buf != null && videoTrackIndex >= 0 && videoBufInfo.size > 0) {
                            buf.position(videoBufInfo.offset)
                            buf.limit(videoBufInfo.offset + videoBufInfo.size)
                            try { muxer?.writeSampleData(videoTrackIndex, buf, videoBufInfo) }
                            catch (_: Exception) {}
                        }
                        vc.releaseOutputBuffer(idx, false)
                    }
                }
            }
        }

        // Drain audio remaining frames
        val ae = audioEncoder
        if (ae != null && muxerStarted) {
            val audioBInfo = MediaCodec.BufferInfo()
            try { ae.signalEndOfInputStream() } catch (_: Exception) {}
            repeat(50) {
                val idx = ae.dequeueOutputBuffer(audioBInfo, 50_000)
                if (idx >= 0 && audioTrackIndex >= 0) {
                    val buf = ae.getOutputBuffer(idx)
                    if (buf != null && audioBInfo.size > 0) {
                        buf.position(audioBInfo.offset)
                        buf.limit(audioBInfo.offset + audioBInfo.size)
                        try { muxer?.writeSampleData(audioTrackIndex, buf, audioBInfo) }
                        catch (_: Exception) {}
                    }
                    ae.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    private fun forceStopAll() {
        runCatching { videoEncoder?.stop(); videoEncoder?.release() }
        runCatching { audioEncoder?.stop(); audioEncoder?.release() }
        runCatching { muxer?.stop(); muxer?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { audioCapture?.stop() }
        encodeHandler?.removeCallbacksAndMessages(null)
        encodeThread?.quitSafely()
    }

    private fun safelyCreateMuxer(path: String): MediaMuxer {
        val file = File(path)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        return try {
            MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            val dir = File(path).parentFile?.absolutePath ?: "/sdcard"
            val fallback = File(dir, "ScreenRecord_fallback_${System.currentTimeMillis()}.mp4")
            Log.w(logTag, "原始路径不可用，回退: $fallback", e)
            outputFilePath = fallback.absolutePath
            MediaMuxer(fallback.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun correctPts(ptsUs: Long): Long = if (ptsUs < 0) System.nanoTime() / 1000 else ptsUs
}
