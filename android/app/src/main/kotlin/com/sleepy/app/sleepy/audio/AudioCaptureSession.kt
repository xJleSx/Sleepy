package com.sleepy.app.sleepy.audio

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Источник PCM-сэмплов (PCM 16 бит / моно).
 *
 * Отделяет цикл захвата от Android: в приложении это обёртка над
 * [android.media.AudioRecord], в тестах — простой генератор.
 */
interface AudioSource {

    /** Готовит источник к работе. Может бросить исключение. */
    fun start()

    /**
     * Читает до `destination.size` сэмплов. Блокируется, пока данные не
     * появятся. Возвращает число прочитанных сэмплов, `0` — данных не было,
     * отрицательное значение — ошибка (как у `AudioRecord.read`).
     */
    fun read(destination: ShortArray): Int

    /** Прерывает блокирующее чтение; можно вызывать из другого потока. */
    fun stop()

    /** Освобождает ресурсы источника. */
    fun release()
}

/**
 * Одна сессия мониторинга: цикл чтения микрофона → [AudioFragmentRecorder].
 *
 * Сессия владеет потоком захвата и экземпляром recorder. Запускать её
 * повторно до завершения предыдущего запуска нельзя: [start] вернёт
 * `false`, поэтому второй поток захвата и второй recorder в одной сессии
 * не появятся.
 *
 * Вся обработка ошибок и освобождение ресурсов сосредоточены здесь:
 * - ошибка запуска источника или создания recorder → `saveFatalError`,
 *   источник освобождается, `start()` возвращает `false`;
 * - ошибка чтения/записи во время сессии → `markInterrupted` с причиной,
 *   `AudioRecord` освобождается, recorder корректно закрывает фрагмент;
 * - [stop] только сигнализирует об остановке и прерывает `read`; тяжёлое
 *   закрытие файлов и освобождение источника выполняет поток захвата,
 *   после чего вызывает [onSessionFinished].
 *
 * `onSessionFinished` получает завершившийся экземпляр сессии (служба
 * сверяет его с текущим, чтобы запоздавший колбэк не остановил новую
 * сессию) и вызывается из потока захвата: служба обязана переключиться
 * на главный поток перед работой с уведомлением.
 */
class AudioCaptureSession(
    private val config: RecordingConfig = RecordingConfig.DEFAULT,
    private val directory: File,
    private val status: RecordingStatusSink,
    private val sourceFactory: () -> AudioSource,
    private val recorderFactory: (AudioFragmentRecorder.Listener) -> AudioFragmentRecorder = {
        listener ->
        AudioFragmentRecorder(
            config = config,
            directory = directory,
            listener = listener,
        )
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIdFactory: (Long) -> String = ::defaultSessionId,
    private val onSessionFinished: ((AudioCaptureSession) -> Unit)? = null,
) : RecordingSessionHandle {

    private val lifecycleLock = Any()

    private val holdsGlobalSlot = AtomicBoolean(false)

    private val readSamples = maxOf(config.chunkSamples, config.sampleRate / READ_WINDOW_DIVISOR)

    private val heartbeatSamples =
        config.sampleRate.toLong() * HEARTBEAT_INTERVAL_SECONDS

    private val diagnosticsIntervalSamples =
        config.sampleRate.toLong() * DIAGNOSTICS_INTERVAL_SECONDS

    @Volatile
    private var running = false

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var currentSource: AudioSource? = null

    val isRunning: Boolean
        get() = running

    /** Поток захвата ещё жив (в том числе доделывает закрытие файлов). */
    override val isActive: Boolean
        get() = running || captureThread?.isAlive == true

    /**
     * Запускает новую сессию. Возвращает `false`, если сессия уже идёт
     * или запуск не удался (причина записана в [status]).
     */
    override fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (running) return false
            if (captureThread?.isAlive == true) return false

            // В процессе может быть только одна активная сессия. Проверка
            // нужна на случай, когда новая служба стартует, пока поток
            // предыдущей ещё держит микрофон.
            if (!activeSession.compareAndSet(null, this)) {
                status.reportWarning(
                    "Предыдущая сессия записи ещё завершается",
                )
                return false
            }
            holdsGlobalSlot.set(true)

            // Считаем файлы до запуска микрофона: сканирование каталога не
            // должно задерживать чтение первых сэмплов.
            val savedFragmentsOnDisk = countSavedFragments()

            val source = try {
                sourceFactory()
            } catch (error: Exception) {
                releaseGlobalSlot()
                status.saveFatalError(
                    error.message ?: "Не удалось создать источник аудио",
                )
                return false
            }

            try {
                source.start()
            } catch (error: Exception) {
                stopAndReleaseQuietly(source)
                releaseGlobalSlot()
                status.saveFatalError(
                    error.message ?: "Не удалось запустить микрофон",
                )
                return false
            }

            val recorder = try {
                recorderFactory(RecorderBridge())
            } catch (error: Exception) {
                stopAndReleaseQuietly(source)
                releaseGlobalSlot()
                status.saveFatalError(
                    error.message ?: "Не удалось создать обработчик фрагментов",
                )
                return false
            }

            val startedAt = clock()

            try {
                status.startSession(
                    sessionIdFactory(startedAt),
                    startedAt,
                    savedFragmentsOnDisk,
                )

                val thread = Thread(
                    { captureLoop(source, recorder) },
                    CAPTURE_THREAD_NAME,
                ).apply {
                    isDaemon = true
                }

                running = true
                currentSource = source
                captureThread = thread
                thread.start()

                return true
            } catch (error: Exception) {
                running = false
                currentSource = null

                stopAndReleaseQuietly(source)
                try {
                    recorder.stop()
                } catch (_: Exception) {
                    // recorder уже не нужен
                }

                releaseGlobalSlot()
                status.saveFatalError(
                    error.message ?: "Не удалось запустить запись",
                )
                return false
            }
        }
    }

    /**
     * Просит сессию остановиться. Не блокирует вызывающий поток: закрытие
     * файлов и освобождение микрофона доделает поток захвата.
     */
    override fun stop() {
        running = false
        try {
            currentSource?.stop()
        } catch (_: Exception) {
            // источник всё равно освободит поток захвата
        }
    }

    /**
     * Ждёт завершения потока захвата. Нужен тестам и защитной логике;
     * основной путь остановки — [stop] + [onSessionFinished].
     */
    fun awaitStopped(timeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS): Boolean {
        val thread = captureThread ?: return true
        return try {
            thread.join(timeoutMs)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private inner class RecorderBridge : AudioFragmentRecorder.Listener {
        override fun onFragmentSaved(fragment: SavedAudioFragment) {
            status.recordFragmentSaved(
                fragment.file.absolutePath,
                fragment.endedAtMillis,
            )
        }

        override fun onEventDetected(event: DetectedAudioEvent) {
            status.recordEventDetected(event.endedAtMillis)
        }

        override fun onWarning(message: String) {
            status.reportWarning(message)
        }
    }

    private fun captureLoop(
        source: AudioSource,
        recorder: AudioFragmentRecorder,
    ) {
        val buffer = ShortArray(readSamples)
        var samplesSinceHeartbeat = 0L
        var samplesReadTotal = 0L
        var readErrorCount = 0L
        var samplesSinceDiagnostics = 0L
        var failure = ""

        try {
            while (running) {
                val samplesRead = source.read(buffer)

                if (samplesRead > 0) {
                    val count = minOf(samplesRead, buffer.size)
                    var offset = 0
                    while (offset < count) {
                        val part = minOf(config.chunkSamples, count - offset)
                        // Срезы передаются по offset без копирования.
                        recorder.process(buffer, offset, part)
                        offset += part
                    }

                    samplesReadTotal += count
                    samplesSinceHeartbeat += count
                    if (samplesSinceHeartbeat >= heartbeatSamples) {
                        samplesSinceHeartbeat = 0L
                        status.touchHeartbeat()
                    }

                    samplesSinceDiagnostics += count
                    if (samplesSinceDiagnostics >= diagnosticsIntervalSamples) {
                        samplesSinceDiagnostics = 0L
                        publishDiagnostics(
                            recorder,
                            samplesReadTotal,
                            readErrorCount,
                        )
                    }
                } else if (samplesRead < 0) {
                    if (running) {
                        readErrorCount += 1L
                        failure = "Ошибка чтения микрофона: $samplesRead"
                    }
                    break
                }
            }
        } catch (error: Exception) {
            failure = error.message ?: "Ошибка во время записи"
        } finally {
            running = false

            stopAndReleaseQuietly(source)

            try {
                recorder.stop()
            } catch (error: Exception) {
                if (failure.isEmpty()) {
                    failure = error.message ?: "Ошибка при закрытии фрагментов"
                }
            }

            publishDiagnostics(recorder, samplesReadTotal, readErrorCount)

            currentSource = null

            if (failure.isEmpty()) {
                status.markStopped()
            } else {
                status.markInterrupted(failure)
            }

            releaseGlobalSlot()

            try {
                onSessionFinished?.invoke(this)
            } catch (_: Exception) {
                // колбэк не должен ломать завершение сессии
            }
        }
    }

    /**
     * Отдаёт в sink один снимок диагностики. Вызывается из потока захвата
     * раз в [DIAGNOSTICS_INTERVAL_SECONDS] секунд и один раз после закрытия
     * writer, чтобы после остановки остались финальные значения.
     */
    private fun publishDiagnostics(
        recorder: AudioFragmentRecorder,
        samplesRead: Long,
        readErrorCount: Long,
    ) {
        try {
            status.updateDiagnostics(
                recorder.diagnostics.copy(
                    samplesRead = samplesRead,
                    readErrorCount = readErrorCount,
                ),
            )
        } catch (_: Exception) {
            // Диагностика не должна прерывать запись или её остановку.
        }
    }

    private fun stopAndReleaseQuietly(source: AudioSource) {
        try {
            source.stop()
        } catch (_: Exception) {
            // освобождаем дальше
        }
        try {
            source.release()
        } catch (_: Exception) {
            // ресурс всё равно уже не нужен
        }
    }

    /**
     * Сколько валидных WAV-фрагментов уже сохранено в каталоге записей.
     *
     * Счётчик фрагментов описывает реальные файлы на диске: фрагменты
     * переживают перезапуск службы, поэтому новая сессия не должна
     * начинать отсчёт с нуля. Валидность проверяется теми же правилами,
     * что и в каталоге событий ([AudioFragmentCatalog]). `-1` — каталог
     * прочитать не удалось, прежнее значение счётчика сохраняется.
     */
    private fun countSavedFragments(): Int = try {
        AudioFragmentCatalog.countValid(directory)
    } catch (_: Exception) {
        -1
    }

    private fun releaseGlobalSlot() {
        if (holdsGlobalSlot.compareAndSet(true, false)) {
            activeSession.compareAndSet(this, null)
        }
    }

    companion object {
        const val DEFAULT_STOP_TIMEOUT_MS = 8_000L

        private const val READ_WINDOW_DIVISOR = 10
        private const val HEARTBEAT_INTERVAL_SECONDS = 5L
        private const val DIAGNOSTICS_INTERVAL_SECONDS = 3L
        private const val CAPTURE_THREAD_NAME = "SleepyAudioCapture"

        /** Сессия, которая сейчас владеет микрофоном в этом процессе. */
        private val activeSession = AtomicReference<AudioCaptureSession?>(null)
    }
}

private fun defaultSessionId(startedAtMillis: Long): String =
    SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        .format(Date(startedAtMillis))
