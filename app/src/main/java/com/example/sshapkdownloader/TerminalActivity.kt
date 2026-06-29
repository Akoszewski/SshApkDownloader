package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
    private val outputBuffer = SpannableStringBuilder()
    private val outputSanitizer = TerminalOutputSanitizer()
    private val writerLock = Any()
    private var currentTextColor = DEFAULT_TEXT_COLOR
    private var currentTextStyle = Typeface.NORMAL

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
        setContentView(R.layout.activity_terminal)
        outputTextView = findViewById(R.id.outputTextView)
        outputScrollView = findViewById(R.id.outputScrollView)
        commandEditText = findViewById(R.id.commandEditText)
        sendButton = findViewById(R.id.sendButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        keepCommandInputAboveKeyboard(findViewById(R.id.terminalRoot))
        disconnectButton.setOnClickListener {
            closedByUser = true
            disconnectShell()
            appendOutput(getString(R.string.terminal_disconnected))
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
        findViewById<Button>(R.id.ctrlCButton).setOnClickListener {
            writeToShell(byteArrayOf(3))
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            outputBuffer.clear()
            resetTerminalStyle()
            outputTextView.text = ""
        }
        connectShell()
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

    override fun onDestroy() {
        closedByUser = true
        disconnectShell()
        super.onDestroy()
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
                shell.setPtyType("xterm-256color")
                shell.setEnv("TERM", "xterm-256color")
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
        var index = 0
        while (index < text.length) {
            if (text[index] == ESCAPE) {
                val consumed = applyAnsiSequence(text, index)
                if (consumed > index) {
                    index = consumed
                    continue
                }
            }

            if (text[index] == '\b') {
                if (outputBuffer.isNotEmpty()) {
                    outputBuffer.delete(outputBuffer.length - 1, outputBuffer.length)
                }
                index++
            } else {
                val nextControlIndex = findNextTerminalControl(text, index)
                appendStyledText(text.substring(index, nextControlIndex))
                index = nextControlIndex
            }
        }
        if (outputBuffer.length > MAX_OUTPUT_CHARS) {
            outputBuffer.delete(0, outputBuffer.length - MAX_OUTPUT_CHARS)
        }
        outputTextView.text = outputBuffer
        scrollOutputToBottom()
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

    private fun findNextTerminalControl(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] != ESCAPE && text[index] != '\b') {
            index++
        }
        return index
    }

    private fun appendStyledText(text: String) {
        if (text.isEmpty()) {
            return
        }

        val start = outputBuffer.length
        outputBuffer.append(text)
        outputBuffer.setSpan(
            ForegroundColorSpan(currentTextColor),
            start,
            outputBuffer.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (currentTextStyle != Typeface.NORMAL) {
            outputBuffer.setSpan(
                StyleSpan(currentTextStyle),
                start,
                outputBuffer.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyAnsiSequence(text: String, start: Int): Int {
        if (start + 2 >= text.length || text[start + 1] != '[') {
            return start
        }

        var index = start + 2
        while (index < text.length && text[index] != 'm') {
            index++
        }
        if (index >= text.length) {
            return start
        }

        val parameters = text.substring(start + 2, index)
            .split(';')
            .map { parameter -> parameter.toIntOrNull() ?: 0 }
            .ifEmpty { listOf(0) }
        applySgrParameters(parameters)
        return index + 1
    }

    private fun applySgrParameters(parameters: List<Int>) {
        var index = 0
        while (index < parameters.size) {
            when (val parameter = parameters[index]) {
                0 -> resetTerminalStyle()
                1 -> currentTextStyle = Typeface.BOLD
                22 -> currentTextStyle = Typeface.NORMAL
                30, 31, 32, 33, 34, 35, 36, 37 -> currentTextColor = ANSI_COLORS[parameter - 30]
                39 -> currentTextColor = DEFAULT_TEXT_COLOR
                90, 91, 92, 93, 94, 95, 96, 97 -> currentTextColor = BRIGHT_ANSI_COLORS[parameter - 90]
                38 -> {
                    val consumed = applyExtendedForegroundColor(parameters, index)
                    if (consumed > index) {
                        index = consumed
                    }
                }
            }
            index++
        }
    }

    private fun applyExtendedForegroundColor(parameters: List<Int>, start: Int): Int {
        if (start + 2 >= parameters.size) {
            return start
        }

        return when (parameters[start + 1]) {
            5 -> {
                currentTextColor = colorFrom256Palette(parameters[start + 2])
                start + 2
            }
            2 -> {
                if (start + 4 >= parameters.size) {
                    start
                } else {
                    currentTextColor = Color.rgb(
                        parameters[start + 2].coerceIn(0, 255),
                        parameters[start + 3].coerceIn(0, 255),
                        parameters[start + 4].coerceIn(0, 255)
                    )
                    start + 4
                }
            }
            else -> start
        }
    }

    private fun colorFrom256Palette(color: Int): Int {
        val normalized = color.coerceIn(0, 255)
        if (normalized < 8) {
            return ANSI_COLORS[normalized]
        }
        if (normalized < 16) {
            return BRIGHT_ANSI_COLORS[normalized - 8]
        }
        if (normalized >= 232) {
            val level = 8 + (normalized - 232) * 10
            return Color.rgb(level, level, level)
        }

        val colorIndex = normalized - 16
        val red = COLOR_CUBE_LEVELS[colorIndex / 36]
        val green = COLOR_CUBE_LEVELS[(colorIndex / 6) % 6]
        val blue = COLOR_CUBE_LEVELS[colorIndex % 6]
        return Color.rgb(red, green, blue)
    }

    private fun resetTerminalStyle() {
        currentTextColor = DEFAULT_TEXT_COLOR
        currentTextStyle = Typeface.NORMAL
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

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 60_000
        private const val ESCAPE = '\u001B'
        private val DEFAULT_TEXT_COLOR = Color.rgb(183, 247, 200)
        private val ANSI_COLORS = intArrayOf(
            Color.rgb(75, 85, 99),
            Color.rgb(248, 113, 113),
            Color.rgb(74, 222, 128),
            Color.rgb(250, 204, 21),
            Color.rgb(96, 165, 250),
            Color.rgb(232, 121, 249),
            Color.rgb(34, 211, 238),
            Color.rgb(229, 231, 235)
        )
        private val BRIGHT_ANSI_COLORS = intArrayOf(
            Color.rgb(156, 163, 175),
            Color.rgb(252, 165, 165),
            Color.rgb(134, 239, 172),
            Color.rgb(253, 224, 71),
            Color.rgb(147, 197, 253),
            Color.rgb(240, 171, 252),
            Color.rgb(103, 232, 249),
            Color.rgb(249, 250, 251)
        )
        private val COLOR_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)
    }
}
