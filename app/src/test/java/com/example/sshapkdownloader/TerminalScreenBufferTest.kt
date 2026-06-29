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

        assertEquals("fresh", buffer.append("old\nscreen\u001B[2Jfresh"))
    }

    @Test
    fun cursorMovementCanRewritePreviousLines() {
        val buffer = TerminalScreenBuffer()

        assertEquals("first updated\nsecond", buffer.append("first\nsecond\u001B[1A\u001B[6G updated\u001B[K"))
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
}
