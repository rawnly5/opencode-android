package com.opencode.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

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
    private var isRunning = false

    private val portPattern = Pattern.compile("(https?://127\\.0\\.0\\.1|localhost)[:/]?(\\d+)")
    private val portJsonPattern = Pattern.compile("\"port\"\\s*:\\s*(\\d+)")

    fun setListener(listener: NodeListener) {
        this.listener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        extractAssets()
    }

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
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                val nodeDir = File(filesDir, "nodejs-project")
                val mainJs = File(nodeDir, "main.js")

                if (!mainJs.exists()) {
                    listener?.onError("Node.js project not found in app data.")
                    isRunning = false
                    return@Thread
                }

                val nodeModulesDir = File(nodeDir, "node_modules")
                if (!nodeModulesDir.exists()) {
                    Log.i("OpenCode-Node", "Running npm install...")
                    listener?.onOutput("[setup] Installing opencode dependencies...")
                    runNpmInstall(nodeDir)
                }

                val os = System.getProperty("os.arch") ?: "aarch64"
                Log.i("OpenCode-Node", "Architecture: $os")

                val nodeBin = File(nodeDir, "nodejs/bin/node")
                if (!nodeBin.exists()) {
                    val altNodeBin = File(
                        applicationInfo.nativeLibDir,
                        "libnode.so"
                    )
                    if (altNodeBin.exists()) {
                        Log.i("OpenCode-Node", "Using libnode.so from native libs")
                    }
                }

                val processBuilder = ProcessBuilder(
                    "node",
                    mainJs.absolutePath
                ).apply {
                    environment()["NODE_ENV"] = "production"
                    environment()["NODE_PATH"] = nodeModulesDir.absolutePath
                    environment()["HOME"] = nodeDir.absolutePath
                    environment()["OPENCODE_HOME"] = File(filesDir, ".opencode").absolutePath
                    directory(nodeDir)
                    redirectErrorStream(true)
                }

                process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?

                while (process!!.isAlive.also { if (!it) break } && reader.readLine()
                        .also { line = it } != null
                ) {
                    listener?.onOutput(line ?: "")

                    val port = extractPort(line ?: "")
                    if (port > 0) {
                        Log.i("OpenCode-Node", "Server ready on port $port")
                        listener?.onReady(port)
                    }
                }

                val exitCode = process?.waitFor() ?: -1
                Log.i("OpenCode-Node", "Process exited: $exitCode")
                isRunning = false

                if (exitCode != 0) {
                    listener?.onError("Node.js process exited with code: $exitCode")
                }

            } catch (e: Exception) {
                Log.e("OpenCode-Node", "Start error", e)
                listener?.onError(e.message ?: "Unknown error")
                isRunning = false
            }
        }.start()
    }

    fun stop() {
        process?.destroy()
        isRunning = false
    }

    private fun extractPort(output: String): Int {
        val jsonMatcher = portJsonPattern.matcher(output)
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1).toIntOrNull() ?: 0
        }

        val urlMatcher = portPattern.matcher(output)
        if (urlMatcher.find()) {
            return urlMatcher.group(2).toIntOrNull() ?: 0
        }

        return 0
    }

    private fun extractAssets() {
        try {
            val destDir = File(filesDir, "nodejs-project")
            if (destDir.exists() && destDir.listFiles()?.isNotEmpty() == true) {
                Log.i("OpenCode-Node", "Assets already extracted, skipping")
                return
            }

            destDir.mkdirs()

            assets.list("nodejs-project")?.forEach { asset ->
                val destFile = File(destDir, asset)
                if (asset.contains(".")) {
                    assets.open("nodejs-project/$asset").use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            Log.i("OpenCode-Node", "Assets extracted to ${destDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("OpenCode-Node", "Failed to extract assets", e)
        }
    }

    private fun runNpmInstall(nodeDir: File) {
        try {
            val pb = ProcessBuilder(
                "node",
                "-e",
                """
                const { execSync } = require('child_process');
                try {
                    execSync('npm install --production', {
                        cwd: '${nodeDir.absolutePath}',
                        stdio: 'inherit',
                        env: { ...process.env, NODE_ENV: 'production' }
                    });
                } catch(e) {
                    process.exit(1);
                }
                """.trimIndent()
            ).apply {
                environment()["NODE_ENV"] = "production"
                directory(nodeDir)
                inheritIO()
            }

            val installProcess = pb.start()
            val exitCode = installProcess.waitFor()

            if (exitCode != 0) {
                Log.w("OpenCode-Node", "npm install had issues, attempting to continue")
            }
        } catch (e: Exception) {
            Log.e("OpenCode-Node", "npm install failed", e)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
