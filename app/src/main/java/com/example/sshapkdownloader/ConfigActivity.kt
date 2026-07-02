package com.example.sshapkdownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

class ConfigActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var ipAddressEditText: EditText
    private lateinit var remoteApkPathEditText: EditText
    private lateinit var uploadScreenshotsCheckBox: CheckBox
    private lateinit var publicKeyEditText: EditText
    private lateinit var generateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        ipAddressEditText = findViewById(R.id.ipAddressEditText)
        remoteApkPathEditText = findViewById(R.id.remoteApkPathEditText)
        uploadScreenshotsCheckBox = findViewById(R.id.uploadScreenshotsCheckBox)
        publicKeyEditText = findViewById(R.id.publicKeyEditText)
        generateButton = findViewById(R.id.generateButton)
        ipAddressEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveAddress(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        remoteApkPathEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveRemoteApkPath(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        generateButton.setOnClickListener {
            generateKey()
        }
        uploadScreenshotsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveScreenshotUploadEnabled(isChecked)
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener {
            copyPublicKey()
        }
        restoreSavedValues()
    }

    private fun restoreSavedValues() {
        ipAddressEditText.setText(preferences.getString("ip_address", ""))
        remoteApkPathEditText.setText(preferences.getString("remote_apk_path", DEFAULT_REMOTE_APK_PATH))
        uploadScreenshotsCheckBox.isChecked = preferences.getBoolean("upload_screenshots_to_shared_folder", false)
        publicKeyEditText.setText(preferences.getString("public_ssh_key", ""))
    }

    private fun saveAddress(address: String) {
        preferences.edit()
            .putString("ip_address", address)
            .apply()
    }

    private fun saveRemoteApkPath(remoteApkPath: String) {
        preferences.edit()
            .putString("remote_apk_path", remoteApkPath)
            .apply()
    }

    private fun saveScreenshotUploadEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean("upload_screenshots_to_shared_folder", enabled)
            .apply()
    }

    private fun generateKey() {
        generateButton.isEnabled = false
        Toast.makeText(this, getString(R.string.message_generating_key), Toast.LENGTH_SHORT).show()

        Thread {
            runCatching {
                SshKeyGenerator.generate()
            }.onSuccess { keyPair ->
                preferences.edit()
                    .putString("private_ssh_key", keyPair.privateKeyPem)
                    .putString("public_ssh_key", keyPair.publicKeyOpenSsh)
                    .apply()

                runOnUiThread {
                    publicKeyEditText.setText(keyPair.publicKeyOpenSsh)
                    generateButton.isEnabled = true
                    Toast.makeText(this, getString(R.string.message_key_generated), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    generateButton.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(R.string.message_generation_error, error.displayMessage()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun copyPublicKey() {
        val publicKey = publicKeyEditText.text.toString()
        if (publicKey.isBlank()) {
            Toast.makeText(this, getString(R.string.message_no_public_key), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_ssh_public_key), publicKey))
        Toast.makeText(this, getString(R.string.message_public_key_copied), Toast.LENGTH_SHORT).show()
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    companion object {
        private const val DEFAULT_REMOTE_APK_PATH = "~/Artifacts/android/"
    }
}
