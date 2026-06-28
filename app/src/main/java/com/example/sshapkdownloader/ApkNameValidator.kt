package com.example.sshapkdownloader

object ApkNameValidator {
    fun requireValid(apkName: String) {
        require(apkName.endsWith(".apk")) { "Only APK files can be downloaded" }
        require(!apkName.contains("/") && !apkName.contains("\\")) { "Invalid APK filename" }
    }
}
