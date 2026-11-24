package com.sleepy.app.sleepy.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.sleepy.app.sleepy.MainActivity

class AlarmPlaybackService : Service() {
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val stopPlayback = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val spec = AlarmSpec.fromJson(intent?.getStringExtra(SleepAlarmReceiver.EXTRA_SPEC))
        if (spec == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(spec)
        startPlayback(spec)
        handler.removeCallbacks(stopPlayback)
        handler.postDelayed(stopPlayback, AUTO_STOP_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopPlayback)
        ringtone?.stop()
        ringtone = null
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun startPlayback(spec: AlarmSpec) {
        acquireWakeLock()
        val audioManager = getSystemService(AudioManager::class.java)
        audioManager?.let { manager ->
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * spec.volume).toInt().coerceIn(0, max)
            try {
                manager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            } catch (_: SecurityException) {
            }
        }

        val soundType = if (spec.sound == "notification") {
            RingtoneManager.TYPE_NOTIFICATION
        } else {
            RingtoneManager.TYPE_ALARM
        }
        val alarmUri = RingtoneManager.getDefaultUri(soundType)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (alarmUri == null) return

        ringtone = try {
            RingtoneManager.getRingtone(this, alarmUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startForegroundCompat(spec: AlarmSpec) {
        val notification = notification(spec)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(spec: AlarmSpec): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AlarmPlaybackService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(spec.label)
            .setContentText("Sleepy · нажмите, чтобы открыть приложение")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Остановить",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Будильник Sleepy",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Срабатывание будильника"
            setSound(null, null)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "sleepy:alarm",
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    companion object {
        const val ACTION_START = "com.sleepy.app.sleepy.alarm.PLAY"
        const val ACTION_STOP = "com.sleepy.app.sleepy.alarm.STOP"

        private const val CHANNEL_ID = "sleepy_alarm_playback"
        private const val NOTIFICATION_ID = 2001
        private const val AUTO_STOP_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 1000L
    }
}
