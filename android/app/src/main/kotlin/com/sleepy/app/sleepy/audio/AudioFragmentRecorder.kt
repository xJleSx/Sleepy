package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сохранённый WAV-фрагмент.
 *
 * [durationMs] — длительность файла целиком (вместе с pre-roll и post-roll),
 * [samples] — число сэмплов в файле. Событие может закончиться раньше файла:
 * post-roll остаётся в записи по задумке.
 */
data class SavedAudioFragment(
    val file: File,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMs: Long,
    val samples: Long,
    val startSample: Long,
    val endSample: Long,
    val truncated: Boolean,
    /** Абсолютный индекс первого сэмпла, реально записанного в файл. */
    val contentStartSample: Long = startSample,
    /** Абсолютный индекс сразу после последнего записанного сэмпла. */
    val contentEndSample: Long = contentStartSample + samples,
    val profileId: String = RecordingProfile.STANDARD.storageValue,
    val eventClass: String = RecordingEventMetadata.EVENT_CLASS_NORMAL,
    val reviewStatus: String = RecordingEventMetadata.REVIEW_STATUS_UNREVIEWED,
    val triggerReasons: String = "",
    val thresholdSummary: String = "",
    val peakDbfs: Double? = null,
    val averageDbfs: Double? = null,
)

/**
 * Собирает отдельные WAV-файлы для звуковых событий.
 *
 * Владеет [AudioRingBuffer] и [AudioEventDetector]: каждый чанк сначала
 * попадает в кольцевой буфер, затем анализируется детектором. Благодаря
 * этому абсолютные индексы сэмплов детектора и буфера совпадают, и
 * pre-roll можно отсчитывать от настоящего начала события
 * (`candidateStartSample`), а не от момента подтверждения.
 *
 * Конвейер:
 * 1. `process(chunk)` — буфер + детектор.
 * 2. Детектор подтверждает событие позже его начала на `minEventMs`.
 *    В этот момент из буфера пишется `[startSample - preRoll, сейчас]`,
 *    поэтому звук до подтверждения не теряется и не дублируется.
 * 3. Пока событие активно, каждый новый чанк дописывается в файл ровно
 *    один раз (счётчик `writtenUpTo`).
 * 4. После `onEventFinished` файл живёт до `endSample + postRollSamples`.
 *    Двухсекундная пауза детектора — это ещё не конец post-roll.
 * 5. `truncated`-события (лимит 60 с) закрываются без post-roll, а
 *    следующий фрагмент продолжается с той же точки без повторного
 *    pre-roll.
 *
 * Работа с диском вынесена в [AudioFragmentWriter] и выполняется в
 * отдельном потоке: поток захвата звука только копирует PCM в блоки и
 * ставит команды в очередь, не обращаясь к файловой системе. Проверки
 * каталога и свободного места тоже делает поток записи. Если пул блоков
 * исчерпан, фрагмент удаляется с предупреждением — аудиопоток при этом
 * не ждёт.
 *
 * Потокобезопасности у самого класса нет: [process] и [stop] рассчитаны
 * на один поток захвата звука. Свойства-геттеры безопасны для чтения
 * из другого потока. Открыт не более одного файла одновременно.
 */
class AudioFragmentRecorder(
    val config: RecordingConfig = RecordingConfig.DEFAULT,
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeSpaceProvider: (File) -> Long = { it.usableSpace },
    private val listener: Listener? = null,
    private val openFile: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
    writerQueueBlocks: Int = DEFAULT_WRITER_BLOCKS,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
) {

    interface Listener {
        fun onFragmentSaved(fragment: SavedAudioFragment) {}

        /**
         * Логическое звуковое событие закончилось. Промежуточные обрезания
         * по лимиту 60 секунд сюда не попадают: одно продолжительное
         * событие считается один раз. Вызывается из потока захвата.
         */
        fun onEventDetected(event: DetectedAudioEvent) {}

        /** Некритичная ошибка: фрагмент пропущен или удалён, сессия жива. */
        fun onWarning(message: String) {}
    }

    private val ringBuffer = AudioRingBuffer(config.ringCapacitySamples)

    private val detector = AudioEventDetector(
        config = config,
        listener = DetectorBridge(),
        clock = clock,
    )

    private val transferBuffer = ShortArray(config.chunkSamples)

    private val writer = AudioFragmentWriter(
        config = config,
        directory = directory,
        listener = WriterBridge(),
        openFile = openFile,
        freeSpaceProvider = freeSpaceProvider,
        queueBlocks = writerQueueBlocks,
    )

    private val savedCounter = AtomicInteger(0)

    private var weakAcceptedCounter = 0L
    private var weakRejectedCounter = 0L
    private var lastWeakRejectionMessage = ""
    private var lastWeakSavedAtMillis = Long.MIN_VALUE

    @Volatile
    private var lastWarningMessage = ""

    /**
     * Поток записи сообщил, что фрагмент с этой генерацией сохранить не
     * удалось. Значение ставится из потока записи и читается потоком
     * захвата; сбрасывать его можно только в потоке захвата.
     */
    @Volatile
    private var failedWriterGeneration = -1L

    /** Номер последнего открытого фрагмента (только поток захвата). */
    private var writerGeneration = 0L

    /** Генерация фрагмента, который сейчас считается открытым. */
    private var currentWriterGeneration = 0L

    @Volatile
    private var fragmentOpen = false

    private var writtenUpTo = 0L

    private var postRollEndSample = -1L

    private var expectContinuation = false

    private var fragmentStartMillis = 0L

    private var fragmentEndMillis = 0L

    private var fragmentStartSample = 0L

    private var fragmentEndSample = 0L

    private var fragmentTruncated = false

    private var lastEventPeakDbfs: Double? = null

    private var lastEventAverageDbfs: Double? = null

    private var lastContentEndSample = 0L

    private var previousEventEndSample = 0L

    private var stopped = false

    val isRecordingEvent: Boolean
        get() = fragmentOpen

    val isWritingPostRoll: Boolean
        get() = fragmentOpen && postRollEndSample >= 0L

    val currentFragmentPath: File?
        get() = writer.activePath

    val savedFragments: Int
        get() = savedCounter.get()

    val lastWarning: String
        get() = lastWarningMessage

    val noiseFloorDbfs: Double
        get() = detector.noiseFloorDbfs

    /**
     * Снимок диагностики сессии без полей [RecordingDiagnostics.samplesRead]
     * и [RecordingDiagnostics.readErrorCount]: их заполняет [AudioCaptureSession].
     * Читать нужно из потока захвата после обработки чанка; счётчики writer
     * потокобезопасны.
     */
    val diagnostics: RecordingDiagnostics
        get() = RecordingDiagnostics(
            lastChunkDbfs = detector.currentChunkDbfs,
            noiseFloorDbfs = detector.noiseFloorDbfs,
            currentDetectorState = detector.currentState.name,
            candidateCount = detector.candidateCountTotal,
            confirmedEventCount = detector.confirmedEventCountTotal,
            writerOpenCount = writer.openCountTotal,
            writerSavedCount = writer.savedCountTotal,
            writerFailedCount = writer.failedCountTotal,
            lastWriterError = writer.lastErrorText,
            lastFragmentPath = writer.lastSavedPathText,
            profileId = config.profile.storageValue,
            weakCandidateCount = detector.weakCandidateCountTotal,
            weakSavedCount = weakAcceptedCounter,
            weakRejectedCount = weakRejectedCounter,
            lastWeakRejection = lastWeakRejectionMessage,
        )

    /**
     * Основная точка входа конвейера. Принимает чанки PCM 16 бит / моно.
     * После [stop] вызовы игнорируются.
     */
    fun process(
        chunk: ShortArray,
        offset: Int = 0,
        length: Int = chunk.size - offset,
    ) {
        if (stopped) return

        // Сначала разбираемся с отказом записи: детектор в этом же вызове
        // может начать новое событие, и его нельзя случайно отменить.
        consumeWriterFailure()

        ringBuffer.write(chunk, offset, length)
        detector.process(chunk, offset, length)

        if (length == 0) return

        appendPendingSamples()
        maybeFinalizePostRoll()
    }

    /**
     * Завершает активное событие и закрывает текущий файл. Вызывать при
     * остановке службы; после этого [process] ничего не делает.
     */
    fun stop() {
        if (stopped) return

        stopped = true
        consumeWriterFailure()
        detector.flush()
        finishFragment()

        if (!writer.stop(stopTimeoutMs)) {
            warn("Поток записи фрагментов не остановился за $stopTimeoutMs мс")
        }
    }

    private inner class DetectorBridge : AudioEventDetector.Listener {
        override fun onEventStarted(startedAtMillis: Long, startSample: Long) {
            handleEventStarted(startedAtMillis, startSample)
        }

        override fun onEventFinished(event: DetectedAudioEvent) {
            handleEventFinished(event)
        }

        override fun onWeakCandidate(event: DetectedAudioEvent) {
            handleWeakCandidate(event)
        }
    }

    private inner class WriterBridge : AudioFragmentWriter.Listener {
        override fun onFragmentSaved(fragment: SavedAudioFragment) {
            savedCounter.incrementAndGet()
            listener?.onFragmentSaved(fragment)
        }

        override fun onFragmentFailed(generation: Long, message: String) {
            // Файл уже удалён или не создан: поток захвата должен перестать
            // отправлять данные в этот фрагмент и начать новый со следующего
            // события. Старые генерации игнорируются.
            failedWriterGeneration = generation
            lastWarningMessage = message
            listener?.onWarning(message)
        }

        override fun onWarning(message: String) {
            lastWarningMessage = message
            listener?.onWarning(message)
        }
    }

    private fun handleEventStarted(startedAtMillis: Long, startSample: Long) {
        consumeWriterFailure()

        val continuation = expectContinuation
        expectContinuation = false

        val requestedFrom: Long
        if (continuation) {
            // Продолжение начинается ровно на границе предыдущего файла:
            // pre-roll уже записан, повторять его нельзя.
            finishFragment()
            requestedFrom = startSample
        } else {
            val desiredFrom = maxOf(0L, startSample - config.preRollSamples)
            // Граница раздела соседних файлов: не позже начала нового
            // события и не раньше конца предыдущего. Новый файл забирает
            // себе участок между событиями как pre-roll, а хвост
            // предыдущего файла обрезается до этой же точки. Так один и
            // тот же диапазон не попадает в два файла и не теряется.
            val previousEnd = if (fragmentOpen) {
                maxOf(previousEventEndSample, fragmentEndSample)
            } else {
                previousEventEndSample
            }
            val boundary = maxOf(previousEnd, desiredFrom)
            finishFragment(trimTo = boundary)
            requestedFrom = maxOf(boundary, lastContentEndSample)
        }

        val from = maxOf(requestedFrom, ringBuffer.oldestAvailableSample)

        if (from > requestedFrom) {
            warn("Pre-roll обрезан: кольцевой буфер уже перезаписан")
        }

        writerGeneration += 1
        currentWriterGeneration = writerGeneration
        writer.open(
            generation = currentWriterGeneration,
            startedAtMillis = startedAtMillis,
            startSample = startSample,
            contentStartSample = from,
            profileId = config.profile.storageValue,
            eventClass = RecordingEventMetadata.EVENT_CLASS_NORMAL,
            triggerReasons = "normal-threshold",
            thresholdSummary = config.thresholdSummary,
        )

        fragmentOpen = true
        fragmentStartMillis = startedAtMillis
        fragmentEndMillis = startedAtMillis
        fragmentStartSample = startSample
        fragmentEndSample = startSample
        fragmentTruncated = false
        postRollEndSample = -1L
        writtenUpTo = from

        if (!appendPendingSamples()) return

        // Фрагмент-продолжение открывается ровно на границе предыдущего,
        // поэтому до первого нового чанка он законно пуст: проверку
        // выполняем только для обычного события.
        if (!continuation && writtenUpTo == from) {
            abortFragment("Пустой фрагмент удалён")
        }
    }

    private fun handleEventFinished(event: DetectedAudioEvent) {
        consumeWriterFailure()

        if (!event.truncated) {
            listener?.onEventDetected(event)
        }

        if (!fragmentOpen) {
            expectContinuation = event.truncated
            return
        }

        fragmentEndMillis = event.endedAtMillis
        fragmentEndSample = event.endSample
        fragmentTruncated = event.truncated
        lastEventPeakDbfs = event.peakDbfs
        lastEventAverageDbfs = event.averageDbfs

        if (event.truncated) {
            expectContinuation = true
            appendPendingSamples()
            finishFragment()
        } else {
            postRollEndSample = event.endSample + config.postRollSamples
        }
    }

    private fun handleWeakCandidate(event: DetectedAudioEvent) {
        consumeWriterFailure()

        if (fragmentOpen || expectContinuation) {
            rejectWeak("weak-overlaps-normal-event")
            return
        }
        if (config.weakMaxCandidatesPerSession <= 0 ||
            weakAcceptedCounter >= config.weakMaxCandidatesPerSession
        ) {
            rejectWeak("weak-session-limit")
            return
        }
        if (lastWeakSavedAtMillis != Long.MIN_VALUE &&
            event.startedAtMillis - lastWeakSavedAtMillis < config.weakCooldownMs
        ) {
            rejectWeak("weak-cooldown")
            return
        }

        val desiredFrom = maxOf(0L, event.startSample - config.preRollSamples)
        val desiredTo = minOf(
            ringBuffer.totalSamplesWritten,
            event.endSample + config.postRollSamples,
        )
        val requestedFrom = maxOf(desiredFrom, ringBuffer.oldestAvailableSample)
        if (requestedFrom >= desiredTo) {
            rejectWeak("weak-audio-unavailable")
            return
        }
        if (requestedFrom > desiredFrom) {
            warn("Pre-roll слабого кандидата обрезан: кольцевой буфер перезаписан")
        }

        writerGeneration += 1
        currentWriterGeneration = writerGeneration
        writer.open(
            generation = currentWriterGeneration,
            startedAtMillis = event.startedAtMillis,
            startSample = event.startSample,
            contentStartSample = requestedFrom,
            profileId = config.profile.storageValue,
            eventClass = RecordingEventMetadata.EVENT_CLASS_WEAK,
            triggerReasons = event.triggerReason.ifBlank { "weak-threshold" },
            thresholdSummary = config.thresholdSummary,
        )

        fragmentOpen = true
        fragmentStartMillis = event.startedAtMillis
        fragmentEndMillis = event.endedAtMillis
        fragmentStartSample = event.startSample
        fragmentEndSample = event.endSample
        fragmentTruncated = event.truncated
        lastEventPeakDbfs = event.peakDbfs
        lastEventAverageDbfs = event.averageDbfs
        postRollEndSample = desiredTo
        writtenUpTo = requestedFrom

        if (!appendPendingSamples()) {
            weakRejectedCounter += 1
            lastWeakRejectionMessage = "weak-write-failed"
            return
        }

        finishFragment()
        weakAcceptedCounter += 1
        lastWeakSavedAtMillis = event.endedAtMillis
    }

    private fun rejectWeak(reason: String) {
        weakRejectedCounter += 1
        lastWeakRejectionMessage = reason
    }

    /**
     * Дописывает в очередь всё, что уже есть в кольцевом буфере вплоть до
     * [postRollEndSample] (или до последнего записанного сэмпла).
     *
     * @return `false`, если фрагмент пришлось удалить.
     */
    private fun appendPendingSamples(): Boolean {
        if (!fragmentOpen) return true

        if (writerFailedForCurrent()) {
            consumeWriterFailure()
            return false
        }

        var target = ringBuffer.totalSamplesWritten
        if (postRollEndSample >= 0L && target > postRollEndSample) {
            target = postRollEndSample
        }
        if (target <= writtenUpTo) return true

        // Явная обработка вытеснения: если буфер успел перезаписать нужный
        // диапазон, честно сообщаем о потере и больше не притворяемся,
        // что файл непрерывен.
        val oldest = ringBuffer.oldestAvailableSample
        if (writtenUpTo < oldest) {
            warn(
                "Часть аудио потеряна: кольцевой буфер уже перезаписан " +
                    "(пропущено ${oldest - writtenUpTo} сэмплов)",
            )
            writtenUpTo = oldest
        }
        if (target <= writtenUpTo) return true

        while (writtenUpTo < target) {
            if (writerFailedForCurrent()) {
                consumeWriterFailure()
                return false
            }

            val oldest = ringBuffer.oldestAvailableSample
            if (writtenUpTo < oldest) {
                warn(
                    "Часть аудио потеряна: кольцевой буфер уже перезаписан " +
                        "(пропущено ${oldest - writtenUpTo} сэмплов)",
                )
                writtenUpTo = oldest
                if (writtenUpTo >= target) break
            }

            val requested = minOf(target - writtenUpTo, transferBuffer.size.toLong()).toInt()
            val copied = ringBuffer.copyAbsoluteTo(
                writtenUpTo,
                transferBuffer,
                0,
                requested,
            )

            if (copied <= 0) {
                warn("Часть аудио потеряна: кольцевой буфер уже перезаписан")
                break
            }
            if (copied < requested) {
                warn("Часть аудио потеряна: кольцевой буфер уже перезаписан")
            }

            if (!writer.append(transferBuffer, 0, copied)) {
                abortFragment("Не хватает буфера записи: фрагмент удалён")
                return false
            }

            writtenUpTo += copied
        }

        return true
    }

    private fun maybeFinalizePostRoll() {
        if (!fragmentOpen || postRollEndSample < 0L) return
        if (ringBuffer.totalSamplesWritten >= postRollEndSample) {
            finishFragment()
        }
    }

    private fun finishFragment(trimTo: Long? = null) {
        if (!fragmentOpen) return

        if (writerFailedForCurrent()) {
            // Файл уже удалён/не создан: не отправляем Finish и не двигаем
            // границу сохранённого аудио.
            consumeWriterFailure()
            return
        }

        val contentEnd = if (trimTo != null) minOf(trimTo, writtenUpTo) else writtenUpTo
        writer.finish(
            endedAtMillis = fragmentEndMillis,
            endSample = fragmentEndSample,
            truncated = fragmentTruncated,
            contentEndSample = contentEnd,
            peakDbfs = lastEventPeakDbfs,
            averageDbfs = lastEventAverageDbfs,
        )
        lastContentEndSample = contentEnd
        if (fragmentEndSample > 0L) {
            previousEventEndSample = fragmentEndSample
        }
        fragmentOpen = false
        resetFragmentState()
    }

    private fun abortFragment(message: String) {
        if (fragmentOpen) {
            if (!writerFailedForCurrent()) {
                writer.abort()
            }
            fragmentOpen = false
            resetFragmentState()
        }
        failedWriterGeneration = -1L
        warn(message)
    }

    /**
     * Поток записи уже удалил текущий фрагмент. Сбрасываем только локальное
     * состояние: границы уже сохранённого аудио не меняются, а следующее
     * событие сможет открыть новый файл. Отказ старой генерации не должен
     * отменять уже открытый новый фрагмент.
     */
    private fun consumeWriterFailure() {
        val failed = failedWriterGeneration
        if (failed < 0L) return

        failedWriterGeneration = -1L

        if (failed == currentWriterGeneration && fragmentOpen) {
            fragmentOpen = false
            resetFragmentState()
        }
    }

    private fun writerFailedForCurrent(): Boolean =
        fragmentOpen && failedWriterGeneration == currentWriterGeneration

    private fun resetFragmentState() {
        writtenUpTo = 0L
        postRollEndSample = -1L
        fragmentStartMillis = 0L
        fragmentEndMillis = 0L
        fragmentStartSample = 0L
        fragmentEndSample = 0L
        fragmentTruncated = false
        lastEventPeakDbfs = null
        lastEventAverageDbfs = null
    }

    private fun warn(message: String) {
        lastWarningMessage = message
        listener?.onWarning(message)
    }

    companion object {
        const val DEFAULT_WRITER_BLOCKS = 256
        const val DEFAULT_STOP_TIMEOUT_MS = 5_000L
    }
}
