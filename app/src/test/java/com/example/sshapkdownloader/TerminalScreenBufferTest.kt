package com.example.sshapkdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalScreenBufferTest {
    @Test
    fun carriageReturnOverwritesCurrentLine() {
        val buffer = TerminalScreenBuffer()

        assertEquals("done", buffer.append("loading\rdone\u001B[K"))
    }

    @Test
    fun eraseLineFromCursorRemovesOldContent() {
        val buffer = TerminalScreenBuffer()

        assertEquals("short", buffer.append("long output\rshort\u001B[K"))
    }

    @Test
    fun eraseDisplayClearsTheScreen() {
        val buffer = TerminalScreenBuffer()

        assertEquals("fresh", buffer.append("old\r\nscreen\u001B[2J\u001B[Hfresh"))
    }

    @Test
    fun cursorMovementCanRewritePreviousLines() {
        val buffer = TerminalScreenBuffer()

        assertEquals("first updated\nsecond", buffer.append("first\r\nsecond\u001B[1A\u001B[6G updated\u001B[K"))
    }

    @Test
    fun handlesEscapeSequenceSplitAcrossChunks() {
        val buffer = TerminalScreenBuffer()

        assertEquals("shortoutput", buffer.append("long output\rshort\u001B["))
        assertEquals("short", buffer.append("K"))
    }

    @Test
    fun restoresSavedCursorPosition() {
        val buffer = TerminalScreenBuffer()

        assertEquals("abcX", buffer.append("abc\u001B7de\u001B8X\u001B[K"))
    }

    @Test
    fun stripsForegroundColorSequencesFromPlainText() {
        val buffer = TerminalScreenBuffer()
        val output = buffer.append("\u001B[31mred\u001B[0m normal")

        assertEquals("red normal", output)
    }

    @Test
    fun stripsBoldSequencesFromPlainText() {
        val buffer = TerminalScreenBuffer()
        val output = buffer.append("\u001B[1mbold\u001B[22m normal")

        assertEquals("bold normal", output)
    }

    @Test
    fun returnsCurrentLineText() {
        val buffer = TerminalScreenBuffer()

        buffer.append("first\r\nprompt> command")

        assertEquals("prompt> command", buffer.currentLineText())
    }

    @Test
    fun returnsCurrentLineTextAfterColumn() {
        val buffer = TerminalScreenBuffer()

        buffer.append("prompt> command")

        assertEquals("command", buffer.currentLineTextAfterColumn("prompt> ".length))
    }

    @Test
    fun rendersScrollbackRowsBeforeVisibleScreen() {
        val buffer = TerminalScreenBuffer(columns = 20, rows = 3, scrollbackRows = 10)

        val output = buffer.append("one\r\ntwo\r\nthree\r\nfour\r\nfive")

        assertEquals("one\ntwo\nthree\nfour\nfive", output)
    }
}
