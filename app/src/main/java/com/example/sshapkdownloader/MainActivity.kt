package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var ipAddressEditText: EditText
    private lateinit var sshKeyEditText: EditText
    private lateinit var apkListContainer: LinearLayout
    private var sshKeyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setPadding(32, 36, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "SshApkDownloader"
            textSize = 24f
            gravity = Gravity.START
        })

        ipAddressEditText = EditText(this).apply {
            hint = "IP address"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        root.addView(ipAddressEditText)

        val sshKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        sshKeyEditText = EditText(this).apply {
            hint = "SSH key"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sshKeyRow.addView(sshKeyEditText)

        sshKeyRow.addView(ImageButton(this).apply {
            contentDescription = "Show SSH key"
            setImageResource(android.R.drawable.ic_menu_view)
            setOnClickListener {
                toggleSshKeyVisibility()
            }
        })
        root.addView(sshKeyRow)

        root.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                saveValues()
                Toast.makeText(this@MainActivity, "Connect pressed", Toast.LENGTH_SHORT).show()
            }
        })

        apkListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(apkListContainer)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun restoreSavedValues() {
        ipAddressEditText.setText(preferences.getString("ip_address", ""))
        sshKeyEditText.setText(preferences.getString("ssh_key", ""))
    }

    private fun saveValues() {
        preferences.edit()
            .putString("ip_address", ipAddressEditText.text.toString())
            .putString("ssh_key", sshKeyEditText.text.toString())
            .apply()
    }

    private fun toggleSshKeyVisibility() {
        sshKeyVisible = !sshKeyVisible
        sshKeyEditText.inputType = if (sshKeyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        sshKeyEditText.setSelection(sshKeyEditText.text.length)
    }
}
