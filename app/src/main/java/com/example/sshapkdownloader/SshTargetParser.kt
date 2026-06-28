package com.example.sshapkdownloader

object SshTargetParser {
    fun parse(address: String): SshTarget {
        val userSplit = address.trim().split("@", limit = 2)
        require(userSplit.size == 2 && userSplit[0].isNotBlank()) {
            "Address must be in user@host or user@host:port format"
        }

        val username = userSplit[0].trim()
        val hostAndPort = userSplit[1]
        val portSplit = hostAndPort.split(":", limit = 2)
        val host = portSplit[0].trim()
        val portText = portSplit.getOrNull(1)
        val port = if (portText == null || portText.isBlank()) {
            22
        } else {
            portText.toIntOrNull() ?: error("Port must be a number")
        }

        require(host.isNotBlank()) { "Host is required" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        return SshTarget(username = username, host = host, port = port)
    }
}
