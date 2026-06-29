package com.example.sshapkdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputSanitizerTest {
    @Test
    fun keepsAnsiColorSequences() {
        val sanitizer = TerminalOutputSanitizer()

        assertEquals(
            "\u001B[01;32mfile.apk\u001B[0m\n",
            sanitizer.clean("\u001B[01;32mfile.apk\u001B[0m\r\n")
        )
    }

    @Test
    fun keepsAnsiColorSequencesSplitAcrossChunks() {
        val sanitizer = TerminalOutputSanitizer()

        assertEquals("", sanitizer.clean("\u001B[01;"))
        assertEquals("\u001B[01;32mfile.apk", sanitizer.clean("32mfile.apk"))
    }

    @Test
    fun stripsBracketedPasteSequencesSplitAcrossChunks() {
        val sanitizer = TerminalOutputSanitizer()

        assertEquals("", sanitizer.clean("\u001B[?200"))
        assertEquals("prompt$ ", sanitizer.clean("4hprompt$ "))
        assertEquals("", sanitizer.clean("\u001B[?2004l"))
    }

    @Test
    fun stripsOscWindowTitleSequences() {
        val sanitizer = TerminalOutputSanitizer()

        assertEquals(
            "output\n",
            sanitizer.clean("\u001B]0;user@host:~\u0007output\n")
        )
    }

    @Test
    fun keepsTabsAndNewlinesButRemovesOtherControlCharacters() {
        val sanitizer = TerminalOutputSanitizer()

        assertEquals("a\tb\nc", sanitizer.clean("a\tb\r\n\u0000c"))
    }
}
