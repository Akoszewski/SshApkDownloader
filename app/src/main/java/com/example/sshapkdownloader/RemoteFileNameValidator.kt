package com.example.sshapkdownloader

object RemoteFileNameValidator {
    fun requireValid(fileName: String) {
        require(fileName.isNotBlank()) { "Missing filename" }
        require(fileName != "." && fileName != "..") { "Invalid filename" }
        require(!fileName.contains("/") && !fileName.contains("\\")) { "Invalid filename" }
    }
}
