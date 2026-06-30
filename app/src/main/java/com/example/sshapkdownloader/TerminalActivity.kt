package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class TerminalActivity : Activity(), TerminalSessionManager.Listener {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var outputTextView: TextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var commandEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var copyCommandButton: ImageButton
    private lateinit var autocompleteButton: ImageButton
    private lateinit var previousCommandButton: ImageButton
    private lateinit var exitButton: Button
    private lateinit var disconnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        outputTextView = findViewById(R.id.outputTextView)
        outputScrollView = findViewById(R.id.outputScrollView)
        commandEditText = findViewById(R.id.commandEditText)
        sendButton = findViewById(R.id.sendButton)
        copyCommandButton = findViewById(R.id.copyCommandButton)
        autocompleteButton = findViewById(R.id.autocompleteButton)
        previousCommandButton = findViewById(R.id.previousCommandButton)
        exitButton = findViewById(R.id.exitButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        keepCommandInputAboveKeyboard(findViewById(R.id.terminalRoot))
        disconnectButton.setOnClickListener {
            TerminalSessionManager.disconnectByUser()
            finish()
        }
        commandEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
        sendButton.setOnClickListener {
            sendCommand()
        }
        copyCommandButton.setOnClickListener {
            showNotImplemented()
        }
        exitButton.setOnClickListener {
            TerminalSessionManager.sendBytes(CONTROL_C_BYTES)
        }
        autocompleteButton.setOnClickListener {
            showNotImplemented()
        }
        previousCommandButton.setOnClickListener {
            showNotImplemented()
        }
        TerminalSessionManager.attachListener(this)
        connectShell()
    }

    override fun onDestroy() {
        TerminalSessionManager.detachListener(this)
        super.onDestroy()
    }

    override fun onTerminalOutputChanged(output: CharSequence) {
        outputTextView.text = output
        scrollOutputToBottom()
    }

    override fun onTerminalEnabledChanged(enabled: Boolean) {
        commandEditText.isEnabled = enabled
        sendButton.isEnabled = enabled
        copyCommandButton.isEnabled = enabled
        exitButton.isEnabled = enabled
        autocompleteButton.isEnabled = enabled
        previousCommandButton.isEnabled = enabled
        if (enabled) {
            focusCommandInput()
        }
    }

    override fun onTerminalDisconnected() {
        commandEditText.isEnabled = false
        sendButton.isEnabled = false
        copyCommandButton.isEnabled = false
        exitButton.isEnabled = false
        autocompleteButton.isEnabled = false
        previousCommandButton.isEnabled = false
    }

    override fun onTerminalConnectionUnavailable() {
        Toast.makeText(this, getString(R.string.message_terminal_not_connected), Toast.LENGTH_SHORT).show()
    }

    private fun keepCommandInputAboveKeyboard(rootView: View) {
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom

        rootView.setOnApplyWindowInsetsListener { view, insets ->
            val keyboardBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            val statusTopInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val navigationBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                0
            }
            val extraKeyboardPadding = (keyboardBottomInset - navigationBottomInset).coerceAtLeast(0)

            view.setPadding(
                initialPaddingLeft,
                initialPaddingTop + statusTopInset,
                initialPaddingRight,
                initialPaddingBottom + extraKeyboardPadding
            )
            scrollOutputToBottom()
            insets
        }
    }

    private fun connectShell() {
        val address = preferences.getString("ip_address", "")?.trim().orEmpty()
        val privateKey = preferences.getString("private_ssh_key", "").orEmpty()

        if (address.isBlank() || privateKey.isBlank()) {
            Toast.makeText(this, getString(R.string.message_ssh_target_and_key_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        commandEditText.isEnabled = false
        sendButton.isEnabled = false
        copyCommandButton.isEnabled = false
        exitButton.isEnabled = false
        autocompleteButton.isEnabled = false
        previousCommandButton.isEnabled = false
        TerminalSessionManager.connect(this, address, privateKey)
    }

    private fun sendCommand() {
        val command = commandEditText.text.toString()
        TerminalSessionManager.sendCommand(command)
        if (command.isNotBlank()) {
            commandEditText.setText("")
        }
    }

    private fun scrollOutputToBottom() {
        outputScrollView.post {
            outputTextView.post {
                val outputContent = outputScrollView.getChildAt(0) ?: return@post
                val scrollRange = (outputContent.bottom + outputScrollView.paddingBottom - outputScrollView.height)
                    .coerceAtLeast(0)
                outputScrollView.scrollTo(0, scrollRange)
            }
        }
    }

    private fun focusCommandInput() {
        commandEditText.requestFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(commandEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showNotImplemented() {
        Toast.makeText(this, getString(R.string.message_not_implemented), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val CONTROL_C_BYTES = byteArrayOf(0x03)
    }
}
