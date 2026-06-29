package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

class TerminalActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences("ssh_apk_downloader", Context.MODE_PRIVATE)
    }

    private lateinit var outputTextView: TextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var commandEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var disconnectButton: Button
    private val outputBuffer = StringBuilder()
    private val outputSanitizer = TerminalOutputSanitizer()
    private val writerLock = Any()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var commandOutput: OutputStream? = null

    @Volatile
    private var closedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        connectShell()
    }

    override fun onDestroy() {
        closedByUser = true
        disconnectShell()
        super.onDestroy()
    }

    private fun createContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
            setBackgroundColor(TERMINAL_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = getString(R.string.title_ssh_terminal)
            textSize = 22f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        disconnectButton = Button(this).apply {
            text = getString(R.string.action_disconnect)
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(DANGER)
            setOnClickListener {
                closedByUser = true
                disconnectShell()
                appendOutput(getString(R.string.terminal_disconnected))
                finish()
            }
        }
        header.addView(disconnectButton)
        root.addView(header)

        outputTextView = TextView(this).apply {
            textSize = 14f
            setTextColor(TERMINAL_TEXT)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        outputScrollView = ScrollView(this).apply {
            setBackgroundColor(OUTPUT_BACKGROUND)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(12)
            }
            addView(outputTextView)
        }
        root.addView(outputScrollView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        commandEditText = EditText(this).apply {
            hint = getString(R.string.hint_terminal_command)
            setHintTextColor(TEXT_MUTED)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCommand()
                    true
                } else {
                    false
                }
            }
        }
        controls.addView(commandEditText)

        sendButton = Button(this).apply {
            text = getString(R.string.action_send)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(PRIMARY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
            setOnClickListener {
                sendCommand()
            }
        }
        controls.addView(sendButton)
        root.addView(controls)

        val shortcutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        shortcutRow.addView(Button(this).apply {
            text = getString(R.string.action_ctrl_c)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(DANGER)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                writeToShell(byteArrayOf(3))
            }
        })

        shortcutRow.addView(Button(this).apply {
            text = getString(R.string.action_clear)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(SECONDARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            }
            setOnClickListener {
                outputBuffer.clear()
                outputTextView.text = ""
            }
        })
        root.addView(shortcutRow)

        return root
    }

    private fun connectShell() {
        val address = preferences.getString("ip_address", "")?.trim().orEmpty()
        val privateKey = preferences.getString("private_ssh_key", "").orEmpty()

        if (address.isBlank() || privateKey.isBlank()) {
            Toast.makeText(this, getString(R.string.message_ssh_target_and_key_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setTerminalEnabled(false)
        appendOutput(getString(R.string.terminal_connecting, address))

        Thread {
            runCatching {
                val sshSession = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                session = sshSession
                sshSession.connect(15_000)

                val shell = sshSession.openChannel("shell") as ChannelShell
                shell.setPty(true)
                shell.setPtyType("dumb")
                shell.setEnv("TERM", "dumb")
                shell.setEnv("NO_COLOR", "1")
                val remoteInput = shell.inputStream
                val remoteOutput = shell.outputStream
                channel = shell
                commandOutput = remoteOutput

                shell.connect(15_000)
                runOnUiThread {
                    appendOutput(getString(R.string.terminal_connected))
                    setTerminalEnabled(true)
                    focusCommandInput()
                }
                readShellOutput(remoteInput)
            }.onFailure { error ->
                if (!closedByUser) {
                    runOnUiThread {
                        appendOutput(getString(R.string.terminal_connection_error, error.displayMessage()))
                        setTerminalEnabled(false)
                    }
                }
                disconnectShell()
            }
        }.start()
    }

    private fun readShellOutput(remoteInput: InputStream) {
        val buffer = ByteArray(4096)
        while (!closedByUser) {
            val bytesRead = remoteInput.read(buffer)
            if (bytesRead < 0) {
                break
            }
            val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
            val cleanText = outputSanitizer.clean(text)
            runOnUiThread {
                appendOutput(cleanText)
            }
        }

        if (!closedByUser) {
            runOnUiThread {
                appendOutput(getString(R.string.terminal_remote_shell_closed))
                setTerminalEnabled(false)
            }
            disconnectShell()
        }
    }

    private fun sendCommand() {
        val command = commandEditText.text.toString()
        if (command.isBlank()) {
            writeToShell("\n".toByteArray(Charsets.UTF_8))
            return
        }

        writeToShell("$command\n".toByteArray(Charsets.UTF_8))
        commandEditText.setText("")
    }

    private fun writeToShell(bytes: ByteArray) {
        val output = commandOutput
        if (output == null || channel?.isClosed == true) {
            Toast.makeText(this, getString(R.string.message_terminal_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            runCatching {
                synchronized(writerLock) {
                    output.write(bytes)
                    output.flush()
                }
            }.onFailure { error ->
                runOnUiThread {
                    appendOutput(getString(R.string.terminal_write_error, error.displayMessage()))
                    setTerminalEnabled(false)
                }
            }
        }.start()
    }

    private fun disconnectShell() {
        runCatching {
            commandOutput?.close()
        }
        commandOutput = null
        runCatching {
            channel?.disconnect()
        }
        channel = null
        runCatching {
            session?.disconnect()
        }
        session = null
    }

    private fun appendOutput(text: String) {
        text.forEach { char ->
            if (char == '\b') {
                if (outputBuffer.isNotEmpty()) {
                    outputBuffer.deleteAt(outputBuffer.length - 1)
                }
            } else {
                outputBuffer.append(char)
            }
        }
        if (outputBuffer.length > MAX_OUTPUT_CHARS) {
            outputBuffer.delete(0, outputBuffer.length - MAX_OUTPUT_CHARS)
        }
        outputTextView.text = outputBuffer.toString()
        outputScrollView.post {
            outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun setTerminalEnabled(enabled: Boolean) {
        commandEditText.isEnabled = enabled
        sendButton.isEnabled = enabled
    }

    private fun focusCommandInput() {
        commandEditText.requestFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(commandEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 60_000
        private const val TERMINAL_BACKGROUND = 0xFF111827.toInt()
        private const val OUTPUT_BACKGROUND = 0xFF05070A.toInt()
        private const val SURFACE = 0xFFE5E7EB.toInt()
        private const val PRIMARY = 0xFF2563EB.toInt()
        private const val SECONDARY = 0xFF475569.toInt()
        private const val DANGER = 0xFFB91C1C.toInt()
        private const val TEXT_PRIMARY = 0xFFF9FAFB.toInt()
        private const val TEXT_MUTED = 0xFF9CA3AF.toInt()
        private const val TERMINAL_TEXT = 0xFFB7F7C8.toInt()
    }
}
