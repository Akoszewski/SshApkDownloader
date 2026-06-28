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
    private lateinit var publicKeyEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
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

        root.addView(Button(this).apply {
            text = "Generuj klucz"
            setOnClickListener {
                Toast.makeText(
                    this@ConfigActivity,
                    "Generowanie klucza zostanie dodane w kolejnym kroku",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

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
