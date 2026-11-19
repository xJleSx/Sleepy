package com.sleepy.app.sleepy.dataset

import com.sleepy.app.sleepy.audio.RecordingConfig
import com.sleepy.app.sleepy.audio.WavFileValidator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DatasetExportEngine {

    fun export(request: DatasetExportRequest): DatasetExportResult {
        var staging: File? = null

        return try {
            val plan = validateAndPrepare(request)
            val outputRoot = ensureOutputRoot(request.outputRoot)
            staging = createStaging(outputRoot)
            writePackage(plan, request, staging)
            finishPackage(staging, outputRoot, plan)
        } catch (failure: ExportFailure) {
            cleanupQuietly(staging)
            DatasetExportResult.Failure(failure.code, failure.message, failure.details)
        } catch (error: Exception) {
            cleanupQuietly(staging)
            DatasetExportResult.Failure(
                code = "EXPORT_FAILED",
                message = error.message ?: "Не удалось экспортировать датасет",
                details = listOf(error.javaClass.simpleName),
            )
        }
    }

    private fun validateAndPrepare(request: DatasetExportRequest): ExportPlan {
        if (request.exportedAtMillis <= 0L) {
            fail("INVALID_EXPORT_TIME", "Время экспорта должно быть положительным")
        }

        val participant = validateParticipant(request.participant)
        val device = validateDevice(request.device)
        val rawSessionId = validateSessionId(request.session.sourceSessionId)
        val datasetSessionId = datasetSessionId(rawSessionId)
        val session = validateSession(request.session, datasetSessionId)

        val sourceRoot = canonicalDirectory(request.sourceRecordingsRoot)
        val outputRoot = request.outputRoot.canonicalFile
        if (isInside(outputRoot, sourceRoot) || isInside(sourceRoot, outputRoot)) {
            fail(
                "UNSAFE_OUTPUT_ROOT",
                "Каталог экспорта не должен находиться внутри каталога записей или содержать его",
            )
        }

        if (request.recordings.isEmpty()) {
            fail("NO_RECORDINGS", "Для экспорта не передано ни одного WAV-фрагмента")
        }

        val prepared = request.recordings.map { source ->
            prepareRecording(source, request.session, datasetSessionId, sourceRoot, participant.participantId)
        }

        val duplicateIds = prepared.groupBy { it.recordingId }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            fail(
                "DUPLICATE_RECORDING_ID",
                "Один recording_id соответствует нескольким файлам",
                duplicateIds.sorted(),
            )
        }

        val duplicatePaths = prepared.groupBy { it.relativePath }.filterValues { it.size > 1 }.keys
        if (duplicatePaths.isNotEmpty()) {
            fail(
                "DUPLICATE_RECORDING_PATH",
                "Несколько записей претендуют на один относительный путь",
                duplicatePaths.sorted(),
            )
        }

        val firstRecordingStart = prepared.minOf { it.startedAtMillis }
        val lastRecordingEnd = prepared.maxOf { it.endedAtMillis }
        if (session.startedAtMillis > firstRecordingStart) {
            fail(
                "SESSION_START_AFTER_RECORDING",
                "Сессия начинается позже первого экспортируемого фрагмента",
            )
        }

        val endedAtMillis = session.endedAtMillis ?: lastRecordingEnd
        if (endedAtMillis < lastRecordingEnd) {
            fail(
                "SESSION_END_BEFORE_RECORDING",
                "Сессия заканчивается раньше последнего экспортируемого фрагмента",
            )
        }

        val warnings = buildList {
            val missingDetector = prepared.count { !it.hasDetectorMetadata }
            if (missingDetector > 0) {
                add(
                    "Для $missingDetector записей нет сохранённых detector-границ; " +
                        "они не будут выдуманы.",
                )
            }
            if (prepared.any { it.privacyReviewStatus == "not_reviewed" }) {
                add("Не все записи прошли privacy review; публикация запрещена.")
            }
            add("Человеческие аннотации событий в экспорт 0.1 не входят.")
        }

        return ExportPlan(
            participant = participant,
            device = device,
            session = session.copy(endedAtMillis = endedAtMillis),
            recordings = prepared,
            warnings = warnings,
        )
    }

    private fun validateParticipant(participant: DatasetParticipant): DatasetParticipant {
        if (!PARTICIPANT_ID.matches(participant.participantId)) {
            fail("INVALID_PARTICIPANT_ID", "participant_id должен иметь формат ptc_*")
        }
        if (participant.createdAtMillis <= 0L) {
            fail("INVALID_PARTICIPANT_TIME", "created_at участника должен быть положительным")
        }
        if (participant.consentVersion.isBlank()) {
            fail("MISSING_CONSENT_VERSION", "Не указана версия согласия")
        }
        if (participant.consentGrantedAtMillis <= 0L) {
            fail("MISSING_CONSENT_TIME", "Не указано время получения согласия")
        }
        if (participant.consentStatus != "active") {
            fail("CONSENT_NOT_ACTIVE", "Согласие не активно; экспорт запрещён")
        }

        val scopes = participant.consentScopes.distinct().sorted()
        if (scopes.isEmpty()) {
            fail("MISSING_CONSENT_SCOPE", "Согласие не содержит ни одной цели")
        }
        val invalidScopes = scopes.filterNot { it in CONSENT_SCOPES }
        if (invalidScopes.isNotEmpty()) {
            fail("INVALID_CONSENT_SCOPE", "Неизвестные цели согласия", invalidScopes)
        }
        if ("dataset_research" !in scopes) {
            fail(
                "CONSENT_SCOPE_REQUIRED",
                "Для исследовательского экспорта требуется scope dataset_research",
            )
        }
        if (participant.minorInvolved && !participant.guardianConsent) {
            fail(
                "GUARDIAN_CONSENT_REQUIRED",
                "Для несовершеннолетнего участника требуется согласие представителя",
            )
        }
        if (participant.ageGroup != null && participant.ageGroup !in AGE_GROUPS) {
            fail("INVALID_AGE_GROUP", "Неизвестная возрастная группа")
        }
        if (participant.notes.length > MAX_NOTES) {
            fail("NOTES_TOO_LONG", "notes участника длиннее $MAX_NOTES символов")
        }

        return participant.copy(consentScopes = scopes)
    }

    private fun validateDevice(device: DatasetDevice): DatasetDevice {
        if (!DEVICE_ID.matches(device.deviceId)) {
            fail("INVALID_DEVICE_ID", "device_id должен иметь формат dev_*")
        }
        if (device.createdAtMillis <= 0L) {
            fail("INVALID_DEVICE_TIME", "created_at устройства должен быть положительным")
        }
        requireText(device.manufacturer, "manufacturer", 1, 200)
        requireText(device.model, "model", 1, 200)
        requireText(device.osVersion, "os_version", 1, 100)
        requireText(device.appVersion, "app_version", 1, 100)
        if (device.microphonePosition !in MICROPHONE_POSITIONS) {
            fail("INVALID_MICROPHONE_POSITION", "Неизвестная позиция микрофона")
        }
        requireText(device.placementNotes, "placement_notes", 1, 500)
        if (device.calibrationStatus !in CALIBRATION_STATUSES) {
            fail("INVALID_CALIBRATION_STATUS", "Неизвестный статус калибровки")
        }
        if (device.notes.length > MAX_DEVICE_NOTES) {
            fail("DEVICE_NOTES_TOO_LONG", "notes устройства слишком длинные")
        }
        return device
    }

    private fun validateSessionId(raw: String): String {
        val value = raw.trim()
        if (!RAW_SESSION_ID.matches(value)) {
            fail(
                "INVALID_SESSION_ID",
                "session_id должен содержать только буквы, цифры, точку, дефис и подчёркивание",
            )
        }
        return value
    }

    private fun datasetSessionId(raw: String): String {
        val value = if (raw.startsWith("ses_")) raw else "ses_$raw"
        if (!SESSION_ID.matches(value)) {
            fail("INVALID_DATASET_SESSION_ID", "Не удалось построить корректный ses_*")
        }
        return value
    }

    private fun validateSession(
        session: DatasetSession,
        datasetSessionId: String,
    ): ExportSession {
        if (session.startedAtMillis <= 0L) {
            fail("INVALID_SESSION_START", "Сессия должна иметь положительное время начала")
        }
        if (session.endedAtMillis != null && session.endedAtMillis <= session.startedAtMillis) {
            fail("INVALID_SESSION_END", "Конец сессии должен быть позже начала")
        }
        if (session.timezone.isBlank() || session.timezone.length > 100) {
            fail("INVALID_TIMEZONE", "timezone должен быть непустой строкой")
        }
        if (session.utcOffsetMinutes !in -720..840) {
            fail("INVALID_UTC_OFFSET", "utc_offset_minutes вне допустимого диапазона")
        }
        if (session.nightIndex < 1) {
            fail("INVALID_NIGHT_INDEX", "night_index должен быть не меньше 1")
        }
        if (session.recordingMode != "event_fragments") {
            fail(
                "UNSUPPORTED_RECORDING_MODE",
                "Экспорт 0.1 поддерживает только event_fragments",
            )
        }
        if (session.roomType !in ROOM_TYPES) {
            fail("INVALID_ROOM_TYPE", "Неизвестный room_type")
        }
        if (session.sessionStatus !in SESSION_STATUSES) {
            fail("INVALID_SESSION_STATUS", "Неизвестный session_status")
        }
        if (
            session.householdAudioRisk != null &&
            session.householdAudioRisk !in HOUSEHOLD_AUDIO_RISKS
        ) {
            fail("INVALID_HOUSEHOLD_RISK", "Неизвестный household_audio_risk")
        }
        if (session.notes.length > MAX_NOTES) {
            fail("SESSION_NOTES_TOO_LONG", "notes сессии слишком длинные")
        }

        return ExportSession(
            datasetSessionId = datasetSessionId,
            startedAtMillis = session.startedAtMillis,
            endedAtMillis = session.endedAtMillis,
            timezone = session.timezone,
            utcOffsetMinutes = session.utcOffsetMinutes,
            nightIndex = session.nightIndex,
            recordingMode = session.recordingMode,
            roomType = session.roomType,
            sessionStatus = session.sessionStatus,
            householdAudioRisk = session.householdAudioRisk,
            notes = session.notes,
        )
    }

    private fun prepareRecording(
        source: DatasetRecordingSource,
        session: DatasetSession,
        datasetSessionId: String,
        sourceRoot: File,
        participantId: String,
    ): PreparedRecording {
        if (source.sourceSessionId != session.sourceSessionId) {
            fail(
                "SESSION_ID_MISMATCH",
                "Фрагмент ${source.sourceFile.name} относится к другой сессии",
            )
        }

        val file = canonicalFile(source.sourceFile)
        if (file.parentFile != sourceRoot) {
            fail(
                "SOURCE_OUTSIDE_RECORDINGS",
                "Файл ${source.sourceFile.name} находится вне каталога записей",
            )
        }
        if (!SAFE_WAV_NAME.matches(file.name)) {
            fail("UNSAFE_FILE_NAME", "Недопустимое имя WAV-файла")
        }
        if (!file.isFile || !file.canRead()) {
            fail("SOURCE_MISSING", "Файл ${file.name} не найден или недоступен")
        }

        val contentStart = source.contentStartSample
        val contentEnd = source.contentEndSample
        if (contentStart == null || contentEnd == null) {
            fail(
                "MISSING_CONTENT_BOUNDARIES",
                "Для ${file.name} нет точных content_start_sample/content_end_sample",
            )
        }
        if (contentStart < 0L || contentEnd <= contentStart) {
            fail("INVALID_CONTENT_BOUNDARIES", "Некорректные границы содержимого ${file.name}")
        }

        val validation = WavFileValidator.diagnose(file)
        if (validation.status != WavFileValidator.Status.VALID || validation.info == null) {
            fail(
                "INVALID_WAV",
                "WAV не прошёл проверку: ${file.name}",
                listOf(validation.reason.ifBlank { validation.status.name }),
            )
        }

        val info = validation.info
        val samples = info.dataBytes / 2L
        if (contentEnd - contentStart != samples) {
            fail(
                "CONTENT_SAMPLE_MISMATCH",
                "content_start_sample/content_end_sample не совпадают с числом сэмплов ${file.name}",
            )
        }

        val durationMs = samples * 1_000L / 16_000L
        if (durationMs <= 0L) {
            fail("EMPTY_AUDIO", "Файл ${file.name} не содержит аудиодлительности")
        }

        val detectorStart = source.detectorStartSample
        val detectorEnd = source.detectorEndSample
        val hasDetector = detectorStart != null && detectorEnd != null
        if ((detectorStart == null) != (detectorEnd == null)) {
            fail(
                "INCOMPLETE_DETECTOR_BOUNDARIES",
                "Для ${file.name} задана только одна detector-граница",
            )
        }
        if (detectorStart != null && detectorEnd != null) {
            if (
                detectorStart < contentStart ||
                detectorEnd > contentEnd ||
                detectorEnd <= detectorStart
            ) {
                fail(
                    "INVALID_DETECTOR_BOUNDARIES",
                    "Detector-границы выходят за содержимое ${file.name}",
                )
            }
        }
        validateDbfs(source.peakDbfs, "peak_dbfs")
        validateDbfs(source.averageDbfs, "average_dbfs")

        val privacyFlags = source.privacyFlags.distinct().sorted()
        if (privacyFlags.isEmpty()) {
            fail("MISSING_PRIVACY_FLAGS", "privacy_flags не может быть пустым")
        }
        val invalidFlags = privacyFlags.filterNot { it in PRIVACY_FLAGS }
        if (invalidFlags.isNotEmpty()) {
            fail("INVALID_PRIVACY_FLAGS", "Неизвестные privacy_flags", invalidFlags)
        }
        if (source.privacyReviewStatus !in PRIVACY_REVIEW_STATUSES) {
            fail("INVALID_PRIVACY_REVIEW_STATUS", "Неизвестный privacy_review_status")
        }
        if (source.privacyReviewStatus == "rejected") {
            fail("PRIVACY_REJECTED", "Запись ${file.name} отклонена privacy review")
        }
        if (
            source.privacyReviewStatus.startsWith("approved_") &&
            "unknown" in privacyFlags
        ) {
            fail(
                "PRIVACY_STATUS_CONFLICT",
                "Проверенная запись ${file.name} имеет privacy_flags = unknown",
            )
        }
        if (source.notes.length > MAX_NOTES) {
            fail("RECORDING_NOTES_TOO_LONG", "notes записи ${file.name} слишком длинные")
        }

        val sourceSha = sha256(file)
        val recordingId = recordingId(datasetSessionId, sourceSha, contentStart, contentEnd)
        val fileName = file.name
        val relativePath = "audio/event_fragments/" +
            "$participantId/$datasetSessionId/$fileName"

        return PreparedRecording(
            sourceFile = file,
            fileName = fileName,
            sourceSha256 = sourceSha,
            recordingId = recordingId,
            relativePath = relativePath,
            startedAtMillis = source.fileStartedAtMillis,
            endedAtMillis = source.fileStartedAtMillis + durationMs,
            durationMs = durationMs,
            sizeBytes = file.length(),
            samples = samples,
            contentStartSample = contentStart,
            contentEndSample = contentEnd,
            detectorStartSample = detectorStart,
            detectorEndSample = detectorEnd,
            peakDbfs = source.peakDbfs,
            averageDbfs = source.averageDbfs,
            isTruncated = source.isTruncated,
            hasDetectorMetadata = hasDetector,
            privacyReviewStatus = source.privacyReviewStatus,
            privacyFlags = privacyFlags,
            notes = source.notes,
        )
    }

    private fun writePackage(plan: ExportPlan, request: DatasetExportRequest, staging: File) {
        val participantId = plan.participant.participantId
        val sessionId = plan.session.datasetSessionId

        mkdirs(File(staging, "participants"))
        mkdirs(File(staging, "devices"))
        mkdirs(File(staging, "sessions"))
        mkdirs(File(staging, "recordings"))
        mkdirs(File(staging, "qc"))
        mkdirs(File(staging, "audio/event_fragments/$participantId/$sessionId"))

        writeJson(
            File(staging, "participants/$participantId.json"),
            buildParticipantJson(plan.participant),
        )
        writeJson(
            File(staging, "devices/${plan.device.deviceId}.json"),
            buildDeviceJson(plan.device),
        )
        writeJson(
            File(staging, "sessions/$sessionId.json"),
            buildSessionJson(plan.session, plan.participant, plan.device),
        )

        plan.recordings.forEach { recording ->
            writeJson(
                File(staging, "recordings/${recording.recordingId}.json"),
                buildRecordingJson(recording, sessionId),
            )

            val destination = File(staging, recording.relativePath)
            copyFile(recording.sourceFile, destination)
            val copiedSha = sha256(destination)
            if (copiedSha != recording.sourceSha256) {
                fail(
                    "CHECKSUM_MISMATCH",
                    "SHA-256 копии не совпадает с источником: ${recording.fileName}",
                )
            }
            val sourceShaAfterCopy = sha256(recording.sourceFile)
            if (sourceShaAfterCopy != recording.sourceSha256) {
                fail(
                    "SOURCE_CHANGED_DURING_EXPORT",
                    "Исходный файл изменился во время экспорта: ${recording.fileName}",
                )
            }
        }

        writeJson(
            File(staging, "qc/$sessionId.qc.json"),
            buildQcJson(plan),
        )
        writeText(File(staging, "README.md"), packageReadme(plan))
        writeManifest(staging, plan, request)
    }

    private fun buildParticipantJson(participant: DatasetParticipant): JsonObject {
        val consent = JsonObject()
            .put("consent_version", participant.consentVersion)
            .put("granted_at_utc", isoUtc(participant.consentGrantedAtMillis))
            .put("status", participant.consentStatus)
            .putStrings("scopes", participant.consentScopes)
            .put("minor_involved", participant.minorInvolved)
            .put("guardian_consent", participant.guardianConsent)

        val json = JsonObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("participant_id", participant.participantId)
            .put("created_at_utc", isoUtc(participant.createdAtMillis))
            .put("consent", consent.build())

        participant.ageGroup?.let { json.put("age_group", it) }
        if (participant.notes.isNotBlank()) json.put("notes", participant.notes)
        return json
    }

    private fun buildDeviceJson(device: DatasetDevice): JsonObject {
        val json = JsonObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("device_id", device.deviceId)
            .put("created_at_utc", isoUtc(device.createdAtMillis))
            .put("manufacturer", device.manufacturer)
            .put("model", device.model)
            .put("os_version", device.osVersion)
            .put("app_version", device.appVersion)
            .put("audio_source", "microphone")
            .put("sample_rate_hz", 16_000)
            .put("channels", 1)
            .put("bits_per_sample", 16)
            .put("microphone_position", device.microphonePosition)
            .put("placement_notes", device.placementNotes)
            .put("calibration_status", device.calibrationStatus)

        if (device.notes.isNotBlank()) json.put("notes", device.notes)
        return json
    }

    private fun buildSessionJson(
        session: ExportSession,
        participant: DatasetParticipant,
        device: DatasetDevice,
    ): JsonObject {
        val endedAtMillis = session.endedAtMillis
            ?: fail("INTERNAL_SESSION_END", "Не удалось определить конец сессии")
        val config = RecordingConfig.DEFAULT
        val detector = JsonObject()
            .put("sample_rate_hz", config.sampleRate)
            .put("channels", config.channels)
            .put("bits_per_sample", config.bitsPerSample)
            .put("chunk_ms", config.chunkMs)
            .put("ring_buffer_seconds", config.ringBufferSeconds)
            .put("pre_roll_ms", config.preRollMs)
            .put("post_roll_ms", config.postRollMs)
            .put("min_event_ms", config.minEventMs)
            .put("merge_gap_ms", config.mergeGapMs)
            .put("max_event_seconds", config.maxEventSeconds)
            .put("threshold_above_noise_db", config.thresholdAboveNoiseDb)
            .put("hysteresis_db", config.hysteresisDb)
            .put("absolute_floor_dbfs", config.absoluteFloorDbfs)
            .put("noise_floor_alpha", config.noiseFloorAlpha)
            .put("detector_warmup_ms", config.detectorWarmupMs)
            .put("min_free_space_mb", config.minFreeSpaceMb.toInt())
            .put("full_recording_enabled", config.fullRecordingEnabled)

        val json = JsonObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("session_id", session.datasetSessionId)
            .put("participant_id", participant.participantId)
            .put("device_id", device.deviceId)
            .put("started_at_utc", isoUtc(session.startedAtMillis))
            .put("ended_at_utc", isoUtc(endedAtMillis))
            .put("timezone", session.timezone)
            .put("utc_offset_minutes", session.utcOffsetMinutes)
            .put("night_index", session.nightIndex)
            .put("recording_mode", session.recordingMode)
            .put("room_type", session.roomType)
            .put("session_status", session.sessionStatus)
            .put("consent_version", participant.consentVersion)
            .put("app_version", device.appVersion)
            .put("detector_config", detector.build())

        session.householdAudioRisk?.let { json.put("household_audio_risk", it) }
        if (session.notes.isNotBlank()) json.put("notes", session.notes)
        return json
    }

    private fun buildRecordingJson(
        recording: PreparedRecording,
        sessionId: String,
    ): JsonObject {
        val json = JsonObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("recording_id", recording.recordingId)
            .put("session_id", sessionId)
            .put("asset_type", "event_fragment")
            .put("relative_path", recording.relativePath)
            .put("format", "wav")
            .put("sample_rate_hz", 16_000)
            .put("channels", 1)
            .put("bits_per_sample", 16)
            .put("duration_ms", recording.durationMs)
            .put("started_at_utc", isoUtc(recording.startedAtMillis))
            .put("ended_at_utc", isoUtc(recording.endedAtMillis))
            .put("size_bytes", recording.sizeBytes)
            .put("sha256", recording.sourceSha256)
            .put("validation_status", "valid")
            .put("is_truncated", recording.isTruncated)
            .put("content_start_sample", recording.contentStartSample)
            .put("content_end_sample", recording.contentEndSample)
            .put("privacy_review_status", recording.privacyReviewStatus)
            .putStrings("privacy_flags", recording.privacyFlags)

        if (recording.detectorStartSample != null && recording.detectorEndSample != null) {
            json.put("detector_start_sample", recording.detectorStartSample)
            json.put("detector_end_sample", recording.detectorEndSample)
        }
        recording.peakDbfs?.let { json.put("peak_dbfs", it) }
        recording.averageDbfs?.let { json.put("average_dbfs", it) }
        if (recording.notes.isNotBlank()) json.put("notes", recording.notes)
        return json
    }

    private fun buildQcJson(plan: ExportPlan): JsonObject {
        val totalSamples = plan.recordings.sumOf { it.samples }
        val truncated = plan.recordings.count { it.isTruncated }
        val detectorRecords = plan.recordings.count { it.hasDetectorMetadata }

        return JsonObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("session_id", plan.session.datasetSessionId)
            .put("status", "pass")
            .put("recording_count", plan.recordings.size)
            .put("total_samples", totalSamples)
            .put("total_duration_ms", totalSamples * 1_000L / 16_000L)
            .put("truncated_recording_count", truncated)
            .put("detector_metadata_recording_count", detectorRecords)
            .put("human_annotations_included", false)
            .putStrings("warnings", plan.warnings)
    }

    private fun writeManifest(
        staging: File,
        plan: ExportPlan,
        request: DatasetExportRequest,
    ) {
        val files = allFiles(staging)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .sortedBy { relativePath(staging, it) }
            .map { file ->
                JsonObject()
                    .put("path", relativePath(staging, file))
                    .put("size_bytes", file.length())
                    .put("sha256", sha256(file))
            }

        val fileArray = JsonArray()
        files.forEach { fileArray.add(it) }

        val manifest = JsonObject()
            .put("dataset_id", DATASET_ID)
            .put("dataset_version", DATASET_VERSION)
            .put("schema_version", SCHEMA_VERSION)
            .put("taxonomy_version", TAXONOMY_VERSION)
            .put("annotation_guidelines_version", ANNOTATION_GUIDELINES_VERSION)
            .put("created_at_utc", isoUtc(request.exportedAtMillis))
            .put("frozen", false)
            .put("export_engine_version", EXPORT_ENGINE_VERSION)
            .putStrings("participant_ids", listOf(plan.participant.participantId))
            .putStrings("session_ids", listOf(plan.session.datasetSessionId))
            .putStrings("recording_ids", plan.recordings.map { it.recordingId }.sorted())
            .put("human_annotations_included", false)
            .put("detector_metadata_complete", plan.recordings.all { it.hasDetectorMetadata })
            .putStrings("consent_scopes", plan.participant.consentScopes)
            .putStrings(
                "limitations",
                listOf(
                    "Только event-фрагменты; полные ночи не экспортируются.",
                    "Человеческие аннотации и классы звуков не включены.",
                    "Detector-границы включены только при наличии точных metadata.",
                    "Пакет не является публичным релизом и требует privacy review.",
                ),
            )
            .put("files", fileArray.build())

        writeJson(File(staging, MANIFEST_FILE_NAME), manifest)
    }

    private fun packageReadme(plan: ExportPlan): String = buildString {
        appendLine("# Sleepy Sleep-Domain Dataset 0.1")
        appendLine()
        appendLine("Локальный экспорт Sleepy. Пакет не является публичным релизом.")
        appendLine()
        appendLine("- Participant: `${plan.participant.participantId}`")
        appendLine("- Session: `${plan.session.datasetSessionId}`")
        appendLine("- Записей: `${plan.recordings.size}`")
        appendLine("- Человеческие аннотации: отсутствуют")
        appendLine()
        appendLine("## Ограничения")
        appendLine()
        plan.warnings.forEach { warning -> appendLine("- $warning") }
        appendLine()
        appendLine("## Удаление")
        appendLine()
        appendLine(
            "Удаление участника из экспортированного пакета удаляет только копии " +
                "внутри пакета и не затрагивает исходные записи Sleepy.",
        )
    }

    private fun finishPackage(
        staging: File,
        outputRoot: File,
        plan: ExportPlan,
    ): DatasetExportResult {
        val finalRoot = File(outputRoot, PACKAGE_DIR)
        val manifestFile = File(finalRoot, MANIFEST_FILE_NAME)

        if (finalRoot.exists()) {
            val same = sameContents(staging, finalRoot)
            cleanupQuietly(staging)
            if (!same) {
                return DatasetExportResult.Failure(
                    code = "OUTPUT_CONFLICT",
                    message = "Пакет уже существует и отличается от нового экспорта",
                )
            }
            return DatasetExportResult.Success(
                packageRoot = finalRoot,
                manifestFile = manifestFile,
                recordingCount = plan.recordings.size,
                warnings = plan.warnings + "Существующий пакет совпал и был переиспользован.",
                reusedExisting = true,
            )
        }

        if (!staging.renameTo(finalRoot)) {
            fail("OUTPUT_MOVE_FAILED", "Не удалось переместить staging в итоговый пакет")
        }

        return DatasetExportResult.Success(
            packageRoot = finalRoot,
            manifestFile = manifestFile,
            recordingCount = plan.recordings.size,
            warnings = plan.warnings,
            reusedExisting = false,
        )
    }

    private fun sameContents(left: File, right: File): Boolean {
        if (!File(right, MANIFEST_FILE_NAME).isFile) return false

        val leftFiles = allFiles(left)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .associateBy { relativePath(left, it) }
        val rightFiles = allFiles(right)
            .filterNot { it.name == MANIFEST_FILE_NAME }
            .associateBy { relativePath(right, it) }

        if (leftFiles.keys != rightFiles.keys) return false
        if (!sameManifest(left, right)) return false

        return leftFiles.all { (path, leftFile) ->
            val rightFile = rightFiles[path] ?: return@all false
            leftFile.length() == rightFile.length() && sha256(leftFile) == sha256(rightFile)
        }
    }

    private fun sameManifest(left: File, right: File): Boolean {
        return try {
            val leftValue = DatasetJson.parse(File(left, MANIFEST_FILE_NAME).readText()) as? JsonValue.ObjectValue
                ?: return false
            val rightValue = DatasetJson.parse(File(right, MANIFEST_FILE_NAME).readText()) as? JsonValue.ObjectValue
                ?: return false
            normalizedManifest(leftValue) == normalizedManifest(rightValue)
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizedManifest(value: JsonValue.ObjectValue): JsonValue.ObjectValue =
        JsonValue.ObjectValue(value.fields.filterNot { it.first == "created_at_utc" })

    private fun ensureOutputRoot(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        if (root.exists() && !root.isDirectory) {
            fail("OUTPUT_NOT_DIRECTORY", "Путь экспорта существует и не является каталогом")
        }
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) {
            fail("OUTPUT_CREATE_FAILED", "Не удалось создать каталог экспорта")
        }
        return root
    }

    private fun createStaging(outputRoot: File): File {
        val temp = File.createTempFile("sleepy-dataset-", ".tmp", outputRoot)
        if (!temp.delete() || !temp.mkdirs()) {
            fail("STAGING_CREATE_FAILED", "Не удалось создать staging-каталог")
        }
        return temp
    }

    private fun cleanupQuietly(directory: File?) {
        if (directory == null || !directory.exists()) return
        try {
            deleteTree(directory)
        } catch (_: Exception) {
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteTree(child) }
        }
        file.delete()
    }

    private fun copyFile(source: File, destination: File) {
        destination.parentFile?.let { mkdirs(it) }
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }

    private fun writeJson(file: File, value: JsonObject) {
        writeText(file, DatasetJson.write(value.build()) + "\n")
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.let { mkdirs(it) }
        file.writeText(text, Charsets.UTF_8)
    }

    private fun mkdirs(directory: File) {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Не удалось создать каталог ${directory.name}")
        }
    }

    private fun canonicalDirectory(file: File): File {
        if (!file.exists() || !file.isDirectory) {
            fail("SOURCE_ROOT_MISSING", "Каталог исходных записей не найден")
        }
        return file.canonicalFile
    }

    private fun canonicalFile(file: File): File = try {
        file.canonicalFile
    } catch (error: IOException) {
        fail("SOURCE_INVALID", error.message ?: "Не удалось проверить путь файла")
    }

    private fun isInside(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    private fun requireText(value: String, name: String, min: Int, max: Int) {
        if (value.length !in min..max) {
            fail("INVALID_DEVICE_FIELD", "$name должен содержать от $min до $max символов")
        }
    }

    private fun validateDbfs(value: Double?, name: String) {
        if (value != null && (!value.isFinite() || value !in -120.0..0.0)) {
            fail("INVALID_DBFS", "$name должен быть в диапазоне -120..0")
        }
    }

    private fun recordingId(
        sessionId: String,
        sourceSha: String,
        contentStart: Long,
        contentEnd: Long,
    ): String {
        val digest = sha256Text("$sessionId|$sourceSha|$contentStart|$contentEnd")
        return "rec_${digest.take(24)}"
    }

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
        return digest.digest().toHex()
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun isoUtc(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(millis))
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

    private fun fail(code: String, message: String, details: List<String> = emptyList()): Nothing {
        throw ExportFailure(code, message, details)
    }

    private data class ExportPlan(
        val participant: DatasetParticipant,
        val device: DatasetDevice,
        val session: ExportSession,
        val recordings: List<PreparedRecording>,
        val warnings: List<String>,
    )

    private data class ExportSession(
        val datasetSessionId: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long?,
        val timezone: String,
        val utcOffsetMinutes: Int,
        val nightIndex: Int,
        val recordingMode: String,
        val roomType: String,
        val sessionStatus: String,
        val householdAudioRisk: String?,
        val notes: String,
    )

    private data class PreparedRecording(
        val sourceFile: File,
        val fileName: String,
        val sourceSha256: String,
        val recordingId: String,
        val relativePath: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val durationMs: Long,
        val sizeBytes: Long,
        val samples: Long,
        val contentStartSample: Long,
        val contentEndSample: Long,
        val detectorStartSample: Long?,
        val detectorEndSample: Long?,
        val peakDbfs: Double?,
        val averageDbfs: Double?,
        val isTruncated: Boolean,
        val hasDetectorMetadata: Boolean,
        val privacyReviewStatus: String,
        val privacyFlags: List<String>,
        val notes: String,
    )

    private class ExportFailure(
        val code: String,
        override val message: String,
        val details: List<String>,
    ) : Exception(message)

    private companion object {
        const val SCHEMA_VERSION = "0.1"
        const val DATASET_ID = "sleepy-sleep-domain-0.1"
        const val DATASET_VERSION = "0.1"
        const val TAXONOMY_VERSION = "0.1"
        const val ANNOTATION_GUIDELINES_VERSION = "0.1"
        const val EXPORT_ENGINE_VERSION = "0.1"
        const val PACKAGE_DIR = "sleepy-sleep-domain-0.1"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val MAX_NOTES = 2_000
        const val MAX_DEVICE_NOTES = 1_000

        val PARTICIPANT_ID = Regex("^ptc_[A-Za-z0-9][A-Za-z0-9._-]*$")
        val DEVICE_ID = Regex("^dev_[A-Za-z0-9][A-Za-z0-9._-]*$")
        val SESSION_ID = Regex("^ses_[A-Za-z0-9][A-Za-z0-9._-]*$")
        val RAW_SESSION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val SAFE_WAV_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.wav$")

        val CONSENT_SCOPES = setOf(
            "dataset_research",
            "model_training",
            "publication",
            "sharing_restricted",
        )
        val AGE_GROUPS = setOf(
            "18-24",
            "25-34",
            "35-44",
            "45-54",
            "55-64",
            "65+",
            "not_disclosed",
        )
        val MICROPHONE_POSITIONS = setOf(
            "bedside_near_head",
            "bedside_far",
            "under_mattress",
            "other",
            "unknown",
        )
        val CALIBRATION_STATUSES = setOf(
            "not_calibrated",
            "relative_only",
            "calibrated",
        )
        val ROOM_TYPES = setOf("bedroom", "other", "unknown")
        val SESSION_STATUSES = setOf("completed", "interrupted", "aborted", "partial")
        val HOUSEHOLD_AUDIO_RISKS = setOf(
            "none_expected",
            "possible",
            "confirmed",
            "unknown",
        )
        val PRIVACY_REVIEW_STATUSES = setOf(
            "not_reviewed",
            "approved_internal",
            "approved_restricted",
            "approved_public",
            "rejected",
        )
        val PRIVACY_FLAGS = setOf(
            "none_observed",
            "speech_present",
            "children_audible",
            "bystander_audible",
            "address_like",
            "intimate_content",
            "unknown",
        )
    }
}

