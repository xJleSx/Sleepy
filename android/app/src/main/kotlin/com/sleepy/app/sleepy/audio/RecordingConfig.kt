package com.sleepy.app.sleepy.audio

/**
 * Параметры записи и обнаружения звуковых событий.
 *
 * Все значения собраны в одном месте, чтобы конвейер записи, детектор и
 * будущие настройки (экран «Профиль») использовали одни и те же числа.
 */
data class RecordingConfig(
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val chunkMs: Int = 20,
    val ringBufferSeconds: Int = 30,
    val preRollMs: Int = 4_000,
    val postRollMs: Int = 4_000,
    val minEventMs: Int = 300,
    val mergeGapMs: Int = 2_000,
    val maxEventSeconds: Int = 60,
    val thresholdAboveNoiseDb: Double = 12.0,
    val hysteresisDb: Double = 6.0,
    val absoluteFloorDbfs: Double = -45.0,
    val noiseFloorAlpha: Double = 0.02,
    val detectorWarmupMs: Int = 1_000,
    val minFreeSpaceMb: Long = 200,
    val fullRecordingEnabled: Boolean = false,
    val profile: RecordingProfile = RecordingProfile.STANDARD,
    val weakThresholdAboveNoiseDb: Double = 0.0,
    val weakAbsoluteFloorDbfs: Double = -45.0,
    val weakMinEventMs: Int = 300,
    val weakMergeGapMs: Int = 2_000,
    val weakMaxEventSeconds: Int = 0,
    val weakCooldownMs: Int = 0,
    val weakMaxCandidatesPerSession: Int = 0,
) {
    init {
        require(sampleRate > 0) { "sampleRate должен быть больше нуля" }
        require(channels == 1) { "Поддерживается только моно" }
        require(bitsPerSample == 16) { "Поддерживается только PCM 16 бит" }
        require(chunkMs in 5..1_000) { "chunkMs должен быть в диапазоне 5..1000" }
        require(ringBufferSeconds >= 5) { "Кольцевой буфер должен вмещать минимум 5 секунд" }
        require(preRollMs >= 0) { "preRollMs не может быть отрицательным" }
        require(preRollMs <= ringBufferSeconds * 1_000) {
            "preRollMs не должен превышать размер кольцевого буфера"
        }
        require(postRollMs >= 0) { "postRollMs не может быть отрицательным" }
        require(minEventMs >= chunkMs) { "minEventMs должен быть не меньше одного чанка" }
        require(mergeGapMs >= 0) { "mergeGapMs не может быть отрицательным" }
        require(maxEventSeconds > 0) { "maxEventSeconds должен быть больше нуля" }
        require(hysteresisDb >= 0) { "hysteresisDb не может быть отрицательным" }
        require(thresholdAboveNoiseDb > hysteresisDb) {
            "Порог срабатывания должен быть выше гистерезиса"
        }
        require(noiseFloorAlpha in 0.0..1.0) { "noiseFloorAlpha должен быть в диапазоне 0..1" }
        require(detectorWarmupMs >= 0) { "detectorWarmupMs не может быть отрицательным" }
        require(minFreeSpaceMb >= 0) { "minFreeSpaceMb не может быть отрицательным" }
        require(weakThresholdAboveNoiseDb >= 0.0) {
            "weakThresholdAboveNoiseDb не может быть отрицательным"
        }
        require(weakMinEventMs >= chunkMs) {
            "weakMinEventMs должен быть не меньше одного чанка"
        }
        require(weakMergeGapMs >= 0) { "weakMergeGapMs не может быть отрицательным" }
        require(weakMaxEventSeconds >= 0) {
            "weakMaxEventSeconds не может быть отрицательным"
        }
        require(weakCooldownMs >= 0) { "weakCooldownMs не может быть отрицательным" }
        require(weakMaxCandidatesPerSession >= 0) {
            "weakMaxCandidatesPerSession не может быть отрицательным"
        }
        if (weakThresholdAboveNoiseDb > 0.0 && weakMaxEventSeconds > 0) {
            require(weakThresholdAboveNoiseDb < thresholdAboveNoiseDb) {
                "Слабый порог должен быть ниже обычного порога"
            }
            require(weakAbsoluteFloorDbfs <= absoluteFloorDbfs) {
                "Слабый абсолютный порог должен быть ниже обычного"
            }
            require(
                weakMaxEventSeconds * 1_000 + preRollMs + postRollMs <=
                    ringBufferSeconds * 1_000,
            ) {
                "Слабое событие вместе с pre/post-roll должно помещаться в кольцевой буфер"
            }
        }
    }

    val bytesPerSample: Int = bitsPerSample / 8

    val bytesPerSecond: Int = sampleRate * channels * bytesPerSample

    val chunkSamples: Int = sampleRate * chunkMs / 1_000

    val chunkBytes: Int = chunkSamples * bytesPerSample

    val ringCapacitySamples: Int = sampleRate * ringBufferSeconds

    val ringCapacityBytes: Int = ringCapacitySamples * bytesPerSample

    val preRollSamples: Int = sampleRate * preRollMs / 1_000

    val postRollSamples: Int = sampleRate * postRollMs / 1_000

    val minEventSamples: Int = sampleRate * minEventMs / 1_000

    val mergeGapSamples: Int = sampleRate * mergeGapMs / 1_000

    val maxEventSamples: Int = sampleRate * maxEventSeconds

    val minEventChunks: Int = (minEventMs + chunkMs - 1) / chunkMs

    val warmupSamples: Int = sampleRate * detectorWarmupMs / 1_000

    val postRollChunks: Int = (postRollMs + chunkMs - 1) / chunkMs

    val mergeGapChunks: Int = (mergeGapMs + chunkMs - 1) / chunkMs

    val weakEnabled: Boolean = weakThresholdAboveNoiseDb > 0.0 && weakMaxEventSeconds > 0

    val weakMinEventSamples: Int = sampleRate * weakMinEventMs / 1_000

    val weakMergeGapSamples: Int = sampleRate * weakMergeGapMs / 1_000

    val weakMaxEventSamples: Int = sampleRate * weakMaxEventSeconds

    val thresholdSummary: String =
        "normal=${thresholdAboveNoiseDb}dB_floor=${absoluteFloorDbfs}dBFS_" +
            "weak=${weakThresholdAboveNoiseDb}dB_floor=${weakAbsoluteFloorDbfs}dBFS"

    companion object {
        val DEFAULT: RecordingConfig = RecordingConfig()

        val HIGH: RecordingConfig = RecordingConfig(
            profile = RecordingProfile.HIGH,
            minEventMs = 200,
            mergeGapMs = 3_000,
            weakThresholdAboveNoiseDb = 7.0,
            weakAbsoluteFloorDbfs = -52.0,
            weakMinEventMs = 250,
            weakMergeGapMs = 2_500,
            weakMaxEventSeconds = 8,
            weakCooldownMs = 20_000,
            weakMaxCandidatesPerSession = 200,
        )

        val VERY_HIGH: RecordingConfig = RecordingConfig(
            profile = RecordingProfile.VERY_HIGH,
            minEventMs = 150,
            mergeGapMs = 3_000,
            thresholdAboveNoiseDb = 9.0,
            absoluteFloorDbfs = -50.0,
            weakThresholdAboveNoiseDb = 5.0,
            weakAbsoluteFloorDbfs = -56.0,
            weakMinEventMs = 200,
            weakMergeGapMs = 2_500,
            weakMaxEventSeconds = 8,
            weakCooldownMs = 15_000,
            weakMaxCandidatesPerSession = 250,
        )

        fun forProfile(profile: RecordingProfile): RecordingConfig = when (profile) {
            RecordingProfile.STANDARD -> DEFAULT
            RecordingProfile.HIGH -> HIGH
            RecordingProfile.VERY_HIGH -> VERY_HIGH
        }
    }
}
