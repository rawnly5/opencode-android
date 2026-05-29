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
    private var isRunning = false

    fun setListener(listener: NodeListener) {
        this.listener = listener
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
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
                val projectDir = File(filesDir, "nodejs-project")
                val nodeBinary: File
                val mainJs = File(projectDir, "main.js")
                val nodeModulesDir = File(projectDir, "node_modules")
                val nodeBinDir = File(projectDir, "nodejs")

                if (!mainJs.exists()) {
                    Log.i("OpenCode-Node", "Extracting assets to ${projectDir.absolutePath}")
                    extractAssets(projectDir)
                }

                nodeBinary = findNodeBinary(projectDir)

                if (nodeBinary == null || !nodeBinary.exists()) {
                    listener?.onError("Node.js binary not found for this device architecture")
                    isRunning = false
                    return@Thread
                }

                Log.i("OpenCode-Node", "Using Node.js: ${nodeBinary.absolutePath}")
                nodeBinary.setExecutable(true)

                if (!nodeModulesDir.exists() || !nodeModulesDir.listFiles().isNullOrEmpty().not()) {
                    listener?.onOutput("[setup] Installing opencode dependencies...")
                    runNpmInstall(nodeBinary, projectDir)
                }

                val env = mutableMapOf<String, String>(
                    "NODE_ENV" to "production",
                    "NODE_PATH" to nodeModulesDir.absolutePath,
                    "HOME" to projectDir.absolutePath,
                    "OPENCODE_HOME" to File(filesDir, ".opencode").absolutePath
                )

                val processBuilder = ProcessBuilder(
                    nodeBinary.absolutePath,
                    mainJs.absolutePath
                ).apply {
                    environment().putAll(env)
                    directory(projectDir)
                    redirectErrorStream(true)
                }

                process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?

                while (process!!.isAlive && reader.readLine().also { line = it } != null) {
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
                    listener?.onError("Node.js exited with code: $exitCode")
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

    private fun findNodeBinary(projectDir: File): File? {
        val arch = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
        Log.i("OpenCode-Node", "Device architecture: ${arch}")

        val candidates = listOf(
            File(projectDir, "nodejs/bin/node"),
            File(projectDir, "nodejs/$arch/bin/node"),
            File(projectDir, "nodejs/node"),
        )

        for (candidate in candidates) {
            if (candidate.exists()) {
                return candidate
            }
        }

        val altDir = File(projectDir, "nodejs").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("node-v") }

        if (altDir != null) {
            val bin = File(altDir, "bin/node")
            if (bin.exists()) return bin
        }

        return null
    }

    private fun extractPort(output: String): Int {
        val jsonMatcher = """"port"\s*:\s*(\d+)""".toRegex().find(output)
        if (jsonMatcher != null) {
            return jsonMatcher.groupValues[1].toIntOrNull() ?: 0
        }

        val urlMatcher = """https?://127\.0\.0\.1[:\/](\d+)""".toRegex().find(output)
        if (urlMatcher != null) {
            return urlMatcher.groupValues[1].toIntOrNull() ?: 0
        }

        return 0
    }

    private fun extractAssets(destDir: File) {
        try {
            destDir.mkdirs()

            val assetPath = "nodejs-project"
            assets.list(assetPath)?.forEach { asset ->
                if (asset == "nodejs") return@forEach

                val destFile = File(destDir, asset)
                destFile.parentFile?.mkdirs()

                try {
                    assets.open("$assetPath/$asset").use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OpenCode-Node", "Could not extract $asset: ${e.message}")
                }
            }

            Log.i("OpenCode-Node", "Assets extracted to ${destDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("OpenCode-Node", "Failed to extract assets", e)
        }
    }

    private fun runNpmInstall(nodeBinary: File, nodeDir: File) {
        try {
            listener?.onOutput("[setup] npm install in progress (internet required)...")

            val pb = ProcessBuilder(
                nodeBinary.absolutePath,
                "-e",
                """
                const { execSync } = require('child_process');
                try {
                    execSync('npm install --production --no-optional', {
                        cwd: ${nodeDir.absolutePath},
                        stdio: 'inherit',
                        env: { ...process.env, NODE_ENV: 'production' }
                    });
                } catch(e) {
                    process.exit(1);
                }
                """.trimIndent()
            ).apply {
                directory(nodeDir)
                inheritIO()
            }

            val installProc = pb.start()
            val exitCode = installProc.waitFor()

            if (exitCode != 0) {
                Log.w("OpenCode-Node", "npm install had issues, attempting alternate method")
                val npmResult = ProcessBuilder(
                    nodeBinary.absolutePath,
                    File(nodeDir, "node_modules/npm/bin/npm-cli.js").absolutePath,
                    "install", "--production", "--no-optional"
                ).apply {
                    directory(nodeDir)
                    inheritIO()
                }.start().waitFor()

                if (npmResult != 0) {
                    listener?.onOutput("[warn] npm install had issues, trying without npm...")
                }
            }

            listener?.onOutput("[setup] Dependencies installed")
        } catch (e: Exception) {
            Log.e("OpenCode-Node", "npm install failed", e)
            listener?.onOutput("[error] npm install failed: ${e.message}")
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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
