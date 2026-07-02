package com.example.sshapkdownloader

fun String.toShellPathExpression(): String {
    val path = trim().trimTrailingSlashes()
    return when {
        path == "~" -> "\$HOME"
        path.startsWith("~/") -> "\$HOME/${path.removePrefix("~/").shellQuote()}"
        else -> path.shellQuote()
    }
}

fun String.toSftpDirectory(): String {
    val path = trim().trimTrailingSlashes()
    return when {
        path == "~" -> "."
        path.startsWith("~/") -> path.removePrefix("~/")
        else -> path
    }
}

private fun String.shellQuote(): String {
    return "'${replace("'", "'\"'\"'")}'"
}

private fun String.trimTrailingSlashes(): String {
    return if (length > 1) trimEnd('/').ifEmpty { "/" } else this
}
