package com.example.sshapkdownloader

class TerminalOutputSanitizer {
    private val pendingEscape = StringBuilder()

    fun clean(text: String): String {
        val output = StringBuilder()
        var index = 0

        if (pendingEscape.isNotEmpty()) {
            val pendingLength = pendingEscape.length
            pendingEscape.append(text)
            val pending = pendingEscape.toString()
            val consumed = consumeEscapeSequence(pending, 0)
            if (consumed == -1) {
                return ""
            }
            pendingEscape.clear()
            index = consumed - pendingLength
            if (index < 0) {
                index = 0
            }
        }

        while (index < text.length) {
            when (val char = text[index]) {
                ESCAPE -> {
                    val consumed = consumeEscapeSequence(text, index)
                    if (consumed == -1) {
                        pendingEscape.append(text.substring(index))
                        break
                    }
                    index = consumed
                }
                '\r' -> index++
                '\b' -> {
                    if (output.isNotEmpty()) {
                        output.deleteAt(output.length - 1)
                    } else {
                        output.append(char)
                    }
                    index++
                }
                else -> {
                    if (!Character.isISOControl(char) || char == '\n' || char == '\t') {
                        output.append(char)
                    }
                    index++
                }
            }
        }

        return output.toString()
    }

    private fun consumeEscapeSequence(text: String, start: Int): Int {
        if (start + 1 >= text.length) {
            return -1
        }

        return when (text[start + 1]) {
            '[' -> consumeCsiSequence(text, start + 2)
            ']' -> consumeOscSequence(text, start + 2)
            '(', ')' -> if (start + 2 < text.length) start + 3 else -1
            else -> start + 2
        }
    }

    private fun consumeCsiSequence(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (char in '@'..'~') {
                return index + 1
            }
            index++
        }
        return -1
    }

    private fun consumeOscSequence(text: String, start: Int): Int {
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

    companion object {
        private const val ESCAPE = '\u001B'
    }
}
