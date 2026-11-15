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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SleepRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.sleepy.app.sleepy.START_RECORDING"
        const val ACTION_STOP = "com.sleepy.app.sleepy.STOP_RECORDING"

        private const val CHANNEL_ID = "sleepy_recording_channel"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "sleepy_recording"

        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var outputStream: RandomAccessFile? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }

        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            saveError("Нет разрешения на микрофон")
            stopSelf()
            return
        }

        try {
            startForegroundCompat()

            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minimumBufferSize <= 0) {
                throw IllegalStateException(
                    "Не удалось определить размер аудиобуфера"
                )
            }

            val bufferSize = maxOf(
                minimumBufferSize,
                SAMPLE_RATE * 2
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException(
                    "Не удалось инициализировать микрофон"
                )
            }

            val directory = File(filesDir, "recordings")

            if (!directory.exists() && !directory.mkdirs()) {
                recorder.release()
                throw IllegalStateException(
                    "Не удалось создать папку записей"
                )
            }

            val date = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())

            val file = File(
                directory,
                "sleep_$date.wav"
            )

            val stream = RandomAccessFile(file, "rw")
            stream.setLength(0)

            writeWavHeader(stream, 0)

            recorder.startRecording()

            if (
                recorder.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder.release()
                stream.close()

                throw IllegalStateException(
                    "Микрофон не начал запись"
                )
            }

            audioRecord = recorder
            outputStream = stream
            outputFile = file

            isRecording = true

            acquireWakeLock()

            val preferences = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

            preferences.edit()
                .putBoolean("is_recording", true)
                .putString("file_path", file.absolutePath)
                .putLong("started_at", System.currentTimeMillis())
                .putString("error", "")
                .apply()

            recordingThread = Thread {
                captureAudio(recorder, stream)
            }.apply {
                name = "SleepyAudioRecording"
                start()
            }

        } catch (exception: Exception) {
            isRecording = false
            saveError(
                exception.message ?: "Неизвестная ошибка записи"
            )

            releaseResources()

            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun captureAudio(
        recorder: AudioRecord,
        stream: RandomAccessFile
    ) {
        val buffer = ByteArray(8192)
        var recordingError = ""

        try {
            while (isRecording) {
                val bytesRead = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )

                if (bytesRead > 0) {
                    stream.write(buffer, 0, bytesRead)
                } else if (bytesRead < 0 && isRecording) {
                    recordingError = "Ошибка чтения микрофона: $bytesRead"
                    break
                }
            }
        } catch (exception: Exception) {
            recordingError =
                exception.message ?: "Ошибка во время записи"
        } finally {
            isRecording = false

            try {
                val audioDataLength = (stream.length() - 44)
                    .coerceAtLeast(0)

                writeWavHeader(stream, audioDataLength)
                stream.close()
            } catch (_: Exception) {
            }

            try {
                recorder.release()
            } catch (_: Exception) {
            }

            audioRecord = null
            outputStream = null

            releaseWakeLock()

            val preferences = getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

            preferences.edit()
                .putBoolean("is_recording", false)
                .putString("error", recordingError)
                .apply()

            Handler(Looper.getMainLooper()).post {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopForegroundCompat()
            stopSelf()
            return
        }

        isRecording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        audioDataLength: Long
    ) {
        val byteRate =
            SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        val blockAlign =
            CHANNELS * BITS_PER_SAMPLE / 8

        val totalDataLength = audioDataLength + 36

        file.seek(0)

        file.writeBytes("RIFF")
        writeIntLE(file, totalDataLength.toInt())

        file.writeBytes("WAVE")

        file.writeBytes("fmt ")
        writeIntLE(file, 16)
        writeShortLE(file, 1)
        writeShortLE(file, CHANNELS)
        writeIntLE(file, SAMPLE_RATE)
        writeIntLE(file, byteRate)
        writeShortLE(file, blockAlign)
        writeShortLE(file, BITS_PER_SAMPLE)

        file.writeBytes("data")
        writeIntLE(file, audioDataLength.toInt())
    }

    private fun writeIntLE(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
        file.write((value shr 16) and 0xFF)
        file.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(
        file: RandomAccessFile,
        value: Int
    ) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Sleepy::RecordingWakeLock"
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
        }

        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Мониторинг сна",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о записи звука во время сна"
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(
            this,
            SleepRecordingService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
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
                stopPendingIntent
            )
            .build()
    }

    private fun startForegroundCompat() {
    val notification = buildNotification()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    } else {
        startForeground(
            NOTIFICATION_ID,
            notification
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

    private fun saveError(message: String) {
        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).edit()
            .putBoolean("is_recording", false)
            .putString("error", message)
            .apply()
    }

    private fun releaseResources() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        try {
            outputStream?.close()
        } catch (_: Exception) {
        }

        audioRecord = null
        outputStream = null

        releaseWakeLock()
    }
}