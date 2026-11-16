package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Файловый уровень конвейера фрагментов: пишет WAV в отдельном потоке.
 *
 * Поток захвата звука не должен ждать диск: задержка записи напрямую
 * превращается в потерю аудио. Поэтому [AudioFragmentRecorder] только
 * копирует PCM в переиспользуемые блоки и ставит их в очередь, а вся работа
 * с [RandomAccessFile] (создание, запись, правка заголовка, `fsync`,
 * удаление) выполняется здесь, в потоке `sleepy-fragment-writer`.
 *
 * Устройство:
 * - пул блоков [PcmBlock] ограничен (по умолчанию 256 блоков по 4096
 *   сэмплов ≈ 65 секунд звука). Если пул исчерпан, [append] возвращает
 *   `false` — вызывающая сторона удаляет фрагмент, предупредив пользователя;
 * - команды обрабатываются строго по порядку: `Open` → `Data…` → `Finish`
 *   или `Abort`, затем `Stop`;
 * - заголовок WAV правится и сбрасывается на диск (`fd.sync()`) каждые
 *   5 секунд и при закрытии файла, чтобы после сбоя оставался валидный файл;
 * - проверка каталога и свободного места тоже выполняется здесь, чтобы
 *   поток захвата вообще не обращался к файловой системе;
 * - одновременно открыт не более одного файла.
 *
 * Потокобезопасность: [open], [append], [finish], [abort] и [stop]
 * вызываются только из потока захвата звука; остальное — только из
 * потока записи. Пул блоков и очередь команд рассчитаны на два потока.
 */
internal class AudioFragmentWriter(
    private val config: RecordingConfig,
    private val directory: File,
    private val listener: Listener?,
    private val openFile: (File) -> RandomAccessFile,
    private val freeSpaceProvider: (File) -> Long,
    queueBlocks: Int,
) {

    interface Listener {
        fun onFragmentSaved(fragment: SavedAudioFragment)

        /**
         * Текущий фрагмент сохранить не удалось: файл удалён или не создан.
         * [generation] позволяет вызывающей стороне понять, относится ли
         * отказ к ещё открытому фрагменту или к уже закрытому.
         */
        fun onFragmentFailed(generation: Long, message: String)

        fun onWarning(message: String)
    }

    private sealed interface Command {
        class Open(
            val generation: Long,
            val startedAtMillis: Long,
            val startSample: Long,
            val contentStartSample: Long,
        ) : Command

        class Data(val block: PcmBlock, val count: Int) : Command

        class Finish(
            val endedAtMillis: Long,
            val endSample: Long,
            val truncated: Boolean,
            val contentEndSample: Long? = null,
        ) : Command

        object Abort : Command

        object Stop : Command
    }

    private class PcmBlock(val samples: ShortArray)

    private val freeBlocks = ConcurrentLinkedQueue<PcmBlock>()

    private val commands = LinkedBlockingQueue<Command>()

    // Состояние потока захвата звука.
    private var pendingBlock: PcmBlock? = null
    private var pendingCount = 0

    // Состояние потока записи.
    private val writeBuffer = ByteArray(BLOCK_SAMPLES * config.bytesPerSample)
    private val patchBuffer = ByteArray(4)
    private val headerBuffer = ByteArray(WAV_HEADER_SIZE)
    private val stampFormat = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US)
    private var sequence = 0
    private var currentFile: RandomAccessFile? = null

    @Volatile
    private var currentPath: File? = null

    private var dataBytes = 0L
    private var samplesSinceSync = 0L
    private var samplesSinceSpaceCheck = 0L
    private var fragmentStartMillis = 0L
    private var fragmentStartSample = 0L
    private var fragmentContentStartSample = 0L
    private var fragmentGeneration = 0L
    private var failureNotified = false
    private val syncSamples = config.sampleRate.toLong() * SYNC_INTERVAL_SECONDS
    private val spaceCheckSamples = config.sampleRate.toLong()

    private val openCount = AtomicLong(0L)
    private val savedCount = AtomicLong(0L)
    private val failedCount = AtomicLong(0L)

    @Volatile
    private var lastError = ""

    @Volatile
    private var lastSavedPath = ""

    init {
        require(queueBlocks > 0) { "Количество блоков записи должно быть больше нуля" }
        repeat(queueBlocks) { freeBlocks.add(PcmBlock(ShortArray(BLOCK_SAMPLES))) }
    }

    private val thread = Thread({ run() }, THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    /** Путь текущего файла; `null`, если фрагмент не открыт. */
    val activePath: File?
        get() = currentPath

    /** Сколько раз writer пытался открыть WAV-файл. */
    val openCountTotal: Long
        get() = openCount.get()

    /** Сколько WAV-фрагментов успешно закрыто и отдано вызывающему. */
    val savedCountTotal: Long
        get() = savedCount.get()

    /** Сколько операций завершилось ошибкой (файл удалён или не создан). */
    val failedCountTotal: Long
        get() = failedCount.get()

    /** Последняя причина ошибки writer; живёт до конца сессии. */
    val lastErrorText: String
        get() = lastError

    /** Последний успешно сохранённый путь. */
    val lastSavedPathText: String
        get() = lastSavedPath

    /**
     * Открывает новый фрагмент. Возвращается сразу, файл создаётся в
     * потоке записи; данные, добавленные позже через [append], попадут
     * в него в правильном порядке.
     */
    fun open(
        generation: Long,
        startedAtMillis: Long,
        startSample: Long,
        contentStartSample: Long,
    ) {
        discardPending()
        commands.put(
            Command.Open(generation, startedAtMillis, startSample, contentStartSample),
        )
    }

    /**
     * Копирует [count] сэмплов в свободный блок и ставит их в очередь.
     *
     * Возвращает `false`, если свободных блоков нет: фрагмент нужно
     * аварийно удалить. Уже принятые в очередь данные при этом не теряются,
     * но и не спасают фрагмент — он будет удалён по команде [abort].
     */
    fun append(source: ShortArray, offset: Int, count: Int): Boolean {
        var copied = 0
        while (copied < count) {
            val block = pendingBlock ?: freeBlocks.poll()?.also {
                pendingBlock = it
                pendingCount = 0
            } ?: return false

            val part = minOf(block.samples.size - pendingCount, count - copied)
            System.arraycopy(source, offset + copied, block.samples, pendingCount, part)
            pendingCount += part
            copied += part

            if (pendingCount == block.samples.size) {
                enqueuePending()
            }
        }
        return true
    }

    /** Завершает фрагмент: дописывает буфер, правит заголовок, закрывает файл. */
    fun finish(
        endedAtMillis: Long,
        endSample: Long,
        truncated: Boolean,
        contentEndSample: Long? = null,
    ) {
        enqueuePending()
        commands.put(Command.Finish(endedAtMillis, endSample, truncated, contentEndSample))
    }

    /** Удаляет текущий фрагмент вместе с уже записанным файлом. */
    fun abort() {
        discardPending()
        commands.put(Command.Abort)
    }

    /**
     * Дописывает остаток, закрывает файл и останавливает поток записи.
     *
     * @return `true`, если поток успел завершиться за [timeoutMs].
     */
    fun stop(timeoutMs: Long): Boolean {
        enqueuePending()
        commands.put(Command.Stop)
        return try {
            thread.join(timeoutMs)
            !thread.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun enqueuePending() {
        val block = pendingBlock ?: return
        val count = pendingCount
        pendingBlock = null
        pendingCount = 0

        if (count <= 0) {
            freeBlocks.offer(block)
            return
        }
        commands.put(Command.Data(block, count))
    }

    private fun discardPending() {
        val block = pendingBlock ?: return
        pendingBlock = null
        pendingCount = 0
        freeBlocks.offer(block)
    }

    private fun run() {
        try {
            while (true) {
                val command = commands.take()
                try {
                    when (command) {
                        is Command.Open -> handleOpen(command)
                        is Command.Data -> handleData(command)
                        is Command.Finish -> handleFinish(command)
                        Command.Abort -> handleAbort()
                        Command.Stop -> {
                            handleFinish(
                                Command.Finish(
                                    endedAtMillis = fragmentStartMillis,
                                    endSample = fragmentStartSample,
                                    truncated = false,
                                ),
                            )
                        }
                    }
                } catch (_: SecurityException) {
                    // Доступ к файлу отозван (например, каталог стал
                    // недоступен): текущий фрагмент удаляем, но поток
                    // записи продолжаем, чтобы обработать Abort/Stop.
                    failCurrent("Нет доступа к файлу WAV-фрагмента")
                }

                // Stop обязан завершить поток даже после ошибки доступа.
                if (command is Command.Stop) return
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            closeCurrent()
            deleteCurrent()
        }
    }

    private fun handleOpen(command: Command.Open) {
        openCount.incrementAndGet()
        closeCurrent()
        deleteCurrent()
        failureNotified = false
        fragmentGeneration = command.generation

        if (!ensureDirectory()) {
            notifyFailure(
                "Не удалось создать каталог для фрагментов: ${directory.absolutePath}",
            )
            return
        }
        if (!hasEnoughSpace()) {
            notifyFailure(
                "Недостаточно свободного места для фрагмента " +
                    "(нужно ${config.minFreeSpaceMb} МБ свободно)",
            )
            return
        }

        var file: RandomAccessFile? = null
        var path: File? = null
        try {
            path = nextFile(command.startedAtMillis)
            file = openFile(path)
            file.setLength(0L)
            file.write(headerBytes(0L))
            file.seek(WAV_HEADER_SIZE.toLong())

            currentFile = file
            currentPath = path
            dataBytes = 0L
            samplesSinceSync = 0L
            samplesSinceSpaceCheck = 0L
            fragmentStartMillis = command.startedAtMillis
            fragmentStartSample = command.startSample
            fragmentContentStartSample = command.contentStartSample
        } catch (error: IOException) {
            try {
                file?.close()
            } catch (_: IOException) {
                // файл всё равно удаляем
            }
            path?.delete()
            notifyFailure("Не удалось создать WAV-фрагмент: ${error.message}")
        } catch (_: SecurityException) {
            try {
                file?.close()
            } catch (_: IOException) {
                // файл всё равно удаляем
            }
            path?.delete()
            notifyFailure("Нет доступа к созданию WAV-фрагмента")
        }
    }

    private fun handleData(command: Command.Data) {
        try {
            val file = currentFile ?: return

            var index = 0
            val samplesPerWrite = writeBuffer.size / config.bytesPerSample
            while (index < command.count) {
                val part = minOf(command.count - index, samplesPerWrite)
                encode(command.block.samples, index, part)
                file.write(writeBuffer, 0, part * config.bytesPerSample)
                dataBytes += part.toLong() * config.bytesPerSample
                index += part
            }

            samplesSinceSync += command.count
            if (samplesSinceSync >= syncSamples) {
                samplesSinceSync = 0L
                patchHeader(file)
                file.fd.sync()
            }

            samplesSinceSpaceCheck += command.count
            if (samplesSinceSpaceCheck >= spaceCheckSamples) {
                samplesSinceSpaceCheck = 0L
                val free = usableSpace()
                if (free in 0 until reserveBytes()) {
                    failCurrent(
                        "Недостаточно свободного места: запись фрагмента остановлена",
                    )
                }
            }
        } catch (error: IOException) {
            failCurrent("Ошибка записи фрагмента: ${error.message}")
        } finally {
            freeBlocks.offer(command.block)
        }
    }

    private fun handleFinish(command: Command.Finish) {
        val file = currentFile
        val path = currentPath
        if (file == null || path == null) {
            closeCurrent()
            deleteCurrent()
            return
        }

        val contentStartSample = fragmentContentStartSample
        var samples = dataBytes / config.bytesPerSample
        val startedAtMillis = fragmentStartMillis
        val startSample = fragmentStartSample

        val contentEndLimit = command.contentEndSample
        if (contentEndLimit != null) {
            val keep = contentEndLimit - contentStartSample
            if (keep in 1L until samples) {
                val keepBytes = keep * config.bytesPerSample
                try {
                    file.setLength(WAV_HEADER_SIZE + keepBytes)
                    dataBytes = keepBytes
                    samples = keep
                } catch (error: IOException) {
                    closeCurrent()
                    deleteCurrent()
                    notifyFailure(
                        "Ошибка при обрезке WAV-фрагмента: ${error.message}",
                    )
                    return
                } catch (_: SecurityException) {
                    closeCurrent()
                    deleteCurrent()
                    notifyFailure("Нет доступа при обрезке WAV-фрагмента")
                    return
                }
            }
        }

        try {
            if (dataBytes > 0L) {
                patchHeader(file)
                file.fd.sync()
            }
        } catch (error: IOException) {
            closeCurrent()
            deleteCurrent()
            notifyFailure(
                "Ошибка при обновлении заголовка WAV-фрагмента: ${error.message}",
            )
            return
        } catch (_: SecurityException) {
            closeCurrent()
            deleteCurrent()
            notifyFailure("Нет доступа при обновлении заголовка WAV-фрагмента")
            return
        }

        try {
            file.close()
        } catch (error: IOException) {
            closeCurrent()
            deleteCurrent()
            notifyFailure("Ошибка при закрытии WAV-фрагмента: ${error.message}")
            return
        } catch (_: SecurityException) {
            closeCurrent()
            deleteCurrent()
            notifyFailure("Нет доступа при закрытии WAV-фрагмента")
            return
        }

        currentFile = null
        currentPath = null
        dataBytes = 0L
        samplesSinceSync = 0L
        samplesSinceSpaceCheck = 0L
        fragmentStartMillis = 0L
        fragmentStartSample = 0L
        fragmentContentStartSample = 0L

        if (samples > 0L) {
            savedCount.incrementAndGet()
            lastSavedPath = path.absolutePath
            listener?.onFragmentSaved(
                SavedAudioFragment(
                    file = path,
                    startedAtMillis = startedAtMillis,
                    endedAtMillis = command.endedAtMillis,
                    durationMs = samples * 1_000L / config.sampleRate,
                    samples = samples,
                    startSample = startSample,
                    endSample = command.endSample,
                    truncated = command.truncated,
                    contentStartSample = contentStartSample,
                    contentEndSample = contentStartSample + samples,
                ),
            )
        } else {
            path.delete()
            listener?.onWarning("Пустой фрагмент удалён")
        }
    }

    private fun handleAbort() {
        closeCurrent()
        deleteCurrent()
    }

    private fun failCurrent(message: String) {
        closeCurrent()
        deleteCurrent()
        notifyFailure(message)
    }

    private fun notifyFailure(message: String) {
        if (failureNotified) return
        failureNotified = true
        failedCount.incrementAndGet()
        lastError = message
        listener?.onFragmentFailed(fragmentGeneration, message)
    }

    private fun closeCurrent() {
        val file = currentFile ?: return
        currentFile = null
        try {
            file.close()
        } catch (_: IOException) {
            // закрываем молча: файл всё равно удаляем
        }
    }

    private fun deleteCurrent() {
        val path = currentPath
        currentPath = null
        dataBytes = 0L
        samplesSinceSync = 0L
        samplesSinceSpaceCheck = 0L
        fragmentStartMillis = 0L
        fragmentStartSample = 0L
        fragmentContentStartSample = 0L
        try {
            path?.delete()
        } catch (_: SecurityException) {
            // файл останется на диске, но фрагмент уже не учитывается
        }
    }

    private fun ensureDirectory(): Boolean {
        if (directory.isDirectory) return true

        return directory.mkdirs() || directory.isDirectory
    }

    private fun hasEnoughSpace(): Boolean {
        val free = usableSpace()
        if (free < 0L) return true

        val estimate = WAV_HEADER_SIZE +
            (
                config.preRollSamples.toLong() +
                    config.maxEventSamples +
                    config.postRollSamples
                ) * config.bytesPerSample

        return free >= reserveBytes() + estimate
    }

    private fun reserveBytes(): Long = config.minFreeSpaceMb * BYTES_PER_MB

    private fun usableSpace(): Long = try {
        freeSpaceProvider(directory)
    } catch (_: SecurityException) {
        -1L
    } catch (_: RuntimeException) {
        -1L
    }

    private fun encode(source: ShortArray, sourceOffset: Int, count: Int) {
        var byteIndex = 0
        var index = 0
        while (index < count) {
            val value = source[sourceOffset + index].toInt()
            writeBuffer[byteIndex] = (value and 0xFF).toByte()
            writeBuffer[byteIndex + 1] = ((value ushr 8) and 0xFF).toByte()
            byteIndex += 2
            index++
        }
    }

    private fun patchHeader(file: RandomAccessFile) {
        putIntLe(patchBuffer, 0, (WAV_HEADER_SIZE - 8 + dataBytes).toInt())
        file.seek(4L)
        file.write(patchBuffer, 0, 4)

        putIntLe(patchBuffer, 0, dataBytes.toInt())
        file.seek(40L)
        file.write(patchBuffer, 0, 4)

        file.seek(WAV_HEADER_SIZE + dataBytes)
    }

    private fun headerBytes(dataSize: Long): ByteArray {
        val size = dataSize.toInt()
        val blockAlign = config.channels * config.bytesPerSample

        putAscii(headerBuffer, 0, "RIFF")
        putIntLe(headerBuffer, 4, WAV_HEADER_SIZE - 8 + size)
        putAscii(headerBuffer, 8, "WAVE")
        putAscii(headerBuffer, 12, "fmt ")
        putIntLe(headerBuffer, 16, 16)
        putShortLe(headerBuffer, 20, FORMAT_PCM)
        putShortLe(headerBuffer, 22, config.channels)
        putIntLe(headerBuffer, 24, config.sampleRate)
        putIntLe(headerBuffer, 28, config.bytesPerSecond)
        putShortLe(headerBuffer, 32, blockAlign)
        putShortLe(headerBuffer, 34, config.bitsPerSample)
        putAscii(headerBuffer, 36, "data")
        putIntLe(headerBuffer, 40, size)

        return headerBuffer
    }

    private fun nextFile(startedAtMillis: Long): File {
        val stamp = stampFormat.format(Date(startedAtMillis))

        var candidate: File
        do {
            sequence += 1
            val suffix = sequence.toString().padStart(3, '0')
            candidate = File(directory, "$FILE_PREFIX${stamp}_$suffix.wav")
        } while (candidate.exists())

        return candidate
    }

    companion object {
        const val BLOCK_SAMPLES = 4_096
        const val WAV_HEADER_SIZE = 44

        /** Префикс имён файлов фрагментов: `event_20260101_120000_000_001.wav`. */
        const val FILE_PREFIX = "event_"

        private const val BYTES_PER_MB = 1024L * 1024L
        private const val FILE_STAMP_PATTERN = "yyyyMMdd_HHmmss_SSS"
        private const val FORMAT_PCM = 1
        private const val SYNC_INTERVAL_SECONDS = 5L
        private const val THREAD_NAME = "sleepy-fragment-writer"

        fun putAscii(target: ByteArray, offset: Int, value: String) {
            var index = 0
            while (index < value.length) {
                target[offset + index] = value[index].code.toByte()
                index++
            }
        }

        fun putIntLe(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        fun putShortLe(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}
