package com.example.sshapkdownloader

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.res.ColorStateList
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var apkBinaryHashTextView: TextView
    private lateinit var apkListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermission()
        setContentView(R.layout.activity_main)
        apkBinaryHashTextView = findViewById(R.id.apkBinaryHashTextView)
        apkListContainer = findViewById(R.id.apkListContainer)
        findViewById<Button>(R.id.terminalButton).setOnClickListener {
            openTerminal()
        }
        findViewById<Button>(R.id.configurationButton).setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connectAndLoadApks()
        }
        displayApkBinaryHash()
    }

    override fun onResume() {
        super.onResume()
        if (preferences.getBoolean("upload_screenshots_to_shared_folder", false) && canReadImages()) {
            ScreenshotUploadManager.start(this)
        } else {
            ScreenshotUploadManager.stop(this)
        }
    }

    private fun connectAndLoadApks() {
        val address = getStoredAddress()
        val privateKey = getStoredPrivateKey()
        val remoteApkPath = getStoredRemoteApkPath()

        if (address.isEmpty() || privateKey.isBlank() || remoteApkPath.isBlank()) {
            showToast(getString(R.string.message_ssh_target_key_and_path_required))
            return
        }

        apkListContainer.removeAllViews()
        showToast(getString(R.string.message_connecting))

        Thread {
            runCatching {
                val session = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    listRemoteApks(session, remoteApkPath)
                } finally {
                    session.disconnect()
                }
            }.onSuccess { apkNames ->
                runOnUiThread {
                    displayApkButtons(apkNames)
                }
            }.onFailure { error ->
                runOnUiThread {
                    apkListContainer.removeAllViews()
                    showToast(getString(R.string.message_ssh_error, error.displayMessage()))
                }
            }
        }.start()
    }

    private fun listRemoteApks(session: Session, remoteApkPath: String): List<String> {
        val command = "find ${remoteApkPath.toShellPathExpression()} -maxdepth 1 -type f -name '*.apk' -printf '%f\\n' | sort"
        val output = executeRemoteCommand(session, command)
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".apk") }
            .toList()
    }

    private fun executeRemoteCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()

        channel.setCommand(command)
        channel.outputStream = output
        channel.setErrStream(errorOutput)
        channel.connect(15_000)

        while (!channel.isClosed) {
            Thread.sleep(100)
        }

        val exitStatus = channel.exitStatus
        channel.disconnect()

        if (exitStatus != 0) {
            val message = errorOutput.toString(Charsets.UTF_8.name()).ifBlank {
                "Remote command failed with exit status $exitStatus"
            }
            error(message)
        }

        return output.toString(Charsets.UTF_8.name())
    }

    private fun displayApkButtons(apkNames: List<String>) {
        apkListContainer.removeAllViews()

        if (apkNames.isEmpty()) {
            apkListContainer.addView(TextView(this).apply {
                text = getString(R.string.message_no_apk_files_found)
                textSize = 16f
                setTextColor(getColor(R.color.text_muted))
            })
            return
        }

        apkNames.forEach { apkName ->
            apkListContainer.addView(Button(this).apply {
                text = apkName
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
                setOnClickListener {
                    downloadApk(apkName)
                }
            })
        }
    }

    private fun displayApkBinaryHash() {
        Thread {
            val text = runCatching {
                getString(R.string.apk_binary_hash, installedApkSha256())
            }.getOrElse { error ->
                getString(R.string.apk_binary_hash_error, error.displayMessage())
            }
            runOnUiThread {
                apkBinaryHashTextView.text = text
            }
        }.start()
    }

    private fun installedApkSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) {
                    break
                }
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun downloadApk(apkName: String) {
        val address = getStoredAddress()
        val privateKey = getStoredPrivateKey()
        val remoteApkPath = getStoredRemoteApkPath()

        if (address.isEmpty() || privateKey.isBlank() || remoteApkPath.isBlank()) {
            showToast(getString(R.string.message_ssh_target_key_and_path_required))
            return
        }

        showToast(getString(R.string.message_download_started, apkName))

        Thread {
            runCatching {
                val session = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    downloadRemoteApk(session, remoteApkPath, apkName)
                } finally {
                    session.disconnect()
                }
            }.onSuccess { apkUri ->
                showToastOnUiThread(getString(R.string.message_download_completed, apkName))
                showDownloadedNotification(apkName, apkUri)
            }.onFailure { error ->
                showToastOnUiThread(getString(R.string.message_download_error, error.displayMessage()))
            }
        }.start()
    }

    private fun openTerminal() {
        val address = getStoredAddress()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        startActivity(Intent(this, TerminalActivity::class.java))
    }

    private fun downloadRemoteApk(session: Session, remoteApkPath: String, apkName: String): Uri {
        ApkNameValidator.requireValid(apkName)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(15_000)
        try {
            channel.cd(remoteApkPath.toSftpDirectory())
            val destination = openDownloadDestination(apkName)
            destination.outputStream.use { output ->
                channel.get(apkName, output)
            }
            destination.markComplete()
            return destination.uri
        } finally {
            channel.disconnect()
        }
    }

    private fun openDownloadDestination(apkName: String): DownloadDestination {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apkName)
                put(MediaStore.Downloads.MIME_TYPE, DownloadedApkProvider.APK_MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SshApkDownloader")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create download file")
            val outputStream = contentResolver.openOutputStream(uri) ?: error("Cannot open download file")

            DownloadDestination(uri, outputStream) {
                val completedValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                contentResolver.update(uri, completedValues, null, null)
            }
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SshApkDownloader"
            )
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                error("Cannot create ${downloadsDir.absolutePath}")
            }
            val uri = Uri.Builder()
                .scheme("content")
                .authority("$packageName.downloaded-apks")
                .appendPath(apkName)
                .build()
            DownloadDestination(uri, File(downloadsDir, apkName).outputStream()) {}
        }
    }

    private fun showDownloadedNotification(apkName: String, apkUri: Uri) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, DownloadedApkProvider.APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            apkName.hashCode(),
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_apk_downloaded))
            .setContentText(apkName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager().notify(apkName.hashCode(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.notification_channel_apk_downloads),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun notificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, DOWNLOAD_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getStoredPrivateKey(): String {
        return preferences.getString("private_ssh_key", "") ?: ""
    }

    private fun getStoredAddress(): String {
        return preferences.getString("ip_address", "")?.trim().orEmpty()
    }

    private fun getStoredRemoteApkPath(): String {
        return preferences.getString("remote_apk_path", DEFAULT_REMOTE_APK_PATH)?.trim().orEmpty()
    }

    private fun showToastOnUiThread(message: String) {
        runOnUiThread {
            showToast(message)
        }
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    private data class DownloadDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val markComplete: () -> Unit
    )

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "apk_downloads"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 100
        private const val DEFAULT_REMOTE_APK_PATH = "~/Artifacts/android/"
    }
}
