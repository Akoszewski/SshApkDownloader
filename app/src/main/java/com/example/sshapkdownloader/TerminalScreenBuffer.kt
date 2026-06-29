package com.example.sshapkdownloader

class TerminalScreenBuffer(
    private val maxRows: Int = 2_000
) {
    private val lines = mutableListOf(StringBuilder())
    private val pendingEscape = StringBuilder()
    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedCursorRow = 0
    private var savedCursorColumn = 0

    fun append(text: String): String {
        var index = 0

        if (pendingEscape.isNotEmpty()) {
            val pendingLength = pendingEscape.length
            pendingEscape.append(text)
            val consumed = consumeEscape(pendingEscape.toString(), 0)
            if (consumed == -1) {
                return render()
            }
            applyEscape(pendingEscape.toString(), 0, consumed)
            pendingEscape.clear()
            index = consumed - pendingLength
            if (index < 0) {
                index = 0
            }
        }

        while (index < text.length) {
            when (val char = text[index]) {
                ESCAPE -> {
                    val consumed = consumeEscape(text, index)
                    if (consumed == -1) {
                        pendingEscape.append(text.substring(index))
                        break
                    }
                    applyEscape(text, index, consumed)
                    index = consumed
                }
                '\r' -> {
                    cursorColumn = 0
                    index++
                }
                '\n' -> {
                    newLine()
                    index++
                }
                '\b' -> {
                    cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                    index++
                }
                '\t' -> {
                    repeat(TAB_WIDTH - cursorColumn % TAB_WIDTH) {
                        writeChar(' ')
                    }
                    index++
                }
                else -> {
                    if (!Character.isISOControl(char)) {
                        writeChar(char)
                    }
                    index++
                }
            }
        }

        trimRows()
        return render()
    }

    fun clear(): String {
        lines.clear()
        lines.add(StringBuilder())
        cursorRow = 0
        cursorColumn = 0
        savedCursorRow = 0
        savedCursorColumn = 0
        pendingEscape.clear()
        return render()
    }

    private fun writeChar(char: Char) {
        ensureCursorLine()
        val line = lines[cursorRow]
        while (line.length < cursorColumn) {
            line.append(' ')
        }
        if (cursorColumn == line.length) {
            line.append(char)
        } else {
            line.setCharAt(cursorColumn, char)
        }
        cursorColumn++
    }

    private fun newLine() {
        cursorRow++
        cursorColumn = 0
        ensureCursorLine()
    }

    private fun ensureCursorLine() {
        while (lines.size <= cursorRow) {
            lines.add(StringBuilder())
        }
    }

    private fun trimRows() {
        if (lines.size <= maxRows) {
            return
        }

        val removeCount = lines.size - maxRows
        repeat(removeCount) {
            lines.removeAt(0)
        }
        cursorRow = (cursorRow - removeCount).coerceAtLeast(0)
    }

    private fun render(): String {
        var lastNonEmptyLine = lines.indexOfLast { it.isNotEmpty() }
        if (lastNonEmptyLine < cursorRow) {
            lastNonEmptyLine = cursorRow
        }
        if (lastNonEmptyLine < 0) {
            return ""
        }
        return lines.take(lastNonEmptyLine + 1).joinToString("\n") { it.toString().trimEnd() }
    }

    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) {
            return -1
        }

        return when (text[start + 1]) {
            '[' -> consumeCsi(text, start + 2)
            ']' -> consumeOsc(text, start + 2)
            '(', ')' -> if (start + 2 < text.length) start + 3 else -1
            '7', '8', 'c' -> start + 2
            else -> start + 2
        }
    }

    private fun consumeCsi(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] in '@'..'~') {
                return index + 1
            }
            index++
        }
        return -1
    }

    private fun consumeOsc(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == '\u0007') {
                return index + 1
            }
            if (text[index] == ESCAPE && index + 1 < text.length && text[index + 1] == '\\') {
                return index + 2
            }
            index++
        }
        return -1
    }

    private fun applyEscape(text: String, start: Int, end: Int) {
        if (end == start + 2) {
            when (text[start + 1]) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'c' -> clear()
            }
            return
        }

        if (end <= start + 2 || text[start + 1] != '[') {
            return
        }

        val finalChar = text[end - 1]
        val body = text.substring(start + 2, end - 1)
        if (body.startsWith("?") && (finalChar == 'h' || finalChar == 'l')) {
            applyPrivateMode(body)
            return
        }

        val params = parseCsiParameters(body)
        when (finalChar) {
            'A' -> cursorRow = (cursorRow - param(params, 0, 1)).coerceAtLeast(0)
            'B' -> {
                cursorRow += param(params, 0, 1)
                ensureCursorLine()
            }
            'C' -> cursorColumn += param(params, 0, 1)
            'D' -> cursorColumn = (cursorColumn - param(params, 0, 1)).coerceAtLeast(0)
            'G' -> cursorColumn = (param(params, 0, 1) - 1).coerceAtLeast(0)
            'H', 'f' -> moveCursor(params)
            'J' -> eraseDisplay(param(params, 0, 0))
            'K' -> eraseLine(param(params, 0, 0))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'd' -> {
                cursorRow = (param(params, 0, 1) - 1).coerceAtLeast(0)
                ensureCursorLine()
            }
        }
    }

    private fun applyPrivateMode(body: String) {
        val modes = body.drop(1)
            .split(';')
            .mapNotNull { it.toIntOrNull() }
        if (1049 in modes || 47 in modes || 1047 in modes) {
            clear()
        }
    }

    private fun parseCsiParameters(body: String): List<Int?> {
        if (body.isBlank()) {
            return emptyList()
        }
        return body.split(';').map { it.toIntOrNull() }
    }

    private fun param(params: List<Int?>, index: Int, defaultValue: Int): Int {
        return params.getOrNull(index)?.takeIf { it > 0 } ?: defaultValue
    }

    private fun moveCursor(params: List<Int?>) {
        cursorRow = (param(params, 0, 1) - 1).coerceAtLeast(0)
        cursorColumn = (param(params, 1, 1) - 1).coerceAtLeast(0)
        ensureCursorLine()
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorColumn = cursorColumn
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorColumn = savedCursorColumn
        ensureCursorLine()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (row in cursorRow + 1 until lines.size) {
                    lines[row].clear()
                }
            }
            1 -> {
                for (row in 0 until cursorRow) {
                    lines[row].clear()
                }
                eraseLine(1)
            }
            2, 3 -> clear()
        }
    }

    private fun eraseLine(mode: Int) {
        ensureCursorLine()
        val line = lines[cursorRow]
        when (mode) {
            0 -> {
                if (cursorColumn < line.length) {
                    line.delete(cursorColumn, line.length)
                }
            }
            1 -> {
                val end = (cursorColumn + 1).coerceAtMost(line.length)
                for (index in 0 until end) {
                    line.setCharAt(index, ' ')
                }
            }
            2 -> line.clear()
        }
    }

    companion object {
        private const val ESCAPE = '\u001B'
        private const val TAB_WIDTH = 4
    }
}
