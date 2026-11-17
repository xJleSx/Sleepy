package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Единая проверка WAV-фрагментов.
 *
 * Каталог событий, счётчик фрагментов при старте сессии и статистика должны
 * описывать одни и те же файлы, поэтому все они используют этот объект.
 *
 * Фрагмент считается валидным, только если:
 * - файл содержит заголовок RIFF/WAVE;
 * - формат — PCM 16 бит, моно, 16 кГц (как пишет приложение);
 * - чанк `data` непустой;
 * - размер аудиоданных в заголовке совпадает с фактическим размером файла:
 *   недописанный, обрезанный или «файл из одного заголовка» не считается
 *   сохранённым фрагментом.
 */
internal object WavFileValidator {

    private const val FORMAT_PCM = 1
    private const val EXPECTED_CHANNELS = 1
    private const val EXPECTED_SAMPLE_RATE = 16_000
    private const val EXPECTED_BITS_PER_SAMPLE = 16
    private const val EXPECTED_BLOCK_ALIGN = 2
    private const val EXPECTED_BYTE_RATE = 32_000L
    private const val HEADER_SCAN_LIMIT = 1L shl 20
    private const val MIN_DATA_BYTES = 2L

    enum class Status {
        VALID,
        EMPTY,
        CORRUPTED,
        FAILED,
    }

    data class Validation(
        val status: Status,
        val info: WavInfo? = null,
        val reason: String = "",
    )

    data class WavInfo(
        val dataBytes: Long,
        val fileSize: Long,
    ) {
        val durationMs: Long
            get() = dataBytes * 1_000L / EXPECTED_BYTE_RATE
    }

    /**
     * Возвращает описание валидного фрагмента или `null`, если файл пуст,
     * повреждён, недописан или имеет другой формат.
     */
    fun inspect(file: File): WavInfo? = diagnose(file).info

    /**
     * Подробная проверка для экрана диагностики.
     *
     * В отличие от [inspect], различает пустой, повреждённый и недоступный
     * файл, чтобы пользователь видел причину, а не только «файл не прошёл
     * проверку».
     */
    fun diagnose(file: File): Validation {
        val size = try {
            if (!file.isFile) {
                return Validation(Status.FAILED, reason = "Файл не найден")
            }
            file.length()
        } catch (_: SecurityException) {
            return Validation(Status.FAILED, reason = "Нет доступа к файлу")
        }

        if (size == 0L) {
            return Validation(Status.EMPTY, reason = "Файл пуст")
        }
        if (size < AudioFragmentWriter.WAV_HEADER_SIZE) {
            return Validation(
                Status.CORRUPTED,
                reason = "Файл короче WAV-заголовка",
            )
        }

        return try {
            read(file, size)
        } catch (_: IOException) {
            Validation(Status.CORRUPTED, reason = "Не удалось прочитать WAV-заголовок")
        } catch (_: SecurityException) {
            Validation(Status.FAILED, reason = "Нет доступа к файлу")
        }
    }

    private fun read(file: File, size: Long): Validation {
        RandomAccessFile(file, "r").use { raf ->
            if (readAscii(raf, 4) != "RIFF") {
                return Validation(Status.CORRUPTED, reason = "Нет заголовка RIFF")
            }

            raf.seek(4L)
            val riffSize = readLe32(raf).toLong() and 0xFFFF_FFFFL

            raf.seek(8L)
            if (readAscii(raf, 4) != "WAVE") {
                return Validation(Status.CORRUPTED, reason = "Формат не WAVE")
            }

            var byteRateValid = false
            var dataBytes = -1L
            var position = 12L

            while (position + 8L <= size && position - 12L <= HEADER_SCAN_LIMIT) {
                raf.seek(position)
                val chunkId = readAscii(raf, 4)
                val chunkSize = readLe32(raf).toLong() and 0xFFFF_FFFFL
                val bodyStart = position + 8L

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16L || bodyStart + 16L > size) {
                            return Validation(
                                Status.CORRUPTED,
                                reason = "Некорректный fmt-чанк",
                            )
                        }

                        raf.seek(bodyStart)
                        val format = readLe16(raf)
                        val channels = readLe16(raf)
                        val sampleRate = readLe32(raf)
                        val declaredByteRate = readLe32(raf)
                        val blockAlign = run {
                            raf.seek(bodyStart + 12L)
                            readLe16(raf)
                        }
                        val bitsPerSample = run {
                            raf.seek(bodyStart + 14L)
                            readLe16(raf)
                        }

                        if (
                            format != FORMAT_PCM ||
                            channels != EXPECTED_CHANNELS ||
                            sampleRate != EXPECTED_SAMPLE_RATE ||
                            bitsPerSample != EXPECTED_BITS_PER_SAMPLE ||
                            blockAlign != EXPECTED_BLOCK_ALIGN ||
                            declaredByteRate.toLong() != EXPECTED_BYTE_RATE
                        ) {
                            return Validation(
                                Status.CORRUPTED,
                                reason = "Неподдерживаемый формат WAV",
                            )
                        }

                        byteRateValid = true
                    }

                    "data" -> {
                        val available = (size - bodyStart).coerceAtLeast(0L)
                        if (chunkSize != available) {
                            return Validation(
                                Status.CORRUPTED,
                                reason = "Размер data-чанка не совпадает с файлом",
                            )
                        }

                        dataBytes = chunkSize
                        break
                    }
                }

                if (chunkSize <= 0L) {
                    break
                }

                position = bodyStart + chunkSize + (chunkSize and 1L)
            }

            if (!byteRateValid) {
                return Validation(
                    Status.CORRUPTED,
                    reason = "Не найден корректный fmt-чанк",
                )
            }
            if (dataBytes < 0L) {
                return Validation(Status.EMPTY, reason = "Нет аудиоданных")
            }
            if (dataBytes == 0L) {
                return Validation(Status.EMPTY, reason = "Нет аудиоданных")
            }
            if (dataBytes < MIN_DATA_BYTES) {
                return Validation(
                    Status.CORRUPTED,
                    reason = "Некорректный размер аудиоданных",
                )
            }
            if (dataBytes % EXPECTED_BLOCK_ALIGN != 0L) {
                return Validation(
                    Status.CORRUPTED,
                    reason = "Размер данных не выровнен по сэмплам",
                )
            }
            if (riffSize != size - 8L) {
                return Validation(
                    Status.CORRUPTED,
                    reason = "Размер RIFF-чанка не совпадает с файлом",
                )
            }

            return Validation(
                Status.VALID,
                info = WavInfo(dataBytes = dataBytes, fileSize = size),
            )
        }
    }

    private fun readAscii(raf: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        raf.readFully(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun readLe16(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        if (low < 0 || high < 0) throw IOException("Неожиданный конец файла")
        return (low and 0xFF) or ((high and 0xFF) shl 8)
    }

    private fun readLe32(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw IOException("Неожиданный конец файла")
        }

        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }
}
