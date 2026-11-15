package com.sleepy.app.sleepy.audio

/**
 * Кольцевой буфер PCM-сэмплов (PCM 16 бит, моно).
 *
 * Буфер не потокобезопасен намеренно: им владеет ровно один поток —
 * поток захвата звука. Запись чанка, анализ и копирование pre-roll
 * выполняются последовательно в этом потоке, поэтому синхронизация
 * не нужна и не создаёт задержек на горячем пути.
 *
 * Пока буфер не заполнен, хранятся только реально записанные сэмплы.
 * После заполнения новые данные перезаписывают самые старые.
 */
class AudioRingBuffer(val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) {
            "Вместимость кольцевого буфера должна быть больше нуля"
        }
    }

    private val samples = ShortArray(capacitySamples)

    private var writeIndex = 0

    private var storedSamples = 0

    /** Сколько сэмплов реально записано (не больше вместимости). */
    fun availableSamples(): Int = storedSamples

    val isFull: Boolean
        get() = storedSamples == capacitySamples

    /** Очищает буфер и затирает старые аудиоданные. */
    fun clear() {
        samples.fill(0)
        writeIndex = 0
        storedSamples = 0
    }

    /**
     * Записывает часть [source] длиной [length], начиная со смещения [offset].
     *
     * Если [length] больше вместимости буфера, сохраняются только последние
     * [capacitySamples] сэмплов: для анализа важнее свежий звук.
     */
    fun write(
        source: ShortArray,
        offset: Int = 0,
        length: Int = source.size - offset,
    ) {
        require(offset >= 0) { "offset не может быть отрицательным" }
        require(length >= 0) { "length не может быть отрицательным" }
        require(offset <= source.size - length) {
            "offset + length выходит за границы исходного массива"
        }

        if (length == 0) return

        if (length >= capacitySamples) {
            val tailStart = offset + length - capacitySamples
            System.arraycopy(source, tailStart, samples, 0, capacitySamples)
            writeIndex = 0
            storedSamples = capacitySamples
            return
        }

        val firstPart = minOf(length, capacitySamples - writeIndex)
        System.arraycopy(source, offset, samples, writeIndex, firstPart)

        val secondPart = length - firstPart
        if (secondPart > 0) {
            System.arraycopy(source, offset + firstPart, samples, 0, secondPart)
        }

        writeIndex += length
        if (writeIndex >= capacitySamples) {
            writeIndex -= capacitySamples
        }

        storedSamples = minOf(capacitySamples, storedSamples + length)
    }

    /**
     * Возвращает новый массив с последними [count] сэмплами в
     * хронологическом порядке (от старых к новым).
     *
     * Если в буфере меньше [count] сэмплов, длина результата будет равна
     * [availableSamples]. Аллокация здесь допустима: метод вызывается
     * только при обнаружении события, а не на каждом чанке.
     */
    fun copyLast(count: Int): ShortArray {
        require(count >= 0) { "count не может быть отрицательным" }

        val actual = minOf(count, storedSamples)
        if (actual == 0) return ShortArray(0)

        var start = writeIndex - actual
        if (start < 0) {
            start += capacitySamples
        }

        val result = ShortArray(actual)
        val firstPart = minOf(actual, capacitySamples - start)
        System.arraycopy(samples, start, result, 0, firstPart)

        val secondPart = actual - firstPart
        if (secondPart > 0) {
            System.arraycopy(samples, 0, result, firstPart, secondPart)
        }

        return result
    }
}
