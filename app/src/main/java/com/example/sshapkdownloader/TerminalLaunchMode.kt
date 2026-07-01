package com.example.sshapkdownloader

enum class TerminalLaunchMode {
    ResumeServerSession,
    NewShell;

    companion object {
        const val EXTRA_NAME = "com.example.sshapkdownloader.extra.TERMINAL_LAUNCH_MODE"

        fun fromIntentValue(value: String?): TerminalLaunchMode {
            return entries.firstOrNull { it.name == value } ?: ResumeServerSession
        }
    }
}
