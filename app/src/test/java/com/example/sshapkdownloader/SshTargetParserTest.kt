package com.example.sshapkdownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshTargetParserTest {
    @Test
    fun parseUsesDefaultSshPortWhenPortIsMissing() {
        val target = SshTargetParser.parse("alice@example.com")

        assertEquals("alice", target.username)
        assertEquals("example.com", target.host)
        assertEquals(22, target.port)
    }

    @Test
    fun parseUsesExplicitPortWhenProvided() {
        val target = SshTargetParser.parse("alice@example.com:2222")

        assertEquals("alice", target.username)
        assertEquals("example.com", target.host)
        assertEquals(2222, target.port)
    }

    @Test
    fun parseTrimsUsernameAndHost() {
        val target = SshTargetParser.parse("  alice @ example.com :22 ")

        assertEquals("alice", target.username)
        assertEquals("example.com", target.host)
        assertEquals(22, target.port)
    }

    @Test
    fun parseRejectsAddressWithoutUsername() {
        assertThrows(IllegalArgumentException::class.java) {
            SshTargetParser.parse("example.com")
        }
    }

    @Test
    fun parseRejectsBlankHost() {
        assertThrows(IllegalArgumentException::class.java) {
            SshTargetParser.parse("alice@")
        }
    }

    @Test
    fun parseRejectsNonNumericPort() {
        assertThrows(IllegalStateException::class.java) {
            SshTargetParser.parse("alice@example.com:ssh")
        }
    }

    @Test
    fun parseRejectsOutOfRangePort() {
        assertThrows(IllegalArgumentException::class.java) {
            SshTargetParser.parse("alice@example.com:70000")
        }
    }
}
