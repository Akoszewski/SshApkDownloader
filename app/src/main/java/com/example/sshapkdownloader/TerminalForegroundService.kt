package com.example.sshapkdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class TerminalForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val terminalIntent = Intent(this, TerminalActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            terminalIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return notificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.notification_terminal_active))
            .setContentText(getString(R.string.notification_terminal_active_detail))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_terminal),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val ACTION_START = "com.example.sshapkdownloader.action.START_TERMINAL_FOREGROUND"
        private const val ACTION_STOP = "com.example.sshapkdownloader.action.STOP_TERMINAL_FOREGROUND"
        private const val CHANNEL_ID = "ssh_terminal"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, TerminalForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TerminalForegroundService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
