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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
        private const val FRAME_LOG_INTERVAL = 150
        private val BLACKLIST_PATTERNS = listOf(
            "qti", "qcom", "qct", "qc_"
        )
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
    private var actualFrameRate = 0
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var forceStop: AtomicBoolean = AtomicBoolean(false)

    private var outputFilePath: String = ""
    private var callback: Callback? = null
    private var qualityParams: QualityParams? = null
    private var audioMode: AudioMode = AudioMode.AUTO
    private var startTimeMs: Long = 0

    // Frame statistics
    private val totalFrames = AtomicLong(0)
    private val emptyPackets = AtomicLong(0)
    private var firstFrameTimeMs: Long = 0
    private var lastFrameTimeMs: Long = 0

    // Selected codec name for history
    private var selectedCodecName: String = ""

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

    // ===== PUBLIC API =====

    fun start(config: StartConfig) {
        audioMode = config.audioMode
        qualityParams = config.quality
        outputFilePath = config.outputPath
        mediaProjection = config.projection

        try {
            logSection("START", "开始录制 | SDK=${Build.VERSION.SDK_INT} | Android=${Build.VERSION.RELEASE} | 硬件=${Build.HARDWARE} | 厂商=${Build.MANUFACTURER} | 型号=${Build.MODEL}")
            logDetail("输入参数", "output=${config.outputPath} qp=${config.quality.width}x${config.quality.height}@${config.quality.frameRate}fps br=${config.quality.videoBitRate/1000}kbps audio=${config.audioMode} dpi=${config.quality.dpi}")

            // 1. Force-stop any leftover encoder instances
            forceStop.set(false)
            stopping = false
            tracksAdded = 0
            videoTrackIndex = -1
            audioTrackIndex = -1
            muxerStarted = false
            totalFrames.set(0)
            emptyPackets.set(0)
            firstFrameTimeMs = 0
            lastFrameTimeMs = 0

            // 2. State machine: IDLE → PREPARING
            logState("PREPARING")

            // 3. Create Muxer
            muxer = safelyCreateMuxer(config.outputPath)
            logDetail("Muxer", "创建完成 path=${config.outputPath} format=MUXER_OUTPUT_MPEG_4")

            // 4. Detect & clamp encoder capabilities
            val clamped = clampConfigToEncoderLimits(config.quality)
            actualVideoWidth = clamped.width
            actualVideoHeight = clamped.height
            actualFrameRate = clamped.frameRate

            logDetail("质量裁剪", "${config.quality.width}x${config.quality.height}→${clamped.width}x${clamped.height} fps=${config.quality.frameRate}→${clamped.frameRate} br=${config.quality.videoBitRate/1000}→${clamped.videoBitRate/1000}kbps")

            // 5. Setup video encoder with detailed logging
            val videoFormat = MediaFormat.createVideoFormat(MIME_VIDEO, clamped.width, clamped.height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, clamped.videoBitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, clamped.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }
            videoEncoder = createVideoEncoderWithLog(clamped, videoFormat)
            selectedCodecName = videoEncoder?.name ?: "unknown"

            // 6. Setup audio if needed
            if (config.audioMode != AudioMode.MUTE) {
                audioEncoder = createAudioEncoderLogging(clamped.audioBitRate)
                audioCapture = AudioCaptureManager(context, config.audioMode)
                audioCapture?.prepare()
                audioCapture?.start()
                logDetail("音频编码器", "创建完成 codec=${audioEncoder?.name} br=${clamped.audioBitRate/1000}kbps mode=${config.audioMode}")
            } else {
                logDetail("音频", "静音模式，跳过音频编码器")
            }

            // 7. Start encoders
            videoEncoder?.start()
            audioEncoder?.start()
            logDetail("编码器启动", "视频+音频编码器已start")

            // 8. Create InputSurface
            inputSurface = videoEncoder?.createInputSurface()
            if (inputSurface == null) throw IllegalStateException("编码器 InputSurface 为空")
            logDetail("InputSurface", "创建完成 ${inputSurface}")

            // 9. Create VirtualDisplay
            val dpi = if (clamped.dpi > 0) clamped.dpi else context.resources.displayMetrics.densityDpi
            val apiPath = when {
                Build.VERSION.SDK_INT >= 29 -> "API29+"
                Build.VERSION.SDK_INT >= 27 -> "API27-28 (O_MR1)"
                Build.VERSION.SDK_INT >= 26 -> "API26 (O)"
                Build.VERSION.SDK_INT >= 24 -> "API24-25 (N)"
                else -> "API<24"
            }

            val displayFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                val mirror = if (Build.VERSION.SDK_INT >= 27) 1 shl 3 else 0 // AUTO_MIRROR = 1<<3
                base or mirror
            } else {
                0
            }

            logDetail("VirtualDisplay", "尺寸=${actualVideoWidth}x${actualVideoHeight} dpi=$dpi flags=0x${displayFlags.toString(16)} API路径=$apiPath")

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder-$logTag",
                actualVideoWidth, actualVideoHeight, dpi, displayFlags,
                inputSurface, null, null
            )
            logDetail("VirtualDisplay", "创建完成")

            // 10. Start encode thread
            encodeThread = HandlerThread("EncodeThread-$logTag", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            encodeThread?.start()
            encodeHandler = Handler(encodeThread!!.looper)
            startTimeMs = System.currentTimeMillis()
            encodeHandler?.post { encodeLoop() }

            logState("RECORDING")
            logSection("READY", "录制已启动 | 编码器=$selectedCodecName | ${actualVideoWidth}x${actualVideoHeight}@${actualFrameRate}fps")
            callback?.onStarted(selectedCodecName)

        } catch (e: Exception) {
            Log.e(logTag, "启动录制引擎失败", e)
            logDetail("ERROR", "启动失败: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(6).forEach { logDetail("STACK", it.toString()) }
            forceStopAll()
            callback?.onError("启动录制引擎失败: ${e.message}", e)
        }
    }

    fun stop() {
        logSection("STOP", "停止录制")
        stopping = true
        forceStop.set(true)

        try {
            logDetail("VD释放", "释放VirtualDisplay")
            virtualDisplay?.release()
            virtualDisplay = null

            logDetail("音频停止", "停止音频采集")
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
            logDetail("Muxer", "已停止并释放")

            videoEncoder?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            videoEncoder = null
            logDetail("视频编码器", "已停止并释放")

            audioEncoder?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }
            audioEncoder = null
            logDetail("音频编码器", "已停止并释放")

            encodeHandler?.removeCallbacksAndMessages(null)
            encodeThread?.quitSafely()
            encodeThread = null
            encodeHandler = null

            val durationMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
            val fileSize = File(outputFilePath).length()
            val avgFps = if (durationMs > 0) totalFrames.get() * 1000.0 / durationMs else 0.0

            logSection("SUMMARY",
                "录制完成 | 时长=${durationMs}ms | 文件=${formatFileSize(fileSize)} | 总帧=$totalFrames | 空包=$emptyPackets | " +
                "平均FPS=${"%.1f".format(avgFps)} | 首帧耗时=${firstFrameTimeMs}ms | 编码器=$selectedCodecName | " +
                "输出=$outputFilePath")

            callback?.onStopped(durationMs, fileSize, outputFilePath)

        } catch (e: Exception) {
            Log.e(logTag, "停止异常", e)
            logDetail("ERROR", "停止异常: ${e.javaClass.simpleName}: ${e.message}")
            callback?.onError("停止录制异常: ${e.message}", e)
        }
    }

    fun pause() {
        paused = true
        logDetail("状态", "暂停录制")
    }

    fun resume() {
        paused = false
        logDetail("状态", "恢复录制")
    }

    // ===== ENCODER CAPABILITY CLAMPING =====

    private fun clampConfigToEncoderLimits(qp: QualityParams): QualityParams {
        try {
            logSection("CAP_CHECK", "开始编码器能力检测")
            val encoder = createTempEncoderForCapCheck()
            val caps = encoder.codecInfo.getCapabilitiesForType(MIME_VIDEO)?.videoCapabilities
            encoder.release()

            if (caps == null) {
                logDetail("CAP_CHECK", "WARN 无法获取编码器能力，使用原始参数")
                return qp
            }

            val casted: Array<android.util.Range<Int>>? = caps.supportedFrameRates as Array<android.util.Range<Int>>?
        logDetail("CAP_CHECK",
                "宽度范围=${caps.supportedWidths} 高度范围=${caps.supportedHeights} " +
                "码率范围=${caps.bitrateRange} 帧率范围=${formatFpsRanges(casted)}")

            var w = qp.width.coerceIn(320, caps.supportedWidths.upper.toInt())
            var h = qp.height.coerceIn(240, caps.supportedHeights.upper.toInt())

            w = (w + 15) / 16 * 16
            h = (h + 15) / 16 * 16

            val capW = caps.supportedWidths
            val capH = caps.supportedHeights
            w = w.coerceIn(capW.lower.toInt(), capW.upper.toInt())
            h = h.coerceIn(capH.lower.toInt(), capH.upper.toInt())

            val maxBitrate = caps.bitrateRange.upper.toInt()
            val minBitrate = caps.bitrateRange.lower.toInt()
            val br = qp.videoBitRate.coerceIn(minBitrate, maxBitrate)

            val fps = safelyPickFps(qp.frameRate, casted)

            val clamped = QualityParams(w, h, fps, br, qp.audioBitRate, qp.dpi)
            logDetail("CAP_CHECK",
                "裁剪结果: ${qp.width}x${qp.height}@${qp.frameRate}fps→${w}x${h}@${fps}fps " +
                "br=${qp.videoBitRate/1000}→${br/1000}kbps")
            return clamped
        } catch (e: Exception) {
            logDetail("CAP_CHECK", "WARN 编码器能力查询失败，使用原始参数: ${e.message}")
            return qp
        }
    }

    private fun createTempEncoderForCapCheck(): MediaCodec {
        val codecName = selectBestVideoEncoder()
        return MediaCodec.createByCodecName(codecName)
    }

    // ===== ENCODER CREATION =====

    private fun createVideoEncoderWithLog(qp: QualityParams, format: MediaFormat): MediaCodec {
        logSection("ENCODER_SELECT", "视频编码器选择流程")

        // Step 1: List all encoders
        val allEncoders = listAllVideoEncoders()
        logDetail("1.原始列表", allEncoders.joinToString())

        // Step 2: Blacklist filter
        val filtered = allEncoders.filter { name ->
            val lower = name.lowercase()
            val blocked = BLACKLIST_PATTERNS.any { it in lower }
            if (blocked) logDetail("2.黑名单排除", "排除 $name (命中qti/qcom/qct)")
            !blocked
        }
        logDetail("2.过滤后", filtered.joinToString { it })

        if (filtered.isEmpty()) {
            logDetail("2.过滤后", "WARN 全部被过滤！回退使用完整列表")
        }

        // Step 3: Priority selection
        val codecName = selectBestVideoEncoder()
        logDetail("3.选中编码器", codecName)

        // Step 4: Dump MediaFormat
        logDetail("4.编码器参数",
            "codec=$codecName | mime=$MIME_VIDEO | ${qp.width}x${qp.height}@${qp.frameRate}fps | " +
            "bitrate=${qp.videoBitRate/1000}kbps | i-interval=1 | color=Surface | " +
            "priority=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "REALTIME(0)" else "未设置"}")

        return createEncoderWithRetry(codecName, format)
    }

    private fun createAudioEncoderLogging(bitRate: Int): MediaCodec {
        logSection("AUDIO_ENCODER", "音频编码器创建")
        val codec = MediaCodec.createEncoderByType(MIME_AUDIO)
        val format = MediaFormat.createAudioFormat(MIME_AUDIO, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        logDetail("音频参数", "codec=${codec.name} mime=$MIME_AUDIO sr=44100 channels=2 br=${bitRate/1000}kbps")
        return codec
    }

    private fun createEncoderWithRetry(codecName: String, format: MediaFormat): MediaCodec {
        var codec: MediaCodec? = null
        var lastError: Exception? = null

        for (attempt in 0..MAX_ENCODER_RETRY) {
            try {
                codec = MediaCodec.createByCodecName(codecName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                logDetail("编码器创建", "成功 codec=$codecName 尝试=${attempt + 1}")
                return codec
            } catch (e: Exception) {
                lastError = e
                logDetail("编码器创建", "WARN $codecName 尝试${attempt + 1}失败: ${e.message}")
                codec?.release()
                codec = null

                if (attempt == 0 && e.message?.contains("bitrate") == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    logDetail("编码器创建", "回退为CBR模式重试")
                    continue
                }
                break
            }
        }
        throw RuntimeException("编码器初始化失败: ${lastError?.message}", lastError)
    }

    private fun selectBestVideoEncoder(): String {
        val encoders = listAllVideoEncoders()
        val filtered = encoders.filter { name ->
            val lower = name.lowercase()
            BLACKLIST_PATTERNS.none { it in lower }
        }

        val workingList = if (filtered.isNotEmpty()) filtered else encoders
        logDetail("编码器池", "原始${encoders.size}个, 过滤后${workingList.size}个")

        // Priority: c2.android.avc.encoder → Google SW → OEM SW → any safe encoder
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
            val match = workingList.find { it.equals(pattern, ignoreCase = true) || it.contains(pattern, ignoreCase = true) }
            if (match != null) {
                logDetail("优先级匹配", "命中 $pattern → $match")
                return match
            }
        }

        // Fallback: any SW encoder
        val sw = workingList.find { "sw" in it.lowercase() || "software" in it.lowercase() || "google" in it.lowercase() }
        if (sw != null) {
            logDetail("软件编码器", "回退: $sw")
            return sw
        }

        // First safe encoder
        val safe = workingList.filterNot { "secure" in it.lowercase() || "vp" in it.lowercase() || "hevc" in it.lowercase() }
        if (safe.isNotEmpty()) {
            logDetail("安全编码器", "回退首个安全编码器: ${safe.first()}")
            return safe.first()
        }

        if (workingList.isEmpty()) throw RuntimeException("未找到可用的视频编码器")
        logDetail("兜底", "使用首个编码器: ${workingList.first()}")
        return workingList.first()
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
            // Also exclude VP and HEVC encoders
            val lower = info.name.lowercase()
            if (lower.contains("vp8") || lower.contains("vp9") || lower.contains("hevc")) continue
            names.add(info.name)
        }
        return names
    }

    // ===== ENCODE LOOP =====

    private fun encodeLoop() {
        val videoBufferInfo = MediaCodec.BufferInfo()
        var lastFrameLog = 0L

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
                        logDetail("编码器回调", "OUTPUT_FORMAT_CHANGED | videoTrackIdx=$videoTrackIndex tracks=$tracksAdded")
                        dumpMediaFormat("视频输出格式", newFormat)
                        tryStartMuxer()
                    }
                    vOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available
                    }
                    vOutIdx >= 0 -> {
                        val now = System.currentTimeMillis()
                        if (firstFrameTimeMs == 0L && startTimeMs > 0) {
                            firstFrameTimeMs = now - startTimeMs
                            logDetail("首帧到达", "耗时=${firstFrameTimeMs}ms | pts=${videoBufferInfo.presentationTimeUs/1000}ms")
                        }
                        lastFrameTimeMs = now

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

                        val total = totalFrames.incrementAndGet()
                        if (total - lastFrameLog >= FRAME_LOG_INTERVAL) {
                            lastFrameLog = total
                            val elapsed = if (startTimeMs > 0) now - startTimeMs else 1
                            val avgFps = total * 1000.0 / elapsed
                            logDetail("帧采样", "#$total | 平均FPS=${"%.1f".format(avgFps)} | 耗时=${elapsed}ms | 空包=$emptyPackets | pts=${videoBufferInfo.presentationTimeUs/1000}ms")
                        }
                    }
                }

                // Audio encode
                encodeAudioFrame()

            } catch (e: IllegalStateException) {
                logDetail("编码循环", "ERROR IllegalStateException, 退出: ${e.message}")
                forceStop.set(true)
            } catch (e: Exception) {
                logDetail("编码循环", "ERROR 异常退出: ${e.javaClass.simpleName}: ${e.message}")
                forceStop.set(true)
            }
        }
        logDetail("编码循环", "已退出 | 总帧=$totalFrames | 空包=$emptyPackets")
    }

    private fun encodeAudioFrame() {
        val ac = audioCapture ?: return
        val ae = audioEncoder ?: return
        if (audioTrackIndex >= 0) return

        val audioData = ac.readFrame()
        if (audioData == null || audioData.isEmpty()) {
            emptyPackets.incrementAndGet()
            return
        }

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
                logDetail("编码器回调", "音频 OUTPUT_FORMAT_CHANGED | audioTrackIdx=$audioTrackIndex tracks=$tracksAdded")
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
            logDetail("Muxer", "启动 (tracks=$tracksAdded needed=$needed)")
        }
    }

    private fun drainRemaining() {
        logDetail("尾部排空", "开始排空剩余编码帧")
        val videoBufInfo = MediaCodec.BufferInfo()
        val vc = videoEncoder
        var drainedVideoFrames = 0
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
                        drainedVideoFrames++
                    }
                }
            }
        }

        val ae = audioEncoder
        var drainedAudioFrames = 0
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
                    drainedAudioFrames++
                }
            }
        }
        logDetail("尾部排空", "完成 | 视频帧=$drainedVideoFrames | 音频帧=$drainedAudioFrames")
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

    // ===== UTILITY =====

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

    private fun dumpMediaFormat(label: String, format: MediaFormat) {
        val sb = StringBuilder()
        try { sb.append("mime=${format.getString(MediaFormat.KEY_MIME)} ") } catch (_: Exception) {}
        try { sb.append("w=${format.getInteger(MediaFormat.KEY_WIDTH)} ") } catch (_: Exception) {}
        try { sb.append("h=${format.getInteger(MediaFormat.KEY_HEIGHT)} ") } catch (_: Exception) {}
        try { sb.append("br=${format.getInteger(MediaFormat.KEY_BIT_RATE)} ") } catch (_: Exception) {}
        try { sb.append("fps=${format.getInteger(MediaFormat.KEY_FRAME_RATE)} ") } catch (_: Exception) {}
        try { sb.append("csd=${format.getByteBuffer("csd-0")}") } catch (_: Exception) {}
        logDetail(label, sb.toString().trim())
    }

@Suppress("UNCHECKED_CAST")
    private fun formatFpsRanges(ranges: Array<android.util.Range<Int>>?): String {
        if (ranges == null || ranges.isEmpty()) return "无"
        val sb = StringBuilder()
        for (i in ranges.indices) {
            val r = ranges[i]
            sb.append(r.lower).append("-").append(r.upper)
            if (i < ranges.size - 1) sb.append(", ")
        }
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun safelyPickFps(desired: Int, ranges: Array<android.util.Range<Int>>?): Int {
        if (ranges == null || ranges.isEmpty()) return desired.coerceIn(1, 120)
        val firstLower = ranges[0].lower
        val firstUpper = ranges[0].upper
        var best = desired
        for (i in 0 until ranges.size) {
            val r = ranges[i]
            val rLower = r.lower
            val rUpper = r.upper
            if (best in rLower..rUpper) {
                best = desired.coerceIn(rLower, rUpper)
                return best
            }
        }
        return desired.coerceIn(firstLower, firstUpper)
    }

    // ===== LOGGING HELPERS =====
    private fun logSection(section: String, msg: String) {
        val line = "===== [$section] $msg ====="
        Log.i(logTag, line)
    }

    private fun logDetail(tag: String, msg: String) {
        val line = "  [$tag] $msg"
        Log.d(logTag, line)
    }

    private fun logState(state: String) {
        val line = "  [状态] → $state"
        Log.i(logTag, line)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> "%.2fGiB".format(bytes / 1073741824.0)
            bytes >= 1048576 -> "%.2fMiB".format(bytes / 1048576.0)
            bytes >= 1024 -> "%.2fKiB".format(bytes / 1024.0)
            else -> "${bytes}B"
        }
    }
}
