package com.example.sshapkdownloader

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle

class TerminalScreenBuffer(
    private val columns: Int = DEFAULT_COLUMNS,
    private val rows: Int = DEFAULT_ROWS,
    private val scrollbackRows: Int = DEFAULT_SCROLLBACK_ROWS,
    private val writeToRemote: (ByteArray) -> Unit = {}
) {
    private val client = NoOpTerminalSessionClient()
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            writeToRemote(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private val emulator = TerminalEmulator(output, columns, rows, scrollbackRows, client)

    fun append(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return append(bytes, bytes.size)
    }

    fun append(data: ByteArray, byteCount: Int): String {
        emulator.append(data, byteCount)
        return renderPlainText()
    }

    fun clear(): String {
        emulator.reset()
        return renderPlainText()
    }

    fun resize(columns: Int, rows: Int) {
        emulator.resize(columns, rows)
    }

    fun renderStyled(): CharSequence {
        val builder = SpannableStringBuilder()
        visibleRows().forEachIndexed { index, (row, text) ->
            if (index > 0) {
                builder.append('\n')
            }
            val rowStart = builder.length
            builder.append(text)
            applyRowStyles(builder, rowStart, row, text)
        }
        return builder
    }

    fun currentLineText(): String {
        return rowText(emulator.cursorRow)
    }

    fun currentLineTextAfterColumn(column: Int): String {
        val line = currentLineText()
        return line.drop(column.coerceIn(0, line.length)).trimEnd()
    }

    private fun renderPlainText(): String {
        return visibleRows().joinToString("\n") { it.second }
    }

    private fun visibleRows(): List<Pair<Int, String>> {
        val screen = emulator.screen
        val rows = mutableListOf<Pair<Int, String>>()
        for (row in -screen.activeTranscriptRows until emulator.mRows) {
            val text = rowText(row)
            if (text.isNotEmpty() || row in 0..emulator.cursorRow) {
                rows.add(row to text)
            }
        }
        return rows.dropLastWhile { it.second.isEmpty() }
    }

    private fun rowText(row: Int): String {
        return emulator.screen.getSelectedText(
            0,
            row,
            emulator.mColumns,
            row,
            false,
            false
        ).trimEnd()
    }

    private fun applyRowStyles(builder: SpannableStringBuilder, rowStart: Int, row: Int, text: String) {
        val screen = emulator.screen
        val colors = emulator.mColors.mCurrentColors

        text.forEachIndexed { column, _ ->
            val style = screen.getStyleAt(row, column)
            val start = rowStart + column
            val end = start + 1
            val foreground = resolveColor(TextStyle.decodeForeColor(style), colors)
            val background = resolveColor(TextStyle.decodeBackColor(style), colors)
            val effects = TextStyle.decodeEffect(style)

            builder.setSpan(
                ForegroundColorSpan(foreground),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                BackgroundColorSpan(background),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (effects and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun resolveColor(color: Int, colors: IntArray): Int {
        return if (color in colors.indices) colors[color] else color
    }

    private class NoOpTerminalSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession?) = Unit
        override fun onTitleChanged(updatedSession: TerminalSession?) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    companion object {
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_ROWS = 40
        const val DEFAULT_SCROLLBACK_ROWS = 10_000
    }
}
