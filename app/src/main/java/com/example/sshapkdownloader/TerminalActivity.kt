package com.example.sshapkdownloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private lateinit var nextCommandButton: ImageButton
    private lateinit var exitButton: Button
    private var remoteInputPrimed = false
    private var remoteInputPromptColumn: Int? = null
    private var inputSyncGeneration = 0

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
        nextCommandButton = findViewById(R.id.nextCommandButton)
        exitButton = findViewById(R.id.exitButton)
        keepCommandInputAboveKeyboard(findViewById(R.id.terminalRoot))
        keepRemoteTerminalSizeInSync()
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
            copyCommand()
        }
        exitButton.setOnClickListener {
            TerminalSessionManager.sendBytes(CONTROL_C_BYTES)
            remoteInputPrimed = false
            remoteInputPromptColumn = null
        }
        autocompleteButton.setOnClickListener {
            sendInputEditingKey(TAB_KEY_BYTES)
        }
        previousCommandButton.setOnClickListener {
            sendInputEditingKey(UP_ARROW_KEY_BYTES)
        }
        nextCommandButton.setOnClickListener {
            sendInputEditingKey(DOWN_ARROW_KEY_BYTES)
        }
        TerminalSessionManager.attachListener(this)
        connectShell()
    }

    override fun onDestroy() {
        TerminalSessionManager.detachListener(this)
        super.onDestroy()
    }

    override fun onTerminalOutputChanged(output: CharSequence) {
        val wasAtBottom = isOutputScrolledToBottom()
        outputTextView.text = output
        if (wasAtBottom) {
            scrollOutputToBottom()
        }
    }

    override fun onTerminalEnabledChanged(enabled: Boolean) {
        commandEditText.isEnabled = enabled
        sendButton.isEnabled = enabled
        copyCommandButton.isEnabled = enabled
        exitButton.isEnabled = enabled
        autocompleteButton.isEnabled = enabled
        previousCommandButton.isEnabled = enabled
        nextCommandButton.isEnabled = enabled
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
        nextCommandButton.isEnabled = false
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

    private fun keepRemoteTerminalSizeInSync() {
        outputScrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncRemoteTerminalSize()
        }
        outputTextView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncRemoteTerminalSize()
        }
        outputScrollView.post {
            syncRemoteTerminalSize()
        }
    }

    private fun syncRemoteTerminalSize() {
        val availableWidth = outputScrollView.width - outputScrollView.paddingLeft - outputScrollView.paddingRight
        val availableHeight = outputScrollView.height - outputScrollView.paddingTop - outputScrollView.paddingBottom
        val characterWidth = outputTextView.paint.measureText("M").takeIf { it > 0f } ?: return
        val lineHeight = outputTextView.lineHeight.takeIf { it > 0 } ?: return
        if (availableWidth <= 0 || availableHeight <= 0) {
            return
        }

        val columns = (availableWidth / characterWidth).toInt()
        val rows = availableHeight / lineHeight
        TerminalSessionManager.resizeTerminal(columns, rows)
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
        nextCommandButton.isEnabled = false
        TerminalSessionManager.connect(this, address, privateKey)
    }

    private fun sendCommand() {
        val command = commandEditText.text.toString()
        if (remoteInputPrimed) {
            TerminalSessionManager.sendPrimedInputCommand(command)
            remoteInputPrimed = false
            remoteInputPromptColumn = null
        } else {
            TerminalSessionManager.sendCommand(command)
        }
        if (command.isNotBlank()) {
            commandEditText.setText("")
        }
    }

    private fun sendInputEditingKey(keyBytes: ByteArray) {
        val command = commandEditText.text.toString()
        val promptColumn = remoteInputPromptColumn ?: TerminalSessionManager.currentInputPromptColumn()
        remoteInputPrimed = true
        remoteInputPromptColumn = promptColumn
        TerminalSessionManager.sendInputEditingKey(command, keyBytes)
        if (keyBytes.contentEquals(TAB_KEY_BYTES)) {
            syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix = command)
        } else {
            syncCommandInputAfterRemoteEdit(promptColumn, waitUntilDifferentFrom = command)
        }
    }

    private fun syncCommandInputAfterRemoteEdit(
        promptColumn: Int,
        expectedPrefix: String? = null,
        waitUntilDifferentFrom: String? = null,
        attempt: Int = 1
    ) {
        val generation = ++inputSyncGeneration
        mainHandler.postDelayed({
            if (generation != inputSyncGeneration || !remoteInputPrimed) {
                return@postDelayed
            }
            val remoteInput = TerminalSessionManager.currentInputAfterPromptColumn(promptColumn)
            val waitingForEcho = !expectedPrefix.isNullOrEmpty() &&
                !remoteInput.startsWith(expectedPrefix) &&
                attempt < INPUT_EDIT_SYNC_MAX_ATTEMPTS
            val waitingForHistory = waitUntilDifferentFrom != null &&
                remoteInput == waitUntilDifferentFrom &&
                attempt < INPUT_EDIT_SYNC_MAX_ATTEMPTS
            if (waitingForEcho) {
                syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix, waitUntilDifferentFrom, attempt + 1)
                return@postDelayed
            }
            if (waitingForHistory) {
                syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix, waitUntilDifferentFrom, attempt + 1)
                return@postDelayed
            }
            commandEditText.setText(remoteInput)
            commandEditText.setSelection(commandEditText.text.length)
            remoteInputPromptColumn = promptColumn
            focusCommandInput()
        }, INPUT_EDIT_SYNC_DELAY_MS)
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

    private fun isOutputScrolledToBottom(): Boolean {
        val outputContent = outputScrollView.getChildAt(0) ?: return true
        val scrollRange = (outputContent.bottom + outputScrollView.paddingBottom - outputScrollView.height)
            .coerceAtLeast(0)
        return scrollRange - outputScrollView.scrollY <= OUTPUT_SCROLL_BOTTOM_TOLERANCE_PX
    }

    private fun focusCommandInput() {
        commandEditText.requestFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(commandEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun copyCommand() {
        val command = commandEditText.text.toString()
        if (command.isBlank()) {
            Toast.makeText(this, getString(R.string.message_no_command), Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_terminal_command), command))
        Toast.makeText(this, getString(R.string.message_command_copied), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val CONTROL_C_BYTES = byteArrayOf(0x03)
        val TAB_KEY_BYTES = byteArrayOf(0x09)
        val UP_ARROW_KEY_BYTES = "\u001B[A".toByteArray(Charsets.UTF_8)
        val DOWN_ARROW_KEY_BYTES = "\u001B[B".toByteArray(Charsets.UTF_8)
        const val INPUT_EDIT_SYNC_DELAY_MS = 150L
        const val INPUT_EDIT_SYNC_MAX_ATTEMPTS = 6
        const val OUTPUT_SCROLL_BOTTOM_TOLERANCE_PX = 24
    }
}
