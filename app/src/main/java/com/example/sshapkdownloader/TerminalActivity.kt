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
    private val terminalScreenBuffer = TerminalScreenBuffer { bytes ->
        writeToShell(bytes)
    }
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
            terminalScreenBuffer.clear()
            renderTerminalOutput()
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
                shell.setPtySize(TERMINAL_COLUMNS, TERMINAL_ROWS, 0, 0)
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
            val bytes = buffer.copyOf(bytesRead)
            runOnUiThread {
                appendOutput(bytes)
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
            writeToShell(ENTER_KEY_BYTES)
            return
        }

        writeCommandToShell(command)
        commandEditText.setText("")
    }

    private fun writeCommandToShell(command: String) {
        val output = commandOutput
        if (output == null || channel?.isClosed == true) {
            Toast.makeText(this, getString(R.string.message_terminal_not_connected), Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            runCatching {
                synchronized(writerLock) {
                    output.write(command.toByteArray(Charsets.UTF_8))
                    output.flush()
                    Thread.sleep(ENTER_KEY_DELAY_MS)
                    output.write(ENTER_KEY_BYTES)
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
        terminalScreenBuffer.append(text)
        renderTerminalOutput()
    }

    private fun appendOutput(bytes: ByteArray) {
        terminalScreenBuffer.append(bytes, bytes.size)
        renderTerminalOutput()
    }

    private fun renderTerminalOutput() {
        outputTextView.text = terminalScreenBuffer.renderStyled()
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
        private const val TERMINAL_COLUMNS = TerminalScreenBuffer.DEFAULT_COLUMNS
        private const val TERMINAL_ROWS = TerminalScreenBuffer.DEFAULT_ROWS
        private const val ENTER_KEY = "\r"
        private const val ENTER_KEY_DELAY_MS = 60L
        private val ENTER_KEY_BYTES = ENTER_KEY.toByteArray(Charsets.UTF_8)
    }
}
