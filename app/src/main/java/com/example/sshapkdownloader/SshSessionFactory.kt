package com.example.sshapkdownloader

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

object SshSessionFactory {
    fun create(target: SshTarget, privateKey: String): Session {
        val jsch = JSch()
        jsch.addIdentity(
            "ssh-apk-downloader-key",
            privateKey.toByteArray(Charsets.UTF_8),
            null,
            null
        )

        return jsch.getSession(target.username, target.host, target.port).apply {
            setConfig("StrictHostKeyChecking", "no")
            timeout = 15_000
        }
    }
}
