package com.sleepy.app.sleepy.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Звуковое событие, обнаруженное детектором.
 *
 * [startedAtMillis] — момент начала первого громкого чанка (до подтверждения),
 * [endedAtMillis] — конец последнего чанка выше порога отпускания.
 * Длительность считается по аудиосэмплам, а не по системным часам.
 */
data class DetectedAudioEvent(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMs: Long,
    val peakDbfs: Double,
    val averageDbfs: Double,
    val truncated: Boolean,
    /** Абсолютный индекс первого сэмпла события (в шкале детектора). */
    val startSample: Long,
    /** Абсолютный индекс сэмпла сразу после последнего громкого. */
    val endSample: Long,
    val eventClass: String = RecordingEventMetadata.EVENT_CLASS_NORMAL,
    val triggerReason: String = "",
)

/**
 * Детектор звуковых событий по энергетическому уровню PCM-сигнала.
 *
 * Работает с чанками PCM 16 бит / моно / 16 кГц. Обрабатывать звук должен
 * ровно один поток (поток захвата), поэтому внутри нет синхронизации.
 *
 * Событие начинается, когда RMS-уровень чанка превышает адаптивный уровень
 * фонового шума на `thresholdAboveNoiseDb` при условии, что он выше
 * абсолютного порога `absoluteFloorDbfs`, и держится так не меньше
 * `minEventMs`. Окончание — уровень ниже порога минус `hysteresisDb`
 * дольше `mergeGapMs`; это же окно объединяет близкие события.
 *
 * Интеграция с [AudioRingBuffer]:
 * - при `onEventStarted` владелец уже может взять `preRollSamples` сэмплов
 *   из кольцевого буфера (`copyLast`) и начать собирать фрагмент;
 * - при `onEventFinished` в буфере лежат последние `postRollSamples`
 *   сэмплов; недостающий хвост post-roll дописывается из следующих чанков
 *   или из буфера.
 */
class AudioEventDetector(
    val config: RecordingConfig = RecordingConfig.DEFAULT,
    private val listener: Listener? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    interface Listener {
        /**
         * Событие подтверждено.
         *
         * [startedAtMillis] — время первого громкого чанка,
         * [startSample] — его абсолютный индекс в шкале сэмплов детектора.
         * Pre-roll нужно отсчитывать именно от [startSample], а не от
         * момента вызова колбэка: подтверждение приходит на 300 мс позже.
         */
        fun onEventStarted(startedAtMillis: Long, startSample: Long) {}

        fun onEventFinished(event: DetectedAudioEvent) {}

        /**
         * Слабое событие, которое не дотянуло до обычного порога.
         *
         * Это кандидат на проверку, а не подтверждённый тип звука. Владелец
         * должен сохранить его отдельно и пометить как `WEAK_CANDIDATE`.
         */
        fun onWeakCandidate(event: DetectedAudioEvent) {}
    }

    enum class DetectorState { WARMING_UP, QUIET, CANDIDATE, ACTIVE }

    private var state: DetectorState =
        if (config.warmupSamples > 0) {
            DetectorState.WARMING_UP
        } else {
            DetectorState.QUIET
        }

    private var candidateCount = 0L

    private var confirmedEventCount = 0L

    private var noiseFloor = config.absoluteFloorDbfs

    private var lastChunkLevel = MIN_DBFS

    private var warmupSamplesCollected = 0L
    private var warmupLevelSum = 0.0

    private var totalSamples = 0L

    private var candidateStartSample = 0L
    private var candidateStartMillis = 0L
    private var candidateSamples = 0L
    private var candidateSumSquares = 0.0
    private var candidatePeakDbfs = MIN_DBFS

    private var eventStartSample = 0L
    private var eventStartMillis = 0L
    private var eventLoudSamples = 0L
    private var eventSumSquares = 0.0
    private var eventPeakDbfs = MIN_DBFS
    private var lastLoudSample = 0L
    private var lastLoudMillis = 0L
    private var quietSamples = 0L
    private var eventIsContinuation = false

    private var weakCandidateCount = 0L
    private var weakEmittedCount = 0L
    private var weakRejectedCount = 0L
    private var lastWeakRejection = ""
    private var weakCandidateStartSample = -1L
    private var weakCandidateStartMillis = 0L
    private var weakCandidateSamples = 0L
    private var weakCandidateSumSquares = 0.0
    private var weakCandidatePeakDbfs = MIN_DBFS
    private var lastWeakLoudSample = 0L
    private var lastWeakLoudMillis = 0L
    private var weakQuietSamples = 0L
    private var weakBlockedUntilQuiet = false

    /** Текущая оценка фонового шума в dBFS. */
    val noiseFloorDbfs: Double
        get() = noiseFloor

    /** Уровень последнего обработанного чанка в dBFS. */
    val currentChunkDbfs: Double
        get() = lastChunkLevel

    /** Текущее состояние автомата детектора. */
    val currentState: DetectorState
        get() = state

    /** Сколько кандидатов стартовало за сессию. */
    val candidateCountTotal: Long
        get() = candidateCount

    /** Сколько кандидатов подтвердилось в события за сессию. */
    val confirmedEventCountTotal: Long
        get() = confirmedEventCount

    /** Сколько слабых кандидатов началось за сессию. */
    val weakCandidateCountTotal: Long
        get() = weakCandidateCount

    /** Сколько слабых кандидатов удалось завершить и отдать владельцу. */
    val weakEmittedCountTotal: Long
        get() = weakEmittedCount

    /** Сколько слабых кандидатов отброшено как слишком короткие/длинные. */
    val weakRejectedCountTotal: Long
        get() = weakRejectedCount

    val lastWeakRejectionReason: String
        get() = lastWeakRejection

    val isEventActive: Boolean
        get() = state == DetectorState.ACTIVE

    val isWarmingUp: Boolean
        get() = state == DetectorState.WARMING_UP

    /** Сколько сэмплов нужно взять из кольцевого буфера при старте события. */
    val preRollSamples: Int
        get() = config.preRollSamples

    /** Сколько сэмплов после последнего громкого чанка входит в фрагмент. */
    val postRollSamples: Int
        get() = config.postRollSamples

    /**
     * Обрабатывает один чанк PCM. Метод не создаёт объектов на горячем пути:
     * единственные аллокации — [DetectedAudioEvent] при завершении события.
     */
    fun process(
        chunk: ShortArray,
        offset: Int = 0,
        length: Int = chunk.size - offset,
    ) {
        require(offset >= 0) { "offset не может быть отрицательным" }
        require(length >= 0) { "length не может быть отрицательным" }
        require(offset <= chunk.size - length) {
            "offset + length выходит за границы массива"
        }

        if (length == 0) return

        var sumSquares = 0.0
        var peak = 0
        var index = offset
        val endIndex = offset + length

        while (index < endIndex) {
            val sample = chunk[index].toInt()
            sumSquares += sample.toDouble() * sample

            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) {
                peak = magnitude
            }

            index++
        }

        val rms = sqrt(sumSquares / length)
        val chunkDbfs = if (rms <= 0.0) {
            MIN_DBFS
        } else {
            maxOf(MIN_DBFS, 20.0 * log10(rms / FULL_SCALE))
        }
        val chunkPeakDbfs = if (peak == 0) {
            MIN_DBFS
        } else {
            maxOf(MIN_DBFS, 20.0 * log10(peak / FULL_SCALE))
        }

        lastChunkLevel = chunkDbfs

        val chunkStartMillis = clock()
        val chunkEndSample = totalSamples + length
        val chunkEndMillis = chunkStartMillis + length * 1_000L / config.sampleRate

        if (state == DetectorState.WARMING_UP) {
            warmupLevelSum += chunkDbfs * length
            warmupSamplesCollected += length
            noiseFloor = maxOf(MIN_DBFS, warmupLevelSum / warmupSamplesCollected)

            totalSamples = chunkEndSample
            if (warmupSamplesCollected >= config.warmupSamples) {
                state = DetectorState.QUIET
            }
            return
        }

        val triggerDbfs = maxOf(
            noiseFloor + config.thresholdAboveNoiseDb,
            config.absoluteFloorDbfs,
        )
        val releaseDbfs = triggerDbfs - config.hysteresisDb

        when (state) {
            DetectorState.QUIET -> {
                if (chunkDbfs > triggerDbfs) {
                    discardWeakCandidate()
                    startCandidate(chunkStartMillis, sumSquares, chunkPeakDbfs, length)
                    if (candidateSamples >= config.minEventSamples) {
                        confirmEvent(chunkEndSample, chunkEndMillis)
                    }
                } else if (config.weakEnabled) {
                    processWeak(
                        chunkDbfs = chunkDbfs,
                        sumSquares = sumSquares,
                        chunkPeakDbfs = chunkPeakDbfs,
                        length = length,
                        chunkStartMillis = chunkStartMillis,
                        chunkEndSample = chunkEndSample,
                        chunkEndMillis = chunkEndMillis,
                    )
                } else {
                    updateNoiseFloor(chunkDbfs)
                }
            }

            DetectorState.CANDIDATE -> {
                if (chunkDbfs > triggerDbfs) {
                    candidateSamples += length
                    candidateSumSquares += sumSquares
                    if (chunkPeakDbfs > candidatePeakDbfs) {
                        candidatePeakDbfs = chunkPeakDbfs
                    }
                    if (candidateSamples >= config.minEventSamples) {
                        confirmEvent(chunkEndSample, chunkEndMillis)
                    }
                } else {
                    state = DetectorState.QUIET
                    clearCandidate()
                    updateNoiseFloor(chunkDbfs)
                }
            }

            DetectorState.ACTIVE -> {
                if (chunkDbfs >= releaseDbfs) {
                    quietSamples = 0
                    eventLoudSamples += length
                    eventSumSquares += sumSquares
                    if (chunkPeakDbfs > eventPeakDbfs) {
                        eventPeakDbfs = chunkPeakDbfs
                    }
                    lastLoudSample = chunkEndSample
                    lastLoudMillis = chunkEndMillis

                    if (lastLoudSample - eventStartSample >= config.maxEventSamples) {
                        val boundarySample = lastLoudSample
                        val boundaryMillis = lastLoudMillis

                        finishEvent(truncated = true)
                        startContinuation(boundarySample, boundaryMillis)
                    }
                } else {
                    quietSamples += length
                    if (quietSamples > config.mergeGapSamples) {
                        finishEvent(truncated = false)
                    }
                }
            }

            DetectorState.WARMING_UP -> Unit
        }

        totalSamples = chunkEndSample
    }

    /**
     * Принудительно завершает активное событие. Используется при остановке
     * мониторинга, чтобы не потерять уже накопленный звук.
     */
    fun flush(): DetectedAudioEvent? {
        if (state == DetectorState.CANDIDATE) {
            state = DetectorState.QUIET
            clearCandidate()
            discardWeakCandidate()
            return null
        }

        if (state != DetectorState.ACTIVE) {
            finishWeakCandidate(truncated = false)
            return null
        }

        return finishEvent(truncated = false)
    }

    /** Сбрасывает состояние детектора для новой сессии. */
    fun reset() {
        state = if (config.warmupSamples > 0) {
            DetectorState.WARMING_UP
        } else {
            DetectorState.QUIET
        }
        noiseFloor = config.absoluteFloorDbfs
        lastChunkLevel = MIN_DBFS
        warmupSamplesCollected = 0L
        warmupLevelSum = 0.0
        totalSamples = 0L
        quietSamples = 0L
        candidateCount = 0L
        confirmedEventCount = 0L
        weakCandidateCount = 0L
        weakEmittedCount = 0L
        weakRejectedCount = 0L
        lastWeakRejection = ""
        clearCandidate()
        clearEvent()
        clearWeakCandidate()
        weakBlockedUntilQuiet = false
    }

    private fun startCandidate(
        chunkStartMillis: Long,
        sumSquares: Double,
        peakDbfs: Double,
        length: Int,
    ) {
        state = DetectorState.CANDIDATE
        candidateCount += 1
        candidateStartSample = totalSamples
        candidateStartMillis = chunkStartMillis
        candidateSamples = length.toLong()
        candidateSumSquares = sumSquares
        candidatePeakDbfs = peakDbfs
    }

    private fun confirmEvent(chunkEndSample: Long, chunkEndMillis: Long) {
        state = DetectorState.ACTIVE
        confirmedEventCount += 1
        eventStartSample = candidateStartSample
        eventStartMillis = candidateStartMillis
        eventLoudSamples = candidateSamples
        eventSumSquares = candidateSumSquares
        eventPeakDbfs = candidatePeakDbfs
        lastLoudSample = chunkEndSample
        lastLoudMillis = chunkEndMillis
        quietSamples = 0L
        eventIsContinuation = false

        clearCandidate()

        listener?.onEventStarted(eventStartMillis, eventStartSample)
    }

    private fun startContinuation(startSample: Long, startMillis: Long) {
        state = DetectorState.ACTIVE
        eventStartSample = startSample
        eventStartMillis = startMillis
        eventLoudSamples = 0L
        eventSumSquares = 0.0
        eventPeakDbfs = MIN_DBFS
        lastLoudSample = startSample
        lastLoudMillis = startMillis
        quietSamples = 0L
        eventIsContinuation = true
        clearCandidate()

        listener?.onEventStarted(startMillis, startSample)
    }

    private fun finishEvent(truncated: Boolean): DetectedAudioEvent? {
        if (state != DetectorState.ACTIVE) return null

        val durationSamples = lastLoudSample - eventStartSample
        val loudSamples = eventLoudSamples
        val continuation = eventIsContinuation

        state = DetectorState.QUIET
        quietSamples = 0L

        // Хвост после лимита 60 с может быть короче minEventMs или вообще
        // не содержать громких сэмплов, если событие закончилось ровно на
        // границе. Это всё ещё часть уже подтверждённого события, поэтому
        // его нельзя проглатывать: иначе recorder оставит файл открытым.
        if (!continuation && (durationSamples < config.minEventSamples || loudSamples <= 0L)) {
            clearEvent()
            return null
        }

        val meanSquare = if (loudSamples <= 0L) {
            0.0
        } else {
            eventSumSquares / (loudSamples.toDouble() * FULL_SCALE_SQUARE)
        }
        val averageDbfs = if (meanSquare <= 0.0) {
            MIN_DBFS
        } else {
            maxOf(MIN_DBFS, 10.0 * log10(meanSquare))
        }

        val event = DetectedAudioEvent(
            startedAtMillis = eventStartMillis,
            endedAtMillis = lastLoudMillis,
            durationMs = durationSamples * 1_000L / config.sampleRate,
            peakDbfs = eventPeakDbfs,
            averageDbfs = averageDbfs,
            truncated = truncated,
            startSample = eventStartSample,
            endSample = lastLoudSample,
        )

        clearEvent()
        listener?.onEventFinished(event)

        return event
    }

    private fun processWeak(
        chunkDbfs: Double,
        sumSquares: Double,
        chunkPeakDbfs: Double,
        length: Int,
        chunkStartMillis: Long,
        chunkEndSample: Long,
        chunkEndMillis: Long,
    ) {
        val trigger = maxOf(
            noiseFloor + config.weakThresholdAboveNoiseDb,
            config.weakAbsoluteFloorDbfs,
        )
        val release = trigger - config.hysteresisDb

        if (weakBlockedUntilQuiet) {
            if (chunkDbfs <= release) {
                weakBlockedUntilQuiet = false
                updateNoiseFloor(chunkDbfs)
            }
            return
        }

        if (weakCandidateStartSample < 0L) {
            if (chunkDbfs > trigger) {
                weakCandidateStartSample = totalSamples
                weakCandidateStartMillis = chunkStartMillis
                weakCandidateSamples = length.toLong()
                weakCandidateSumSquares = sumSquares
                weakCandidatePeakDbfs = chunkPeakDbfs
                lastWeakLoudSample = chunkEndSample
                lastWeakLoudMillis = chunkEndMillis
                weakQuietSamples = 0L
                weakCandidateCount += 1
            } else {
                updateNoiseFloor(chunkDbfs)
            }
            return
        }

        if (chunkDbfs >= release) {
            weakCandidateSamples += length
            weakCandidateSumSquares += sumSquares
            if (chunkPeakDbfs > weakCandidatePeakDbfs) {
                weakCandidatePeakDbfs = chunkPeakDbfs
            }
            lastWeakLoudSample = chunkEndSample
            lastWeakLoudMillis = chunkEndMillis
            weakQuietSamples = 0L

            if (weakCandidateSamples >= config.weakMaxEventSamples) {
                finishWeakCandidate(truncated = true)
                weakBlockedUntilQuiet = true
            }
        } else {
            weakQuietSamples += length
            if (weakQuietSamples > config.weakMergeGapSamples) {
                finishWeakCandidate(truncated = false)
            }
        }
    }

    private fun finishWeakCandidate(truncated: Boolean) {
        if (weakCandidateStartSample < 0L) return

        val durationSamples = lastWeakLoudSample - weakCandidateStartSample
        val loudSamples = weakCandidateSamples
        val startSample = weakCandidateStartSample
        val startMillis = weakCandidateStartMillis
        val endSample = lastWeakLoudSample
        val endMillis = lastWeakLoudMillis
        val peakDbfs = weakCandidatePeakDbfs
        val sumSquares = weakCandidateSumSquares

        clearWeakCandidate()

        if (durationSamples < config.weakMinEventSamples || loudSamples <= 0L) {
            weakRejectedCount += 1
            lastWeakRejection = "weak-too-short"
            return
        }

        val meanSquare = weakCandidateMeanSquare(sumSquares, loudSamples)
        val averageDbfs = if (meanSquare <= 0.0) {
            MIN_DBFS
        } else {
            maxOf(MIN_DBFS, 10.0 * log10(meanSquare))
        }

        weakEmittedCount += 1
        listener?.onWeakCandidate(
            DetectedAudioEvent(
                startedAtMillis = startMillis,
                endedAtMillis = endMillis,
                durationMs = durationSamples * 1_000L / config.sampleRate,
                peakDbfs = peakDbfs,
                averageDbfs = averageDbfs,
                truncated = truncated,
                startSample = startSample,
                endSample = endSample,
                eventClass = RecordingEventMetadata.EVENT_CLASS_WEAK,
                triggerReason = "weak-threshold",
            ),
        )
    }

    private fun weakCandidateMeanSquare(sumSquares: Double, loudSamples: Long): Double {
        if (loudSamples <= 0L) return 0.0
        return sumSquares / (loudSamples.toDouble() * FULL_SCALE_SQUARE)
    }

    private fun discardWeakCandidate() {
        clearWeakCandidate()
        weakBlockedUntilQuiet = false
    }

    private fun clearWeakCandidate() {
        weakCandidateStartSample = -1L
        weakCandidateStartMillis = 0L
        weakCandidateSamples = 0L
        weakCandidateSumSquares = 0.0
        weakCandidatePeakDbfs = MIN_DBFS
        lastWeakLoudSample = 0L
        lastWeakLoudMillis = 0L
        weakQuietSamples = 0L
    }

    private fun updateNoiseFloor(chunkDbfs: Double) {
        noiseFloor += config.noiseFloorAlpha * (chunkDbfs - noiseFloor)
        if (noiseFloor < MIN_DBFS) {
            noiseFloor = MIN_DBFS
        } else if (noiseFloor > 0.0) {
            noiseFloor = 0.0
        }
    }

    private fun clearCandidate() {
        candidateStartSample = 0L
        candidateStartMillis = 0L
        candidateSamples = 0L
        candidateSumSquares = 0.0
        candidatePeakDbfs = MIN_DBFS
    }

    private fun clearEvent() {
        eventStartSample = 0L
        eventStartMillis = 0L
        eventLoudSamples = 0L
        eventSumSquares = 0.0
        eventPeakDbfs = MIN_DBFS
        lastLoudSample = 0L
        lastLoudMillis = 0L
        eventIsContinuation = false
    }

    companion object {
        const val MIN_DBFS = -120.0

        private const val FULL_SCALE = 32_768.0
        private const val FULL_SCALE_SQUARE = FULL_SCALE * FULL_SCALE
    }
}
