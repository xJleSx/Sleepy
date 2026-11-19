package com.sleepy.app.sleepy.audio

import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Описание сохранённого WAV-фрагмента для списка событий.
 *
 * [id] уникален внутри каталога (имя файла), [durationMs] считается по
 * заголовку WAV, а [startedAtMillis] — по имени файла (время начала
 * события), с запасным вариантом в виде времени изменения файла.
 */
data class AudioFragmentInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val startedAtMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val metadata: AudioFragmentMetadata? = null,
)

/**
 * Строка диагностического каталога: файл показывается независимо от того,
 * прошёл он проверку или нет. [durationMs] остаётся `null` для битых файлов,
 * чтобы UI не подменял отсутствующую длительность нулём.
 */
internal data class AudioFileDiagnostic(
    val fileName: String,
    val filePath: String,
    val startedAtMillis: Long,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val status: WavFileValidator.Status,
    val reason: String,
)

/**
 * Читает каталог `files/recordings` и отдаёт только фрагменты новой
 * системы событий (`event_*.wav`).
 *
 * Валидность файла определяется [WavFileValidator]: каталог, счётчик при
 * старте сессии и статистика используют одни и те же правила. Пустые,
 * повреждённые, недописанные и чужие файлы (например, старые
 * `sleep_*.wav`) пропускаются, исключения наружу не выходят.
 *
 * Каталог сканируется в фоновом потоке: операции с файлами не должны
 * попадать в поток захвата звука.
 */
internal object AudioFragmentCatalog {

    private const val NAME_PATTERN = "yyyyMMdd_HHmmss_SSS"
    private const val WAV_EXTENSION = "wav"

    fun list(directory: File): List<AudioFragmentInfo> {
        return diagnose(directory).mapNotNull { diagnostic ->
            val durationMs = diagnostic.durationMs ?: return@mapNotNull null
            val sizeBytes = diagnostic.sizeBytes ?: return@mapNotNull null
            if (diagnostic.status != WavFileValidator.Status.VALID) {
                return@mapNotNull null
            }

            val file = File(diagnostic.filePath)
            AudioFragmentInfo(
                id = diagnostic.fileName,
                fileName = diagnostic.fileName,
                filePath = diagnostic.filePath,
                startedAtMillis = diagnostic.startedAtMillis,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                metadata = AudioFragmentMetadata.read(file),
            )
        }
    }

    /**
     * Все WAV-фрагменты новой системы событий вместе с результатом проверки.
     *
     * В отличие от [list], возвращает и пустые, и повреждённые файлы: экран
     * диагностики должен показывать полную картину. Файлы не изменяются и не
     * удаляются.
     */
    fun diagnose(directory: File): List<AudioFileDiagnostic> {
        val files = try {
            directory.listFiles() ?: return emptyList()
        } catch (_: SecurityException) {
            return emptyList()
        }

        return files
            .asSequence()
            .filter { isEventFragment(it) }
            .map { diagnoseFile(it) }
            .sortedWith(
                compareByDescending<AudioFileDiagnostic> { it.startedAtMillis }
                    .thenByDescending { it.fileName },
            )
            .toList()
    }

    /**
     * Сколько валидных фрагментов уже лежит в каталоге.
     *
     * `-1` — каталог прочитать не удалось; вызывающая сторона сохраняет
     * прежнее значение счётчика.
     */
    fun countValid(directory: File): Int {
        val files = try {
            directory.listFiles()
        } catch (_: SecurityException) {
            return -1
        } ?: return 0

        return files.count { file ->
            isEventFragment(file) && WavFileValidator.inspect(file) != null
        }
    }

    private fun isEventFragment(file: File): Boolean = try {
        file.isFile &&
            file.name.startsWith(AudioFragmentWriter.FILE_PREFIX) &&
            file.extension.equals(WAV_EXTENSION, ignoreCase = true)
    } catch (_: SecurityException) {
        false
    }

    private fun diagnoseFile(file: File): AudioFileDiagnostic {
        val validation = WavFileValidator.diagnose(file)
        val size = validation.info?.fileSize ?: try {
            if (file.isFile) file.length() else null
        } catch (_: SecurityException) {
            null
        }

        return AudioFileDiagnostic(
            fileName = file.name,
            filePath = file.absolutePath,
            startedAtMillis = eventTimeMillis(file),
            sizeBytes = size,
            durationMs = validation.info?.durationMs,
            status = validation.status,
            reason = validation.reason,
        )
    }

    private fun eventTimeMillis(file: File): Long {
        val stamp = file.name
            .removePrefix(AudioFragmentWriter.FILE_PREFIX)
            .substringBeforeLast('.')

        val parts = stamp.split('_')
        if (parts.size >= 3) {
            val candidate = parts.take(3).joinToString("_")
            val format = SimpleDateFormat(NAME_PATTERN, Locale.US).apply {
                isLenient = false
            }

            try {
                val parsed = format.parse(candidate)
                if (parsed != null) return parsed.time
            } catch (_: ParseException) {
                // Имя нестандартное: используем время изменения файла.
            }
        }

        return try {
            file.lastModified()
        } catch (_: SecurityException) {
            0L
        }
    }
}
