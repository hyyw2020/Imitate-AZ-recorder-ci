package com.monkeycode.screenrecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureManager(
    private val context: Context,
    private val mode: AudioMode
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var readBufferSize = 0

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE = 2048
    }

    @SuppressLint("MissingPermission")
    fun prepare() {
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, ENCODING)
        readBufferSize = maxOf(minBuf, FRAME_SIZE * 2)

        val audioSource = when (mode) {
            AudioMode.MIC_ONLY -> MediaRecorder.AudioSource.MIC
            AudioMode.SPEAKER_ONLY -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            AudioMode.MIXED -> MediaRecorder.AudioSource.DEFAULT
            AudioMode.MUTE, AudioMode.AUTO -> MediaRecorder.AudioSource.DEFAULT
        }

        if (mode == AudioMode.AUTO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioRecord = createPlaybackCapture()
        }

        if (audioRecord == null) {
            try {
                audioRecord = AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    channelConfig,
                    ENCODING,
                    readBufferSize
                )
            } catch (e: Exception) {
                // Fallback: try mic
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelConfig,
                    ENCODING,
                    readBufferSize
                )
            }
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createPlaybackCapture(): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(ScreenRecorderApp.pendingMediaProjection ?: return null)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .build())
                .setBufferSizeInBytes(readBufferSize)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    fun start() {
        audioRecord?.let {
            running.set(true)
            it.startRecording()
        }
    }

    fun stop() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun readFrame(): ByteArray? {
        val ar = audioRecord ?: return null
        if (!running.get() || ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null

        val buffer = ByteArray(readBufferSize)
        val bytesRead = ar.read(buffer, 0, readBufferSize)
        if (bytesRead <= 0) return null

        return if (bytesRead == readBufferSize) buffer else buffer.copyOf(bytesRead)
    }
}
