package com.sleepy.app.sleepy.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * [AudioSource] поверх Android [AudioRecord]: PCM 16 бит, моно.
 *
 * Класс только управляет микрофоном и ничего не знает о файлах и
 * детекторе. Все исключения пробрасываются вызывающему —
 * [AudioCaptureSession] превращает их в статус сессии.
 */
class AudioRecordSource(
    private val sampleRate: Int,
    private val preferredBufferBytes: Int = sampleRate * 2,
) : AudioSource {

    private var record: AudioRecord? = null

    override fun start() {
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minimumBufferSize <= 0) {
            throw IllegalStateException(
                "Не удалось определить размер аудиобуфера",
            )
        }

        val bufferSize = maxOf(minimumBufferSize, preferredBufferBytes)

        val created = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw IllegalStateException("Не удалось инициализировать микрофон")
        }

        record = created
        created.startRecording()

        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record = null
            created.release()
            throw IllegalStateException("Микрофон не начал запись")
        }
    }

    override fun read(destination: ShortArray): Int {
        val record = this.record ?: return -1
        return record.read(destination, 0, destination.size)
    }

    override fun stop() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // микрофон уже остановлен
        }
    }

    override fun release() {
        val record = this.record ?: return
        this.record = null

        try {
            record.release()
        } catch (_: Exception) {
            // освобождать больше нечего
        }
    }
}
