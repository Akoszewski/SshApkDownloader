package com.example.sshapkdownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ConfigActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var publicKeyEditText: EditText
    private lateinit var generateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        restorePublicKey()
    }

    private fun createContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Konfiguracja"
            textSize = 24f
        })

        generateButton = Button(this).apply {
            text = "Generuj klucz"
            setOnClickListener {
                generateKey()
            }
        }
        root.addView(generateButton)

        val publicKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        publicKeyEditText = EditText(this).apply {
            hint = "Publiczny klucz SSH"
            isSingleLine = false
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        publicKeyRow.addView(publicKeyEditText)

        publicKeyRow.addView(Button(this).apply {
            text = "Kopiuj"
            setOnClickListener {
                copyPublicKey()
            }
        })

        root.addView(publicKeyRow)
        return root
    }

    private fun restorePublicKey() {
        publicKeyEditText.setText(preferences.getString("public_ssh_key", ""))
    }

    private fun generateKey() {
        generateButton.isEnabled = false
        Toast.makeText(this, "Generowanie klucza", Toast.LENGTH_SHORT).show()

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
                    Toast.makeText(this, "Wygenerowano klucz", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    generateButton.isEnabled = true
                    Toast.makeText(
                        this,
                        "Błąd generowania: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun copyPublicKey() {
        val publicKey = publicKeyEditText.text.toString()
        if (publicKey.isBlank()) {
            Toast.makeText(this, "Brak klucza publicznego", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey))
        Toast.makeText(this, "Skopiowano klucz publiczny", Toast.LENGTH_SHORT).show()
    }
}
