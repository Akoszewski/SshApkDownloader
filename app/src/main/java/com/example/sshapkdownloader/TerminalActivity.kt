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
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(TERMINAL_BACKGROUND)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "SSH Terminal"
            textSize = 22f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        disconnectButton = Button(this).apply {
            text = "Disconnect"
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(DANGER)
            setOnClickListener {
                closedByUser = true
                disconnectShell()
                appendOutput("\n[disconnected]\n")
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
            hint = "command"
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
            text = "Send"
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
            }
        }

        shortcutRow.addView(Button(this).apply {
            text = "Ctrl+C"
            setTextColor(TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                writeToShell(byteArrayOf(3))
            }
        })

        shortcutRow.addView(Button(this).apply {
            text = "Clear"
            setTextColor(TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
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
            Toast.makeText(this, "SSH target and generated key are required", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setTerminalEnabled(false)
        appendOutput("Connecting to $address...\n")

        Thread {
            runCatching {
                val sshSession = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                session = sshSession
                sshSession.connect(15_000)

                val shell = sshSession.openChannel("shell") as ChannelShell
                shell.setPty(true)
                shell.setPtyType("xterm")
                val remoteInput = shell.inputStream
                val remoteOutput = shell.outputStream
                channel = shell
                commandOutput = remoteOutput

                shell.connect(15_000)
                runOnUiThread {
                    appendOutput("[connected]\n")
                    setTerminalEnabled(true)
                    focusCommandInput()
                }
                readShellOutput(remoteInput)
            }.onFailure { error ->
                if (!closedByUser) {
                    runOnUiThread {
                        appendOutput("\n[connection error: ${error.message ?: error.javaClass.simpleName}]\n")
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
            runOnUiThread {
                appendOutput(text)
            }
        }

        if (!closedByUser) {
            runOnUiThread {
                appendOutput("\n[remote shell closed]\n")
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
            Toast.makeText(this, "Terminal is not connected", Toast.LENGTH_SHORT).show()
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
                    appendOutput("\n[write error: ${error.message ?: error.javaClass.simpleName}]\n")
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
        outputBuffer.append(text)
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

    companion object {
        private const val MAX_OUTPUT_CHARS = 60_000
        private const val TERMINAL_BACKGROUND = 0xFF111827.toInt()
        private const val OUTPUT_BACKGROUND = 0xFF05070A.toInt()
        private const val SURFACE = 0xFFE5E7EB.toInt()
        private const val PRIMARY = 0xFF2563EB.toInt()
        private const val DANGER = 0xFFB91C1C.toInt()
        private const val TEXT_PRIMARY = 0xFFF9FAFB.toInt()
        private const val TEXT_MUTED = 0xFF9CA3AF.toInt()
        private const val TERMINAL_TEXT = 0xFFB7F7C8.toInt()
    }
}
