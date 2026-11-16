package com.sleepy.app.sleepy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.sleepy.app.sleepy.audio.AudioCaptureSession
import com.sleepy.app.sleepy.audio.AudioRecordSource
import com.sleepy.app.sleepy.audio.RecordingConfig
import com.sleepy.app.sleepy.audio.RecordingSessionController
import com.sleepy.app.sleepy.audio.RecordingStatusStore
import java.io.File

/**
 * Foreground-служба мониторинга сна.
 *
 * Служба отвечает только за Android-жизненный цикл: foreground-уведомление,
 * WakeLock и передачу команд в [AudioCaptureSession]. Сама она не читает
 * микрофон и не пишет файлы — этим занимается сессия, которая сохраняет
 * отдельные WAV-фрагменты на обнаруженные звуковые события вместо одной
 * непрерывной ночной записи.
 */
class SleepRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.sleepy.app.sleepy.START_RECORDING"
        const val ACTION_STOP = "com.sleepy.app.sleepy.STOP_RECORDING"

        private const val CHANNEL_ID = "sleepy_recording_channel"
        private const val NOTIFICATION_ID = 1001
        const val RECORDINGS_DIRECTORY = "recordings"
        private const val STOP_WATCHDOG_MS = 15_000L
    }

    private val config = RecordingConfig.DEFAULT

    private lateinit var statusStore: RecordingStatusStore

    private lateinit var controller: RecordingSessionController<AudioCaptureSession>

    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Страховка от зависшего потока захвата: служба не может оставаться
     * foreground вечно, если остановка не завершилась. Пока поток жив,
     * контроллер не освобождает ресурсы и не разрешает новый запуск.
     */
    private val stopWatchdog = Runnable { handleWatchdog() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        statusStore = RecordingStatusStore(this)

        controller = RecordingSessionController(
            status = statusStore,
            createSession = { onFinished -> createSession(onFinished) },
            startForeground = { startForegroundCompat() },
            stopForeground = { stopForegroundCompat() },
            acquireWakeLock = { acquireWakeLock() },
            releaseWakeLock = { releaseWakeLock() },
            requestStopService = { stopSelf() },
            cancelWatchdog = { mainHandler.removeCallbacks(stopWatchdog) },
        )

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopWatchdog)

        if (::controller.isInitialized) {
            controller.onDestroy()
        }

        releaseWakeLock()
        super.onDestroy()
    }

    private fun startMonitoring() {
        // Если сессия уже идёт, повторный START не должен требовать
        // разрешение заново: контроллер сам проигнорирует такой вызов.
        if (!controller.isRunning) {
            if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                statusStore.saveFatalError("Нет разрешения на микрофон")
                stopSelf()
                return
            }
        }

        controller.start()
    }

    private fun stopMonitoring() {
        if (controller.stop()) {
            mainHandler.removeCallbacks(stopWatchdog)
            mainHandler.postDelayed(stopWatchdog, STOP_WATCHDOG_MS)
        }
    }

    private fun handleWatchdog() {
        if (controller.onWatchdog()) {
            mainHandler.postDelayed(stopWatchdog, STOP_WATCHDOG_MS)
        }
    }

    private fun createSession(
        onFinished: (AudioCaptureSession) -> Unit,
    ): AudioCaptureSession? {
        val directory = File(filesDir, RECORDINGS_DIRECTORY)

        if (!directory.exists() && !directory.mkdirs()) {
            statusStore.saveFatalError("Не удалось создать папку записей")
            return null
        }

        return AudioCaptureSession(
            config = config,
            directory = directory,
            status = statusStore,
            sourceFactory = {
                AudioRecordSource(
                    sampleRate = config.sampleRate,
                    preferredBufferBytes = config.bytesPerSecond,
                )
            },
            onSessionFinished = { finished ->
                // Колбэк приходит из потока захвата: в UI и уведомления
                // можно ходить только через главный поток.
                mainHandler.post { onFinished(finished) }
            },
        )
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Sleepy::RecordingWakeLock",
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
            // блокировка уже снята системой
        }

        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Мониторинг сна",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Уведомление о записи звука во время сна"
            }

            val manager = getSystemService(
                NotificationManager::class.java,
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(
            this,
            SleepRecordingService::class.java,
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Sleepy")
            .setContentText("Идёт мониторинг сна")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                stopPendingIntent,
            )
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
            )
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
