package com.sleepy.app.sleepy.audio

/**
 * Состояние мониторинга, которое служба обязана обновлять.
 *
 * Интерфейс отделяет [AudioCaptureSession] от Android: в тестах вместо
 * [RecordingStatusStore] подставляется простая реализация в памяти.
 * Методы рассчитаны на вызов из фоновых потоков (поток захвата и поток
 * записи фрагментов) и не должны блокировать надолго.
 */
interface RecordingStatusSink {

    /**
     * Новая сессия началась.
     *
     * [savedFragmentsOnDisk] — сколько WAV-фрагментов уже лежит в каталоге
     * записей (`-1`, если каталог прочитать не удалось). Счётчик фрагментов
     * отражает реальные файлы на диске, поэтому старые записи не теряются
     * при старте новой сессии.
     */
    fun startSession(
        sessionId: String,
        startedAtMillis: Long,
        savedFragmentsOnDisk: Int,
    )

    /** Сессия завершена пользователем (или штатно). */
    fun markStopped()

    /** Сессия прервана ошибкой; [reason] показывается пользователю. */
    fun markInterrupted(reason: String)

    /** Ошибка, после которой запись не удалось даже начать. */
    fun saveFatalError(message: String)

    /** Обнаружено новое звуковое событие (до сохранения фрагмента). */
    fun recordEventDetected(eventAtMillis: Long)

    /** WAV-фрагмент успешно сохранён на диск. */
    fun recordFragmentSaved(filePath: String, fragmentAtMillis: Long)

    /** Некритичная ошибка: один фрагмент пропущен, сессия продолжается. */
    fun reportWarning(message: String)

    /** Сессия жива: обновляет отметку времени для проверки зависаний. */
    fun touchHeartbeat()

    /**
     * Пакетное обновление диагностики (раз в несколько секунд и в конце
     * сессии). Реализация по умолчанию ничего не делает, чтобы старые
     * тестовые sink-и продолжали работать.
     */
    fun updateDiagnostics(diagnostics: RecordingDiagnostics) {}

    /** Текущее состояние: была ли запись начата и ещё не завершена. */
    fun isRecording(): Boolean
}
