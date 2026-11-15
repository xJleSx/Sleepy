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
    val preRollMs: Int = 8_000,
    val postRollMs: Int = 8_000,
    val minEventMs: Int = 300,
    val mergeGapMs: Int = 2_000,
    val maxEventSeconds: Int = 60,
    val thresholdAboveNoiseDb: Double = 12.0,
    val hysteresisDb: Double = 6.0,
    val absoluteFloorDbfs: Double = -45.0,
    val noiseFloorAlpha: Double = 0.02,
    val minFreeSpaceMb: Long = 200,
    val fullRecordingEnabled: Boolean = false,
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
        require(minFreeSpaceMb >= 0) { "minFreeSpaceMb не может быть отрицательным" }
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

    val postRollChunks: Int = (postRollMs + chunkMs - 1) / chunkMs

    val mergeGapChunks: Int = (mergeGapMs + chunkMs - 1) / chunkMs

    companion object {
        val DEFAULT: RecordingConfig = RecordingConfig()
    }
}
