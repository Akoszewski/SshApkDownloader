package com.example.sshapkdownloader

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

object TerminalSessionManager {
    interface Listener {
        fun onTerminalOutputChanged(output: CharSequence)
        fun onTerminalEnabledChanged(enabled: Boolean)
        fun onTerminalDisconnected()
        fun onTerminalConnectionUnavailable()
    }

    private val terminalScreenBuffer = TerminalScreenBuffer { bytes ->
        writeToShell(bytes)
    }
    private val writerLock = Any()
    private val outputLock = Any()
    private val pendingOutput = ByteArrayOutputStream()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disconnectStarted = AtomicBoolean(false)
    private val flushPendingOutputRunnable = Runnable {
        flushPendingOutput()
    }

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var commandOutput: OutputStream? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var outputRenderScheduled = false

    @Volatile
    private var keepAliveThread: Thread? = null

    @Volatile
    private var terminalEnabled = false

    @Volatile
    private var terminalColumns = TerminalScreenBuffer.DEFAULT_COLUMNS

    @Volatile
    private var terminalRows = TerminalScreenBuffer.DEFAULT_ROWS

    @Volatile
    private var connecting = false

    private var terminalWakeLock: PowerManager.WakeLock? = null
    private var terminalWifiLock: WifiManager.WifiLock? = null

    fun attachListener(listener: Listener) {
        this.listener = listener
        mainHandler.post {
            listener.onTerminalOutputChanged(terminalScreenBuffer.renderStyled())
            listener.onTerminalEnabledChanged(terminalEnabled)
        }
    }

    fun detachListener(listener: Listener) {
        if (this.listener === listener) {
            this.listener = null
        }
    }

    fun connect(context: Context, address: String, privateKey: String) {
        val appContext = context.applicationContext
        applicationContext = appContext

        if (isSessionActive() || connecting) {
            notifyOutputChanged()
            notifyTerminalEnabled()
            return
        }

        setTerminalEnabled(false)
        appendOutput(appContext.getString(R.string.terminal_connecting, address))
        stopRequested = false
        connecting = true
        disconnectStarted.set(false)
        TerminalForegroundService.start(appContext)
        acquireTerminalLocks(appContext)

        Thread {
            runCatching {
                val sshSession = SshSessionFactory.create(SshTargetParser.parse(address), privateKey)
                session = sshSession
                sshSession.connect(15_000)
                if (stopRequested) {
                    disconnectShell()
                    return@Thread
                }

                val shell = sshSession.openChannel("shell") as ChannelShell
                shell.setPty(true)
                shell.setPtyType("xterm-256color")
                shell.setPtySize(terminalColumns, terminalRows, 0, 0)
                shell.setEnv("TERM", "xterm-256color")
                val remoteInput = shell.inputStream
                val remoteOutput = shell.outputStream
                channel = shell
                commandOutput = remoteOutput

                shell.connect(15_000)
                if (stopRequested) {
                    disconnectShell()
                    return@Thread
                }
                connecting = false
                startKeepAliveLoop(sshSession)
                appendOutput(appContext.getString(R.string.terminal_connected))
                setTerminalEnabled(true)
                readShellOutput(remoteInput)
            }.onFailure { error ->
                connecting = false
                if (!stopRequested) {
                    appendOutput(appContext.getString(R.string.terminal_connection_error, error.displayMessage()))
                    setTerminalEnabled(false)
                }
                disconnectShellAsync()
            }
        }.apply {
            name = "ssh-terminal-connect"
            isDaemon = true
            start()
        }
    }

    fun disconnectByUser() {
        stopRequested = true
        appendOutput(contextString(R.string.terminal_disconnected))
        setTerminalEnabled(false)
        disconnectShellAsync()
    }

    fun disconnectBecauseTaskRemoved() {
        stopRequested = true
        setTerminalEnabled(false)
        disconnectShell(stopForegroundService = false)
    }

    fun sendCommand(command: String) {
        if (command.isBlank()) {
            writeToShell(ENTER_KEY_BYTES)
            return
        }

        writeCommandToShell(command)
    }

    fun sendBytes(bytes: ByteArray) {
        writeToShell(bytes)
    }

    fun sendInputEditingKey(currentInput: String, keyBytes: ByteArray) {
        writeToShell(buildInputEditingBytes(currentInput, keyBytes))
    }

    fun sendPrimedInputCommand(command: String) {
        writeToShell(buildInputEditingBytes(command, ENTER_KEY_BYTES))
    }

    fun currentInputPromptColumn(): Int {
        return terminalScreenBuffer.currentCursorColumn()
    }

    fun currentInputAfterPromptColumn(promptColumn: Int): String {
        return terminalScreenBuffer.currentLineTextAfterColumn(promptColumn)
    }

    fun clearOutput() {
        mainHandler.post {
            terminalScreenBuffer.clear()
            notifyOutputChanged()
        }
    }

    fun resizeTerminal(columns: Int, rows: Int) {
        val boundedColumns = columns.coerceIn(MIN_TERMINAL_COLUMNS, MAX_TERMINAL_COLUMNS)
        val boundedRows = rows.coerceIn(MIN_TERMINAL_ROWS, MAX_TERMINAL_ROWS)
        if (boundedColumns == terminalColumns && boundedRows == terminalRows) {
            return
        }

        terminalColumns = boundedColumns
        terminalRows = boundedRows
        mainHandler.post {
            terminalScreenBuffer.resize(boundedColumns, boundedRows)
            notifyOutputChanged()
        }
        channel?.takeIf { it.isConnected && !it.isClosed }?.setPtySize(boundedColumns, boundedRows, 0, 0)
    }

    private fun readShellOutput(remoteInput: InputStream) {
        val buffer = ByteArray(4096)
        while (!stopRequested) {
            val bytesRead = remoteInput.read(buffer)
            if (bytesRead < 0) {
                break
            }
            val bytes = buffer.copyOf(bytesRead)
            appendOutput(bytes)
        }

        if (!stopRequested) {
            appendOutput(contextString(R.string.terminal_remote_shell_closed))
            setTerminalEnabled(false)
            disconnectShellAsync()
        }
    }

    private fun startKeepAliveLoop(sshSession: Session) {
        keepAliveThread = Thread {
            while (!stopRequested && sshSession.isConnected) {
                try {
                    Thread.sleep(TERMINAL_KEEP_ALIVE_INTERVAL_MS)
                    if (!stopRequested && sshSession.isConnected) {
                        sshSession.sendKeepAliveMsg()
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (error: Throwable) {
                    if (!stopRequested) {
                        appendOutput(contextString(R.string.terminal_connection_error, error.displayMessage()))
                        setTerminalEnabled(false)
                        disconnectShellAsync()
                    }
                    return@Thread
                }
            }
        }.apply {
            name = "ssh-terminal-keepalive"
            isDaemon = true
            start()
        }
    }

    private fun writeCommandToShell(command: String) {
        val output = commandOutput
        if (output == null || channel?.isClosed == true) {
            notifyConnectionUnavailable()
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
                appendOutput(contextString(R.string.terminal_write_error, error.displayMessage()))
                setTerminalEnabled(false)
            }
        }.apply {
            name = "ssh-terminal-write-command"
            isDaemon = true
            start()
        }
    }

    private fun writeToShell(bytes: ByteArray) {
        val output = commandOutput
        if (output == null || channel?.isClosed == true) {
            notifyConnectionUnavailable()
            return
        }

        Thread {
            runCatching {
                synchronized(writerLock) {
                    output.write(bytes)
                    output.flush()
                }
            }.onFailure { error ->
                appendOutput(contextString(R.string.terminal_write_error, error.displayMessage()))
                setTerminalEnabled(false)
            }
        }.apply {
            name = "ssh-terminal-write-bytes"
            isDaemon = true
            start()
        }
    }

    private fun disconnectShell() {
        disconnectShell(stopForegroundService = true)
    }

    private fun disconnectShell(stopForegroundService: Boolean) {
        if (!disconnectStarted.compareAndSet(false, true)) {
            return
        }
        connecting = false
        val currentThread = Thread.currentThread()
        keepAliveThread
            ?.takeIf { it != currentThread }
            ?.interrupt()
        keepAliveThread = null
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
        releaseTerminalLocks()
        if (stopForegroundService) {
            applicationContext?.let { TerminalForegroundService.stop(it) }
        }
        notifyDisconnected()
    }

    private fun disconnectShellAsync() {
        Thread {
            disconnectShell()
        }.apply {
            name = "ssh-terminal-disconnect"
            isDaemon = true
            start()
        }
    }

    private fun isSessionActive(): Boolean {
        return session?.isConnected == true && channel?.isConnected == true
    }

    private fun acquireTerminalLocks(context: Context) {
        if (terminalWakeLock?.isHeld == true && terminalWifiLock?.isHeld == true) {
            return
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        terminalWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:TerminalSshSession"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        terminalWifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "${context.packageName}:TerminalSshWifi"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseTerminalLocks() {
        terminalWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
        terminalWakeLock = null

        terminalWifiLock?.let { wifiLock ->
            if (wifiLock.isHeld) {
                wifiLock.release()
            }
        }
        terminalWifiLock = null
    }

    private fun appendOutput(text: String) {
        mainHandler.post {
            terminalScreenBuffer.append(text)
            notifyOutputChanged()
        }
    }

    private fun appendOutput(bytes: ByteArray) {
        synchronized(outputLock) {
            pendingOutput.write(bytes)
            if (outputRenderScheduled) {
                return
            }
            outputRenderScheduled = true
        }
        mainHandler.postDelayed(flushPendingOutputRunnable, TERMINAL_RENDER_DELAY_MS)
    }

    private fun flushPendingOutput() {
        val bytes = synchronized(outputLock) {
            outputRenderScheduled = false
            if (pendingOutput.size() == 0) {
                return
            }
            pendingOutput.toByteArray().also {
                pendingOutput.reset()
            }
        }
        terminalScreenBuffer.append(bytes, bytes.size)
        notifyOutputChanged()
    }

    private fun setTerminalEnabled(enabled: Boolean) {
        terminalEnabled = enabled
        notifyTerminalEnabled()
    }

    private fun notifyOutputChanged() {
        val output = terminalScreenBuffer.renderStyled()
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalOutputChanged(output)
                }
            }
        }
    }

    private fun notifyTerminalEnabled() {
        val enabled = terminalEnabled
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalEnabledChanged(enabled)
                }
            }
        }
    }

    private fun notifyDisconnected() {
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalDisconnected()
                }
            }
        }
    }

    private fun notifyConnectionUnavailable() {
        listener?.let { activeListener ->
            mainHandler.post {
                if (listener === activeListener) {
                    activeListener.onTerminalConnectionUnavailable()
                }
            }
        }
    }

    private fun contextString(resId: Int, vararg args: Any): String {
        val context = applicationContext ?: return ""
        return context.getString(resId, *args)
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    private fun buildInputEditingBytes(currentInput: String, keyBytes: ByteArray): ByteArray {
        return CONTROL_U_BYTES + currentInput.toByteArray(Charsets.UTF_8) + keyBytes
    }

    private const val MIN_TERMINAL_COLUMNS = 20
    private const val MAX_TERMINAL_COLUMNS = 200
    private const val MIN_TERMINAL_ROWS = 8
    private const val MAX_TERMINAL_ROWS = 80
    private const val ENTER_KEY = "\r"
    private const val ENTER_KEY_DELAY_MS = 60L
    private const val TERMINAL_RENDER_DELAY_MS = 50L
    private const val TERMINAL_KEEP_ALIVE_INTERVAL_MS = 10_000L
    private val ENTER_KEY_BYTES = ENTER_KEY.toByteArray(Charsets.UTF_8)
    private val CONTROL_U_BYTES = byteArrayOf(0x15)
}
