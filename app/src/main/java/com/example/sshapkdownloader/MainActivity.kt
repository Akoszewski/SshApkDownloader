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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var ipAddressEditText: EditText
    private lateinit var apkListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermission()
        setContentView(createContentView())
        restoreSavedValues()
    }

    override fun onPause() {
        super.onPause()
        saveValues()
    }

    private fun createContentView(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
            setBackgroundColor(SCREEN_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        header.addView(Button(this).apply {
            text = getString(R.string.action_terminal)
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(TERMINAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(8)
            }
            setOnClickListener {
                openTerminal()
            }
        })

        header.addView(Button(this).apply {
            text = getString(R.string.action_configuration)
            textSize = 12f
            setTextColor(PRIMARY)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
            }
        })

        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(42), 0, 0)
        }

        ipAddressEditText = EditText(this).apply {
            hint = getString(R.string.hint_ssh_target)
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        content.addView(ipAddressEditText)

        content.addView(Button(this).apply {
            text = getString(R.string.action_connect)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(22)
            }
            setOnClickListener {
                connectAndLoadApks()
            }
        })

        apkListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(apkListContainer)

        root.addView(content)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun restoreSavedValues() {
        ipAddressEditText.setText(preferences.getString("ip_address", ""))
    }

    private fun saveValues() {
        preferences.edit()
            .putString("ip_address", ipAddressEditText.text.toString())
            .apply()
    }

    private fun connectAndLoadApks() {
        saveValues()
        val address = ipAddressEditText.text.toString().trim()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        apkListContainer.removeAllViews()
        showToast(getString(R.string.message_connecting))

        Thread {
            runCatching {
                val session = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    listRemoteApks(session)
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

    private fun listRemoteApks(session: Session): List<String> {
        val command = "find ~/Artifacts/android -maxdepth 1 -type f -name '*.apk' -printf '%f\\n' | sort"
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
                setTextColor(TEXT_MUTED)
            })
            return
        }

        apkNames.forEach { apkName ->
            apkListContainer.addView(Button(this).apply {
                text = apkName
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(ACCENT)
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

    private fun downloadApk(apkName: String) {
        saveValues()
        val address = ipAddressEditText.text.toString().trim()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        showToast(getString(R.string.message_download_started, apkName))

        Thread {
            runCatching {
                val session = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                try {
                    session.connect(15_000)
                    downloadRemoteApk(session, apkName)
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
        saveValues()
        val address = ipAddressEditText.text.toString().trim()
        val privateKey = getStoredPrivateKey()

        if (address.isEmpty() || privateKey.isBlank()) {
            showToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        startActivity(Intent(this, TerminalActivity::class.java))
    }

    private fun downloadRemoteApk(session: Session, apkName: String): Uri {
        ApkNameValidator.requireValid(apkName)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(15_000)
        try {
            channel.cd("Artifacts/android")
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
        private const val SCREEN_BACKGROUND = 0xFFF5F7FB.toInt()
        private const val SURFACE = 0xFFFFFFFF.toInt()
        private const val PRIMARY = 0xFF2563EB.toInt()
        private const val ACCENT = 0xFF0F766E.toInt()
        private const val TERMINAL = 0xFF1F2937.toInt()
        private const val TEXT_PRIMARY = 0xFF172033.toInt()
        private const val TEXT_MUTED = 0xFF667085.toInt()
    }
}
