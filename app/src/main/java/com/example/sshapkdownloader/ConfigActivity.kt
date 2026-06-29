package com.example.sshapkdownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(SCREEN_BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.title_configuration)
            textSize = 26f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        })

        generateButton = Button(this).apply {
            text = getString(R.string.action_generate_key)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(14)
            }
            setOnClickListener {
                generateKey()
            }
        }
        root.addView(generateButton)

        val publicKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        publicKeyEditText = EditText(this).apply {
            hint = getString(R.string.hint_ssh_public_key)
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            isSingleLine = false
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        publicKeyRow.addView(publicKeyEditText)

        publicKeyRow.addView(Button(this).apply {
            text = getString(R.string.action_copy)
            setTextColor(PRIMARY)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(10)
            }
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    companion object {
        private const val SCREEN_BACKGROUND = 0xFFF5F7FB.toInt()
        private const val SURFACE = 0xFFFFFFFF.toInt()
        private const val PRIMARY = 0xFF2563EB.toInt()
        private const val TEXT_PRIMARY = 0xFF172033.toInt()
        private const val TEXT_MUTED = 0xFF667085.toInt()
    }
}
