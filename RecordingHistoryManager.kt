package com.monkeycode.screenrecorder

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecordingEntry(
    val fileName: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val codecName: String
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val mins = (totalSeconds % 3600) / 60
            val secs = totalSeconds % 60
            return if (hours > 0) String.format("%d:%02d:%02d", hours, mins, secs)
            else String.format("%02d:%02d", mins, secs)
        }
    val formattedSize: String
        get() = when {
            sizeBytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
            sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024.0 * 1024))
            sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
    val formattedResolution: String
        get() = "${width}x${height}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("fileName", fileName)
        put("path", path)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("width", width)
        put("height", height)
        put("timestamp", timestamp)
        put("codecName", codecName)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordingEntry = RecordingEntry(
            fileName = json.optString("fileName"),
            path = json.optString("path"),
            durationMs = json.optLong("durationMs"),
            sizeBytes = json.optLong("sizeBytes"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            timestamp = json.optLong("timestamp"),
            codecName = json.optString("codecName")
        )
    }
}

class RecordingHistoryManager(context: Context) {
    private val file = File(context.filesDir, "recording_history.json")
    private var entries = mutableListOf<RecordingEntry>()

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            val arr = JSONArray(json)
            entries.clear()
            for (i in 0 until arr.length()) {
                entries.add(RecordingEntry.fromJson(arr.getJSONObject(i)))
            }
            entries.sortByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.w("History", "加载历史失败", e)
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e("History", "保存历史失败", e)
        }
    }

    fun add(entry: RecordingEntry) {
        entries.add(0, entry)
        if (entries.size > 100) {
            entries = entries.take(100).toMutableList()
        }
        save()
    }

    fun getAll(): List<RecordingEntry> = entries.toList()

    fun getRecent(limit: Int = 5): List<RecordingEntry> = entries.take(limit)

    fun delete(predicate: (RecordingEntry) -> Boolean) {
        entries.removeAll(predicate)
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    fun size(): Int = entries.size
}
