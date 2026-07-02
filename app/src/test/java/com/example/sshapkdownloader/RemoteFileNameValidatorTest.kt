package com.example.sshapkdownloader

import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteFileNameValidatorTest {
    @Test
    fun requireValidAllowsNonApkFilename() {
        RemoteFileNameValidator.requireValid("notes.txt")
    }

    @Test
    fun requireValidRejectsBlankFilename() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileNameValidator.requireValid(" ")
        }
    }

    @Test
    fun requireValidRejectsPathTraversalWithSlash() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileNameValidator.requireValid("../notes.txt")
        }
    }

    @Test
    fun requireValidRejectsPathTraversalWithBackslash() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteFileNameValidator.requireValid("folder\\notes.txt")
        }
    }
}
