package com.opencode.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class NodeService : Service() {

    interface NodeListener {
        fun onReady(port: Int)
        fun onError(message: String)
        fun onOutput(line: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    private val binder = LocalBinder()
    private var listener: NodeListener? = null
    private var process: Process? = null
    private var outputThread: Thread? = null

    fun setListener(listener: NodeListener) {
        this.listener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(OpenCodeApplication.NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    fun start() {
        if (process != null) return

        val workDir = File(filesDir, "opencode")
        workDir.mkdirs()

        val opencodeBin = File(workDir, "opencode")
        val glibcDir = File(workDir, "glibc")

        if (!opencodeBin.exists()) {
            listener?.onOutput("[setup] Extracting opencode...")
            if (!extractAssets(workDir)) {
                listener?.onError("Failed to extract opencode")
                return
            }
        }

        Log.i("OpenCode", "Starting opencode from ${opencodeBin.absolutePath}")
        listener?.onOutput("[setup] Starting OpenCode AI...")

        try {
            val pb = ProcessBuilder(
                opencodeBin.absolutePath,
                "web",
                "--port", "0",
                "--hostname", "127.0.0.1"
            )
            pb.directory(workDir)
            pb.environment()["LD_LIBRARY_PATH"] = glibcDir.absolutePath
            pb.environment()["OPENCODE_HOME"] = File(workDir, ".opencode").absolutePath
            pb.environment()["HOME"] = workDir.absolutePath

            process = pb.start()

            outputThread = Thread {
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line!!
                    Log.i("OpenCode", text)
                    listener?.onOutput(text)

                    val portMatch = Regex("""https?://127\.0\.0\.1[:/](\d+)""").find(text)
                    if (portMatch != null) {
                        val port = portMatch.groupValues[1].toInt()
                        listener?.onReady(port)
                    }
                }
            }
            outputThread?.start()

            val errorThread = Thread {
                val reader = BufferedReader(InputStreamReader(process!!.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.w("OpenCode", line!!)
                    listener?.onOutput("[stderr] ${line}")
                }
            }
            errorThread.start()

        } catch (e: Exception) {
            Log.e("OpenCode", "Start error", e)
            listener?.onError(e.message ?: "Unknown error")
            process = null
        }
    }

    fun stop() {
        process?.let {
            it.destroyForcibly()
            process = null
        }
        outputThread?.interrupt()
        outputThread = null
    }

    private fun extractAssets(workDir: File): Boolean {
        val binaryPath = "opencode/opencode"

        return try {
            val opencodeBin = File(workDir, "opencode")
            assets.open(binaryPath).use { input ->
                opencodeBin.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            opencodeBin.setExecutable(true)

            val glibcAssets = assets.list("opencode/glibc")
            if (glibcAssets != null) {
                val glibcDir = File(workDir, "glibc")
                glibcDir.mkdirs()
                for (asset in glibcAssets) {
                    assets.open("opencode/glibc/$asset").use { input ->
                        File(glibcDir, asset).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            Log.i("OpenCode", "Assets extracted to ${workDir.absolutePath}")
            listener?.onOutput("[setup] Extracted opencode (${opencodeBin.length() / 1024 / 1024} MB)")
            true
        } catch (e: Exception) {
            Log.e("OpenCode", "Extract error", e)
            false
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, OpenCodeApplication.CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("OpenCode")
            .setContentText("AI coding agent is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
