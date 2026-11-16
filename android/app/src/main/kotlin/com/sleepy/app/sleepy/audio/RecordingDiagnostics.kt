package com.sleepy.app.sleepy.audio

/**
 * Диагностический снимок текущей (или последней завершённой) сессии.
 *
 * Счётчики описывают одну сессию: они обнуляются в
 * [RecordingStatusSink.startSession] и сохраняются после остановки, чтобы
 * разбирать пропущенные события без повторного запуска службы.
 *
 * [samplesRead] и [readErrorCount] заполняет поток захвата. Остальные поля
 * берутся из [AudioFragmentRecorder]: уровень последнего блока, состояние
 * детектора и результаты работы потока записи WAV.
 */
data class RecordingDiagnostics(
    val samplesRead: Long = 0L,
    val readErrorCount: Long = 0L,
    val lastChunkDbfs: Double = AudioEventDetector.MIN_DBFS,
    val noiseFloorDbfs: Double = AudioEventDetector.MIN_DBFS,
    val currentDetectorState: String = "",
    val candidateCount: Long = 0L,
    val confirmedEventCount: Long = 0L,
    val writerOpenCount: Long = 0L,
    val writerSavedCount: Long = 0L,
    val writerFailedCount: Long = 0L,
    val lastWriterError: String = "",
    val lastFragmentPath: String = "",
)
