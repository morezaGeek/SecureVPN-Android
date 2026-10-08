package com.vpnapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB
    private var logFile: File? = null
    
    // Background queue for logs to prevent blocking the caller
    private val logQueue = LinkedBlockingQueue<String>()
    private var logThread: Thread? = null

    fun init(context: Context) {
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            logFile = File(logDir, "vpn_crash_log.txt")
            
            startLogThread()
            write("FileLogger initialized at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init FileLogger", e)
        }
    }

    private fun startLogThread() {
        if (logThread?.isAlive == true) return
        
        logThread = thread(name = "FileLoggerThread", isDaemon = true) {
            while (true) {
                try {
                    val message = logQueue.take() // Blocks until a message is available
                    processWrite(message)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in log thread", e)
                }
            }
        }
    }

    fun write(message: String) {
        // Just add to queue. Super fast.
        logQueue.offer(message)
    }

    private fun processWrite(message: String) {
        val file = logFile ?: return
        
        try {
            // Rotation check
            if (file.length() > MAX_FILE_SIZE) {
                val backup = File(file.parent, "vpn_crash_log.bak")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
                logFile = File(file.parent, "vpn_crash_log.txt")
            }

            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(logFile, true).use { writer ->
                writer.append("[$timestamp] $message\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    fun logException(throwable: Throwable) {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        throwable.printStackTrace(pw)
        write("CRASH/EXCEPTION: $sw")
    }
}
