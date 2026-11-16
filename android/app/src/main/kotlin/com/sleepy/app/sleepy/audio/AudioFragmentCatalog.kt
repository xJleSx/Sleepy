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
        val files = try {
            directory.listFiles() ?: return emptyList()
        } catch (_: SecurityException) {
            return emptyList()
        }

        return files
            .asSequence()
            .filter { isEventFragment(it) }
            .mapNotNull { describe(it) }
            .sortedWith(
                compareByDescending<AudioFragmentInfo> { it.startedAtMillis }
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

    private fun describe(file: File): AudioFragmentInfo? {
        val info = WavFileValidator.inspect(file) ?: return null

        return AudioFragmentInfo(
            id = file.name,
            fileName = file.name,
            filePath = file.absolutePath,
            startedAtMillis = eventTimeMillis(file),
            durationMs = info.durationMs,
            sizeBytes = info.fileSize,
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
