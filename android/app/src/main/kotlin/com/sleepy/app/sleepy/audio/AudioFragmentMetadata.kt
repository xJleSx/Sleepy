package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

data class AudioFragmentMetadata(
    val schemaVersion: Int,
    val fileName: String,
    val sampleRateHz: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMs: Long,
    val samples: Long,
    val startSample: Long,
    val endSample: Long,
    val contentStartSample: Long,
    val contentEndSample: Long,
    val truncated: Boolean,
    val sizeBytes: Long,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val EXTENSION = "meta"

        fun sidecarFile(wavFile: File): File = File(
            wavFile.parentFile ?: File("."),
            "${wavFile.name}.$EXTENSION",
        )

        fun write(
            wavFile: File,
            fragment: SavedAudioFragment,
            sampleRateHz: Int,
        ): Boolean {
            if (
                !wavFile.isFile ||
                fragment.file.name != wavFile.name ||
                wavFile.length() != fragment.file.length()
            ) {
                return false
            }

            val target = sidecarFile(wavFile)
            val temporary = File(target.parentFile ?: File("."), "${target.name}.tmp")
            val text = buildString {
                appendLine("schema_version=$SCHEMA_VERSION")
                appendLine("file_name=${wavFile.name}")
                appendLine("sample_rate_hz=$sampleRateHz")
                appendLine("started_at_millis=${fragment.startedAtMillis}")
                appendLine("ended_at_millis=${fragment.endedAtMillis}")
                appendLine("duration_ms=${fragment.durationMs}")
                appendLine("samples=${fragment.samples}")
                appendLine("start_sample=${fragment.startSample}")
                appendLine("end_sample=${fragment.endSample}")
                appendLine("content_start_sample=${fragment.contentStartSample}")
                appendLine("content_end_sample=${fragment.contentEndSample}")
                appendLine("truncated=${fragment.truncated}")
                appendLine("size_bytes=${wavFile.length()}")
            }

            return try {
                FileOutputStream(temporary).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                if (!temporary.renameTo(target)) {
                    target.delete()
                    if (!temporary.renameTo(target)) {
                        temporary.delete()
                        return false
                    }
                }
                true
            } catch (_: IOException) {
                temporary.delete()
                false
            } catch (_: SecurityException) {
                temporary.delete()
                false
            }
        }

        fun read(wavFile: File): AudioFragmentMetadata? {
            val source = sidecarFile(wavFile)
            if (!wavFile.isFile || !source.isFile) return null

            val properties = Properties()
            try {
                source.reader(Charsets.UTF_8).use(properties::load)
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }

            return try {
                val metadata = AudioFragmentMetadata(
                    schemaVersion = properties.requiredInt("schema_version"),
                    fileName = properties.requiredText("file_name"),
                    sampleRateHz = properties.requiredInt("sample_rate_hz"),
                    startedAtMillis = properties.requiredLong("started_at_millis"),
                    endedAtMillis = properties.requiredLong("ended_at_millis"),
                    durationMs = properties.requiredLong("duration_ms"),
                    samples = properties.requiredLong("samples"),
                    startSample = properties.requiredLong("start_sample"),
                    endSample = properties.requiredLong("end_sample"),
                    contentStartSample = properties.requiredLong("content_start_sample"),
                    contentEndSample = properties.requiredLong("content_end_sample"),
                    truncated = properties.requiredBoolean("truncated"),
                    sizeBytes = properties.requiredLong("size_bytes"),
                )
                metadata.takeIf { it.isValidFor(wavFile) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun delete(wavFile: File) {
            val sidecar = sidecarFile(wavFile)
            try {
                sidecar.delete()
                File(sidecar.parentFile ?: File("."), "${sidecar.name}.tmp").delete()
            } catch (_: SecurityException) {
                // sidecar недоступен; WAV можно удалять независимо
            }
        }

        private fun AudioFragmentMetadata.isValidFor(wavFile: File): Boolean =
            schemaVersion == SCHEMA_VERSION &&
                fileName == wavFile.name &&
                sampleRateHz > 0 &&
                durationMs > 0 &&
                samples > 0 &&
                startSample >= 0 &&
                endSample >= startSample &&
                contentStartSample >= 0 &&
                contentEndSample > contentStartSample &&
                contentEndSample - contentStartSample == samples &&
                sizeBytes == wavFile.length()

        private fun Properties.requiredText(key: String): String =
            getProperty(key)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(key)

        private fun Properties.requiredLong(key: String): Long =
            getProperty(key)?.toLongOrNull()
                ?: throw IllegalArgumentException(key)

        private fun Properties.requiredInt(key: String): Int =
            getProperty(key)?.toIntOrNull()
                ?: throw IllegalArgumentException(key)

        private fun Properties.requiredBoolean(key: String): Boolean =
            getProperty(key)?.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException(key)
    }
}
