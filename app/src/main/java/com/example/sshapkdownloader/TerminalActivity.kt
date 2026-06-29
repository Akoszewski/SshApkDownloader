package com.example.sshapkdownloader

import android.app.Activity
import android.content.Context
import android.os.Bundle
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
        setContentView(R.layout.activity_terminal)
        outputTextView = findViewById(R.id.outputTextView)
        outputScrollView = findViewById(R.id.outputScrollView)
        commandEditText = findViewById(R.id.commandEditText)
        sendButton = findViewById(R.id.sendButton)
        disconnectButton = findViewById(R.id.disconnectButton)
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
            outputTextView.text = ""
        }
        connectShell()
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

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 60_000
    }
}
