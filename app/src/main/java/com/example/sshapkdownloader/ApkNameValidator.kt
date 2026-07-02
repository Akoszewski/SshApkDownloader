package com.example.sshapkdownloader

object ApkNameValidator {
    fun requireValid(apkName: String) {
        RemoteFileNameValidator.requireValid(apkName)
        require(apkName.endsWith(".apk")) { "Only APK files can be downloaded" }
    }
}
