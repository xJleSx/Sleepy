package com.sleepy.app.sleepy.dataset

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class DatasetPackageDeleter {

    fun deleteParticipant(packageRoot: File, participantId: String): DatasetDeletionResult {
        return try {
            deleteParticipantInternal(packageRoot, participantId)
        } catch (failure: DeletionFailure) {
            DatasetDeletionResult.Failure(failure.code, failure.message, failure.details)
        } catch (error: Exception) {
            DatasetDeletionResult.Failure(
                code = "DELETE_FAILED",
                message = error.message ?: "Не удалось удалить участника из пакета",
                details = listOf(error.javaClass.simpleName),
            )
        }
    }

    private fun deleteParticipantInternal(
        packageRoot: File,
        participantId: String,
    ): DatasetDeletionResult {
        if (!PARTICIPANT_ID.matches(participantId)) {
            fail("INVALID_PARTICIPANT_ID", "participant_id должен иметь формат ptc_*")
        }

        val root = packageRoot.canonicalFile
        if (!root.isDirectory) {
            fail("PACKAGE_MISSING", "Каталог пакета не найден")
        }

        val manifestFile = File(root, MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) {
            fail("MANIFEST_MISSING", "В пакете отсутствует manifest.json")
        }

        val manifest = readObject(manifestFile, "INVALID_MANIFEST")
        if (manifest["dataset_id"]?.asText() != DATASET_ID) {
            fail("INVALID_MANIFEST", "manifest.json относится к другому датасету")
        }
        if (manifest["frozen"]?.let { it is JsonValue.BooleanValue && it.value } == true) {
            fail("PACKAGE_FROZEN", "Запрещено изменять замороженный пакет")
        }

        val entries = readManifestEntries(root, manifest)
        verifyPackageIntegrity(root, entries)

        val sessions = readSessions(root, entries)
        val recordings = readRecordings(root, entries)
        val participantFile = "participants/$participantId.json"
        val audioPrefix = "audio/event_fragments/$participantId/"
        val participantSessions = sessions.filterValues { it.participantId == participantId }
        val participantRecordings = recordings.filterValues { recording ->
            recording.sessionId in participantSessions.keys
        }
        val hasParticipantData = participantFile in entries ||
            entries.keys.any { it.startsWith(audioPrefix) } ||
            participantSessions.isNotEmpty()
        if (!hasParticipantData) {
            fail("PARTICIPANT_NOT_FOUND", "В пакете нет данных участника $participantId")
        }

        val remainingSessions = sessions.filterKeys { it !in participantSessions.keys }
        val deletedDeviceIds = participantSessions.values.map { it.deviceId }.toSet()
        val remainingDeviceIds = remainingSessions.values.map { it.deviceId }.toSet()
        val removableDeviceIds = deletedDeviceIds - remainingDeviceIds

        val toDelete = mutableSetOf<String>()
        toDelete += participantFile
        toDelete += entries.keys.filter { it.startsWith(audioPrefix) }
        participantSessions.keys.forEach { sessionId ->
            toDelete += "sessions/$sessionId.json"
            toDelete += "qc/$sessionId.qc.json"
        }
        participantRecordings.keys.forEach { recordingId ->
            toDelete += "recordings/$recordingId.json"
        }
        removableDeviceIds.forEach { deviceId ->
            toDelete += "devices/$deviceId.json"
        }
        toDelete.removeAll { it !in entries }

        val deleted = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        toDelete.sorted().forEach { relative ->
            val file = resolveInside(root, relative)
            if (file.isFile && file.delete()) {
                deleted += relative
            } else if (file.exists()) {
                warnings += "Не удалось удалить $relative"
            }
        }
        cleanupEmptyDirectories(root, deleted)
        writeSafeReadme(root)

        val remainingEntries = entries.filterKeys { it !in toDelete }
        val remainingParticipantIds = manifest.stringList("participant_ids")
            .filterNot { it == participantId }
        val remainingSessionIds = manifest.stringList("session_ids")
            .filterNot { it in participantSessions.keys }
        val remainingRecordingIds = manifest.stringList("recording_ids")
            .filterNot { it in participantRecordings.keys }
        val remainingConsentScopes = remainingParticipantIds
            .flatMap { id -> participantScopes(root, remainingEntries, id) }
            .distinct()
            .sorted()
        val allRecordingsHaveDetectorMetadata = remainingRecordingIds.all { id ->
            recordings[id]?.hasDetectorMetadata == true
        }

        val updated = JsonObject()
        manifest.fields.forEach { (key, value) ->
            if (key !in REPLACED_MANIFEST_KEYS) {
                updated.put(key, value)
            }
        }
        updated
            .putStrings("participant_ids", remainingParticipantIds)
            .putStrings("session_ids", remainingSessionIds)
            .putStrings("recording_ids", remainingRecordingIds)
            .put("detector_metadata_complete", allRecordingsHaveDetectorMetadata)
            .putStrings("consent_scopes", remainingConsentScopes)

        val files = allFiles(root)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .sortedBy { relativePath(root, it) }
            .map { file ->
                JsonObject()
                    .put("path", relativePath(root, file))
                    .put("size_bytes", file.length())
                    .put("sha256", sha256(file))
            }
        val fileArray = JsonArray()
        files.forEach { fileArray.add(it) }
        updated.put("files", fileArray.build())

        writeManifest(manifestFile, updated)
        val integrityOk = verifyRemainingPackage(root, updated.build(), warnings)
        if (deleted.isEmpty()) {
            warnings += "Ни один файл не был удалён"
        }
        if (removableDeviceIds.isNotEmpty()) {
            warnings += "Удалены device-метаданные, не используемые другими сессиями."
        }

        return DatasetDeletionResult.Success(
            deletedFiles = deleted.sorted(),
            remainingFiles = files.size,
            integrityOk = integrityOk && warnings.none { it.startsWith("Не удалось удалить") },
            warnings = warnings,
        )
    }

    private fun readManifestEntries(
        root: File,
        manifest: JsonValue.ObjectValue,
    ): Map<String, ManifestEntry> {
        val files = manifest["files"]?.asArray()
            ?: fail("INVALID_MANIFEST", "manifest.json не содержит списка files")
        val entries = linkedMapOf<String, ManifestEntry>()
        files.forEach { value ->
            val objectValue = value as? JsonValue.ObjectValue
                ?: fail("INVALID_MANIFEST", "Элемент files должен быть объектом")
            val path = objectValue["path"]?.asText()
                ?: fail("INVALID_MANIFEST", "У файла отсутствует path")
            val size = objectValue["size_bytes"]?.asLong()
                ?: fail("INVALID_MANIFEST", "У файла отсутствует size_bytes")
            val sha = objectValue["sha256"]?.asText()
                ?: fail("INVALID_MANIFEST", "У файла отсутствует sha256")
            if (path in entries) {
                fail("INVALID_MANIFEST", "Повторяющийся путь в manifest.json: $path")
            }
            entries[path] = ManifestEntry(path, size, sha)
        }
        return entries
    }

    private fun verifyPackageIntegrity(
        root: File,
        entries: Map<String, ManifestEntry>,
    ) {
        val diskFiles = allFiles(root)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .associateBy { relativePath(root, it) }
        val missing = entries.keys - diskFiles.keys
        val extra = diskFiles.keys - entries.keys
        if (missing.isNotEmpty()) {
            fail("PACKAGE_INTEGRITY_FAILED", "В пакете отсутствуют файлы из manifest.json", missing.sorted())
        }
        if (extra.isNotEmpty()) {
            fail("PACKAGE_INTEGRITY_FAILED", "В пакете есть файлы вне manifest.json", extra.sorted())
        }
        entries.values.forEach { entry ->
            val file = diskFiles[entry.path]
                ?: fail("PACKAGE_INTEGRITY_FAILED", "Файл не найден: ${entry.path}")
            if (file.length() != entry.size) {
                fail("PACKAGE_INTEGRITY_FAILED", "Размер не совпадает: ${entry.path}")
            }
            if (sha256(file) != entry.sha256) {
                fail("PACKAGE_INTEGRITY_FAILED", "SHA-256 не совпадает: ${entry.path}")
            }
        }
    }

    private fun verifyRemainingPackage(
        root: File,
        manifest: JsonValue.ObjectValue,
        warnings: MutableList<String>,
    ): Boolean {
        val entries = try {
            readManifestEntries(root, manifest)
        } catch (failure: DeletionFailure) {
            warnings += failure.message
            return false
        }
        val diskFiles = allFiles(root)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .associateBy { relativePath(root, it) }
        if (entries.keys != diskFiles.keys) {
            warnings += "Список файлов manifest.json не совпадает с пакетом"
            return false
        }
        return entries.values.all { entry ->
            val file = diskFiles[entry.path] ?: return@all false
            file.length() == entry.size && sha256(file) == entry.sha256
        }
    }

    private fun readSessions(
        root: File,
        entries: Map<String, ManifestEntry>,
    ): Map<String, SessionInfo> {
        return entries.keys
            .filter { it.startsWith("sessions/") && it.endsWith(".json") }
            .associate { relative ->
                val json = readObject(resolveInside(root, relative), "INVALID_SESSION_JSON")
                val sessionId = json["session_id"]?.asText()
                    ?: fail("INVALID_SESSION_JSON", "В $relative отсутствует session_id")
                sessionId to SessionInfo(
                    participantId = json["participant_id"]?.asText()
                        ?: fail("INVALID_SESSION_JSON", "В $relative отсутствует participant_id"),
                    deviceId = json["device_id"]?.asText()
                        ?: fail("INVALID_SESSION_JSON", "В $relative отсутствует device_id"),
                )
            }
    }

    private fun readRecordings(
        root: File,
        entries: Map<String, ManifestEntry>,
    ): Map<String, RecordingInfo> {
        return entries.keys
            .filter { it.startsWith("recordings/") && it.endsWith(".json") }
            .associate { relative ->
                val json = readObject(resolveInside(root, relative), "INVALID_RECORDING_JSON")
                val recordingId = json["recording_id"]?.asText()
                    ?: fail("INVALID_RECORDING_JSON", "В $relative отсутствует recording_id")
                val hasDetector = json["detector_start_sample"] != null &&
                    json["detector_end_sample"] != null
                recordingId to RecordingInfo(
                    sessionId = json["session_id"]?.asText()
                        ?: fail("INVALID_RECORDING_JSON", "В $relative отсутствует session_id"),
                    hasDetectorMetadata = hasDetector,
                )
            }
    }

    private fun participantScopes(
        root: File,
        entries: Map<String, ManifestEntry>,
        participantId: String,
    ): List<String> {
        val relative = "participants/$participantId.json"
        if (relative !in entries) return emptyList()
        val participant = readObject(resolveInside(root, relative), "INVALID_PARTICIPANT_JSON")
        val consent = participant["consent"] as? JsonValue.ObjectValue ?: return emptyList()
        return consent["scopes"]?.asArray()?.mapNotNull { it.asText() } ?: emptyList()
    }

    private fun readObject(file: File, code: String): JsonValue.ObjectValue {
        if (!file.isFile) {
            fail(code, "Файл не найден: ${file.name}")
        }
        val parsed = try {
            DatasetJson.parse(file.readText(Charsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            fail(code, "Некорректный JSON ${file.name}: ${error.message}")
        }
        return parsed as? JsonValue.ObjectValue
            ?: fail(code, "Ожидался JSON-объект: ${file.name}")
    }

    private fun JsonValue.ObjectValue.stringList(key: String): List<String> =
        this[key]?.asArray()?.mapNotNull { it.asText() } ?: emptyList()

    private fun resolveInside(root: File, relative: String): File {
        if (relative.isBlank() || relative.startsWith("/") || relative.startsWith("\\")) {
            fail("UNSAFE_MANIFEST_PATH", "Недопустимый путь в manifest.json")
        }
        val file = File(root, relative).canonicalFile
        val rootPath = root.canonicalPath
        if (file.path != rootPath && !file.path.startsWith(rootPath + File.separator)) {
            fail("UNSAFE_MANIFEST_PATH", "Путь выходит за пределы пакета: $relative")
        }
        if (file == root) {
            fail("UNSAFE_MANIFEST_PATH", "Путь указывает на корень пакета")
        }
        return file
    }

    private fun writeManifest(file: File, value: JsonObject) {
        file.writeText(DatasetJson.write(value.build()) + "\n", Charsets.UTF_8)
    }

    private fun writeSafeReadme(root: File) {
        val text = buildString {
            appendLine("# Sleepy Sleep-Domain Dataset 0.1")
            appendLine()
            appendLine("Локальный экспорт Sleepy. Пакет не является публичным релизом.")
            appendLine()
            appendLine("## Удаление")
            appendLine()
            appendLine(
                "Удаление участника удаляет только копии внутри пакета и не затрагивает " +
                    "исходные записи Sleepy. README пересобран без идентификаторов участников.",
            )
        }
        File(root, "README.md").writeText(text, Charsets.UTF_8)
    }

    private fun cleanupEmptyDirectories(root: File, deleted: List<String>) {
        val directories = deleted
            .mapNotNull { resolveInside(root, it).parentFile }
            .distinct()
            .sortedByDescending { it.path.length }
        directories.forEach { directory ->
            var current: File? = directory
            while (current != null && current != root && current.isDirectory) {
                val children = current.listFiles()
                if (children == null || children.isNotEmpty()) break
                if (!current.delete()) break
                current = current.parentFile
            }
        }
    }

    private fun allFiles(directory: File): List<File> {
        val result = mutableListOf<File>()
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                result += allFiles(file)
            } else {
                result += file
            }
        }
        return result
    }

    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun fail(code: String, message: String, details: List<String> = emptyList()): Nothing {
        throw DeletionFailure(code, message, details)
    }

    private data class ManifestEntry(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    private data class SessionInfo(
        val participantId: String,
        val deviceId: String,
    )

    private data class RecordingInfo(
        val sessionId: String,
        val hasDetectorMetadata: Boolean,
    )

    private class DeletionFailure(
        val code: String,
        override val message: String,
        val details: List<String>,
    ) : Exception(message)

    private companion object {
        const val DATASET_ID = "sleepy-sleep-domain-0.1"
        const val MANIFEST_FILE_NAME = "manifest.json"
        val PARTICIPANT_ID = Regex("^ptc_[A-Za-z0-9][A-Za-z0-9._-]*$")
        val REPLACED_MANIFEST_KEYS = setOf(
            "participant_ids",
            "session_ids",
            "recording_ids",
            "detector_metadata_complete",
            "consent_scopes",
            "files",
        )
    }
}
