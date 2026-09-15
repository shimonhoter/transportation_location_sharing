package com.shimonhoter.ridelocationshare.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * On-device diagnostic log. `adb`/`logcat` access from the development
 * environment (Termux) has repeatedly been unreliable, so the app keeps its
 * own rolling log file for post-hoc debugging.
 */
object AppLog {
    private const val MAX_FILE_BYTES = 256 * 1024

    private lateinit var logFile: File
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, "app_log.txt")
    }

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        write("E", tag, message + (throwable?.let { ": ${it.message}" } ?: ""))

    private fun write(level: String, tag: String, message: String) {
        Log.println(if (level == "E") Log.ERROR else if (level == "I") Log.INFO else Log.DEBUG, tag, message)
        if (!::logFile.isInitialized) return
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                logFile.delete()
            }
            logFile.appendText("${formatter.format(System.currentTimeMillis())} $level/$tag: $message\n")
        } catch (_: Exception) {
            // Diagnostics must never crash the app they're diagnosing.
        }
    }

    fun readAll(): String = if (::logFile.isInitialized && logFile.exists()) logFile.readText() else ""
}
