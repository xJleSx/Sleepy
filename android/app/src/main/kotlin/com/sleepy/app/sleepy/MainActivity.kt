package com.sleepy.app.sleepy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.sleepy.app.sleepy.alarm.AlarmScheduleResult
import com.sleepy.app.sleepy.alarm.AlarmSpec
import com.sleepy.app.sleepy.alarm.SleepAlarmScheduler
import com.sleepy.app.sleepy.audio.AudioFragmentCatalog
import com.sleepy.app.sleepy.audio.AudioFragmentMetadata
import com.sleepy.app.sleepy.audio.FragmentPlayer
import com.sleepy.app.sleepy.audio.RecordingStatusStore
import com.sleepy.app.sleepy.dataset.DatasetDeletionResult
import com.sleepy.app.sleepy.dataset.DatasetDevice
import com.sleepy.app.sleepy.dataset.DatasetExportEngine
import com.sleepy.app.sleepy.dataset.DatasetExportRequest
import com.sleepy.app.sleepy.dataset.DatasetExportResult
import com.sleepy.app.sleepy.dataset.DatasetPackageDeleter
import com.sleepy.app.sleepy.dataset.DatasetParticipant
import com.sleepy.app.sleepy.dataset.DatasetRecordingSource
import com.sleepy.app.sleepy.dataset.DatasetSession
import com.sleepy.app.sleepy.storage.SleepyLocalStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.sleepy.app.sleepy/recording"
        private const val STORAGE_CHANNEL = "com.sleepy.app.sleepy/storage"
        private const val ALARM_CHANNEL = "com.sleepy.app.sleepy/alarm"
        private const val PERMISSION_REQUEST_CODE = 501
    }

    private var pendingPermissionResult: MethodChannel.Result? = null

    private val fragmentPlayer = FragmentPlayer()

    private lateinit var localStore: SleepyLocalStore
    private lateinit var alarmScheduler: SleepAlarmScheduler

    private var storageExecutor: ExecutorService? = null

    @Volatile
    private var destroyed = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        storageExecutor?.shutdownNow()
        storageExecutor = Executors.newSingleThreadExecutor()
        destroyed = false

        localStore = SleepyLocalStore(applicationContext)
        alarmScheduler = SleepAlarmScheduler(applicationContext)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            STORAGE_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "readText" -> {
                    val key = call.argument<String>("key")
                    if (key == null) {
                        result.error("INVALID_STORAGE_KEY", "Не передан ключ", null)
                    } else {
                        runStorage(result) { localStore.readText(key) }
                    }
                }

                "writeText" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<String>("value")
                    if (key == null || value == null) {
                        result.error(
                            "INVALID_STORAGE_VALUE",
                            "Не переданы ключ или значение",
                            null
                        )
                    } else {
                        runStorage(result) {
                            localStore.writeText(key, value)
                            true
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            ALARM_CHANNEL
        ).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "canScheduleExactAlarms" -> {
                        result.success(alarmScheduler.canScheduleExactAlarms())
                    }

                    "requestExactAlarmPermission" -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            result.success(false)
                        } else {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                            result.success(true)
                        }
                    }

                    "scheduleAlarm" -> {
                        val arguments = call.arguments as? Map<*, *>
                        val spec = AlarmSpec.fromChannel(arguments)
                        val triggerAtMillis = (arguments?.get("triggerAtMillis") as? Number)
                            ?.toLong()
                            ?: throw IllegalArgumentException("Не передано время будильника")
                        result.success(
                            alarmScheduler.schedule(spec, triggerAtMillis).toPayload()
                        )
                    }

                    "cancelAlarm" -> {
                        val alarmId = call.argument<String>("id")
                            ?: throw IllegalArgumentException("Не передан id будильника")
                        alarmScheduler.cancel(alarmId)
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            } catch (exception: Exception) {
                result.error(
                    "ALARM_CHANNEL_ERROR",
                    exception.message ?: "Ошибка будильника",
                    null
                )
            }
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                "requestMicrophonePermission" -> {
                    requestMicrophonePermission(result)
                }

                "startRecording" -> {
                    startRecording(result)
                }

                "stopRecording" -> {
                    stopRecording(result)
                }

                "getRecordingStatus" -> {
                    getRecordingStatus(result)
                }

                "listFragments" -> {
                    listFragments(result)
                }

                "listDiagnosticFiles" -> {
                    listDiagnosticFiles(result)
                }

                "deleteFragment" -> {
                    val path = call.argument<String>("path")
                    if (path.isNullOrBlank()) {
                        result.error("INVALID_FRAGMENT_PATH", "Не передан путь", null)
                    } else {
                        deleteFragment(path, result)
                    }
                }

                "playFragment" -> {
                    playFragment(call.argument<String>("path").orEmpty(), result)
                }

                "pausePlayback" -> {
                    fragmentPlayer.pause()
                    result.success(true)
                }

                "resumePlayback" -> {
                    fragmentPlayer.resume()
                    result.success(true)
                }

                "stopPlayback" -> {
                    fragmentPlayer.stop()
                    result.success(true)
                }

                "seekPlayback" -> {
                    val positionMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
                    fragmentPlayer.seekTo(positionMs)
                    result.success(true)
                }

                "getPlaybackState" -> {
                    result.success(fragmentPlayer.snapshot())
                }

                "exportDataset" -> {
                    exportDataset(call.arguments as? Map<*, *>, result)
                }

                "deleteDatasetParticipant" -> {
                    deleteDatasetParticipant(call.arguments as? Map<*, *>, result)
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        storageExecutor?.shutdownNow()
        storageExecutor = null
        fragmentPlayer.release()
        super.onDestroy()
    }

    private fun requestMicrophonePermission(
        result: MethodChannel.Result
    ) {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }

        if (pendingPermissionResult != null) {
            result.error(
                "PERMISSION_PENDING",
                "Запрос разрешения уже выполняется",
                null
            )
            return
        }

        pendingPermissionResult = result

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissions(
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val microphoneGranted =
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

            pendingPermissionResult?.success(microphoneGranted)
            pendingPermissionResult = null
        }
    }

    private fun startRecording(result: MethodChannel.Result) {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            result.error(
                "NO_MICROPHONE_PERMISSION",
                "Нет разрешения на использование микрофона",
                null
            )
            return
        }

        val intent = Intent(
            this,
            SleepRecordingService::class.java
        ).apply {
            action = SleepRecordingService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            result.success(true)
        } catch (exception: Exception) {
            result.error(
                "START_SERVICE_ERROR",
                exception.message,
                null
            )
        }
    }

    private fun stopRecording(result: MethodChannel.Result) {
        val intent = Intent(
            this,
            SleepRecordingService::class.java
        ).apply {
            action = SleepRecordingService.ACTION_STOP
        }

        try {
            startService(intent)
            result.success(true)
        } catch (exception: Exception) {
            result.error(
                "STOP_SERVICE_ERROR",
                exception.message,
                null
            )
        }
    }

    private fun getRecordingStatus(result: MethodChannel.Result) {
        val store = RecordingStatusStore(this)

        try {
            result.success(store.readStatus())
        } catch (exception: Exception) {
            result.error(
                "STATUS_READ_ERROR",
                exception.message ?: "Не удалось прочитать состояние записи",
                null
            )
        }
    }

    private fun deleteFragment(path: String, result: MethodChannel.Result) {
        runStorage(result) {
            val directory = recordingsDirectory().canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile != directory || !file.name.endsWith(".wav", true)) {
                throw IllegalArgumentException("Фрагмент вне каталога записей")
            }
            if (!file.isFile) {
                throw IllegalStateException("Фрагмент уже удалён")
            }

            try {
                fragmentPlayer.stop()
            } catch (_: Exception) {
            }

            AudioFragmentMetadata.sidecarFile(file).delete()
            if (!file.delete()) {
                throw IllegalStateException("Не удалось удалить WAV-фрагмент")
            }
            true
        }
    }

    private fun listFragments(result: MethodChannel.Result) {
        val executor = storageExecutor

        if (executor == null || destroyed) {
            result.error(
                "STORAGE_UNAVAILABLE",
                "Хранилище недоступно",
                null
            )
            return
        }

        executor.execute {
            val payload = try {
                AudioFragmentCatalog
                    .list(recordingsDirectory())
                    .map { fragment ->
                        val item = hashMapOf<String, Any>(
                            "id" to fragment.id,
                            "fileName" to fragment.fileName,
                            "filePath" to fragment.filePath,
                            "startedAtMillis" to fragment.startedAtMillis,
                            "durationMs" to fragment.durationMs,
                            "sizeBytes" to fragment.sizeBytes,
                            "metadataAvailable" to (fragment.metadata != null)
                        )
                        fragment.metadata?.let { metadata ->
                            item["sampleRateHz"] = metadata.sampleRateHz
                            item["startSample"] = metadata.startSample
                            item["endSample"] = metadata.endSample
                            item["contentStartSample"] = metadata.contentStartSample
                            item["contentEndSample"] = metadata.contentEndSample
                            item["truncated"] = metadata.truncated
                        }
                        item
                    }
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "LIST_FRAGMENTS_ERROR",
                        exception.message ?: "Не удалось прочитать фрагменты",
                        null
                    )
                }
                return@execute
            }

            postToUi { result.success(payload) }
        }
    }

    /**
     * Полная диагностика каталога WAV: валидные, пустые, повреждённые и
     * недоступные файлы. Абсолютные пути наружу не передаются — экрану
     * диагностики и отчёту достаточно имени файла.
     */
    private fun listDiagnosticFiles(result: MethodChannel.Result) {
        val executor = storageExecutor

        if (executor == null || destroyed) {
            result.error(
                "STORAGE_UNAVAILABLE",
                "Хранилище недоступно",
                null
            )
            return
        }

        executor.execute {
            val payload = try {
                AudioFragmentCatalog
                    .diagnose(recordingsDirectory())
                    .map { diagnostic ->
                        val item = hashMapOf<String, Any>(
                            "fileName" to diagnostic.fileName,
                            "startedAtMillis" to diagnostic.startedAtMillis,
                            "status" to diagnostic.status.name,
                            "reason" to diagnostic.reason
                        )
                        diagnostic.sizeBytes?.let { item["sizeBytes"] = it }
                        diagnostic.durationMs?.let { item["durationMs"] = it }
                        item
                    }
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "LIST_DIAGNOSTIC_FILES_ERROR",
                        exception.message ?: "Не удалось проверить WAV-файлы",
                        null
                    )
                }
                return@execute
            }

            postToUi { result.success(payload) }
        }
    }

    private fun exportDataset(arguments: Map<*, *>?, result: MethodChannel.Result) {
        val executor = storageExecutor

        if (executor == null || destroyed) {
            result.error("STORAGE_UNAVAILABLE", "Хранилище недоступно", null)
            return
        }

        val request = try {
            datasetExportRequest(arguments)
        } catch (exception: IllegalArgumentException) {
            result.error("INVALID_DATASET_REQUEST", exception.message, null)
            return
        }

        executor.execute {
            val payload = try {
                datasetExportPayload(DatasetExportEngine().export(request))
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "DATASET_EXPORT_ERROR",
                        exception.message ?: "Не удалось экспортировать датасет",
                        null,
                    )
                }
                return@execute
            }

            postToUi { result.success(payload) }
        }
    }

    private fun deleteDatasetParticipant(
        arguments: Map<*, *>?,
        result: MethodChannel.Result,
    ) {
        val executor = storageExecutor

        if (executor == null || destroyed) {
            result.error("STORAGE_UNAVAILABLE", "Хранилище недоступно", null)
            return
        }

        val packageRoot = arguments?.optionalString("packageRootPath")
        val participantId = arguments?.optionalString("participantId")
        if (packageRoot.isNullOrBlank() || participantId.isNullOrBlank()) {
            result.error(
                "INVALID_DATASET_REQUEST",
                "Нужны packageRootPath и participantId",
                null,
            )
            return
        }

        executor.execute {
            val payload = try {
                datasetDeletionPayload(
                    DatasetPackageDeleter().deleteParticipant(File(packageRoot), participantId),
                )
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "DATASET_DELETE_ERROR",
                        exception.message ?: "Не удалось удалить участника",
                        null,
                    )
                }
                return@execute
            }

            postToUi { result.success(payload) }
        }
    }

    private fun datasetExportRequest(arguments: Map<*, *>?): DatasetExportRequest {
        if (arguments == null) {
            throw IllegalArgumentException("Аргументы экспорта не переданы")
        }
        val participant = arguments.requiredMap("participant")
        val device = arguments.requiredMap("device")
        val session = arguments.requiredMap("session")
        val recordings = arguments.requiredList("recordings").map { value ->
            val recording = value as? Map<*, *>
                ?: throw IllegalArgumentException("recordings должен содержать объекты")
            val sourceFile = File(recording.requiredString("sourceFilePath"))
            val metadata = AudioFragmentMetadata.read(sourceFile)
            DatasetRecordingSource(
                sourceFile = sourceFile,
                sourceSessionId = recording.requiredString("sourceSessionId"),
                fileStartedAtMillis = recording.optionalLong("fileStartedAtMillis")
                    ?: metadata?.startedAtMillis
                    ?: throw IllegalArgumentException("fileStartedAtMillis должен быть числом"),
                contentStartSample = recording.optionalLong("contentStartSample")
                    ?: metadata?.contentStartSample,
                contentEndSample = recording.optionalLong("contentEndSample")
                    ?: metadata?.contentEndSample,
                detectorStartSample = recording.optionalLong("detectorStartSample")
                    ?: metadata?.startSample,
                detectorEndSample = recording.optionalLong("detectorEndSample")
                    ?: metadata?.endSample,
                peakDbfs = recording.optionalDouble("peakDbfs"),
                averageDbfs = recording.optionalDouble("averageDbfs"),
                isTruncated = recording.optionalBoolean("isTruncated")
                    ?: metadata?.truncated
                    ?: false,
                privacyReviewStatus = recording.optionalString("privacyReviewStatus")
                    ?: "not_reviewed",
                privacyFlags = recording.stringList("privacyFlags").ifEmpty {
                    listOf("unknown")
                },
                notes = recording.optionalString("notes") ?: "",
            )
        }

        return DatasetExportRequest(
            outputRoot = File(arguments.requiredString("outputRootPath")),
            sourceRecordingsRoot = File(arguments.requiredString("sourceRecordingsRootPath")),
            participant = DatasetParticipant(
                participantId = participant.requiredString("participantId"),
                createdAtMillis = participant.requiredLong("createdAtMillis"),
                consentVersion = participant.requiredString("consentVersion"),
                consentGrantedAtMillis = participant.requiredLong("consentGrantedAtMillis"),
                consentStatus = participant.requiredString("consentStatus"),
                consentScopes = participant.stringList("consentScopes"),
                minorInvolved = participant.optionalBoolean("minorInvolved") ?: false,
                guardianConsent = participant.optionalBoolean("guardianConsent") ?: false,
                ageGroup = participant.optionalString("ageGroup"),
                notes = participant.optionalString("notes") ?: "",
            ),
            device = DatasetDevice(
                deviceId = device.requiredString("deviceId"),
                createdAtMillis = device.requiredLong("createdAtMillis"),
                manufacturer = device.requiredString("manufacturer"),
                model = device.requiredString("model"),
                osVersion = device.requiredString("osVersion"),
                appVersion = device.requiredString("appVersion"),
                microphonePosition = device.requiredString("microphonePosition"),
                placementNotes = device.requiredString("placementNotes"),
                calibrationStatus = device.requiredString("calibrationStatus"),
                notes = device.optionalString("notes") ?: "",
            ),
            session = DatasetSession(
                sourceSessionId = session.requiredString("sourceSessionId"),
                startedAtMillis = session.requiredLong("startedAtMillis"),
                endedAtMillis = session.optionalLong("endedAtMillis"),
                timezone = session.requiredString("timezone"),
                utcOffsetMinutes = session.requiredInt("utcOffsetMinutes"),
                nightIndex = session.requiredInt("nightIndex"),
                recordingMode = session.optionalString("recordingMode") ?: "event_fragments",
                roomType = session.optionalString("roomType") ?: "unknown",
                sessionStatus = session.optionalString("sessionStatus") ?: "completed",
                householdAudioRisk = session.optionalString("householdAudioRisk"),
                notes = session.optionalString("notes") ?: "",
            ),
            recordings = recordings,
            exportedAtMillis = arguments.optionalLong("exportedAtMillis")
                ?: System.currentTimeMillis(),
        )
    }

    private fun datasetExportPayload(result: DatasetExportResult): Map<String, Any?> =
        when (result) {
            is DatasetExportResult.Success -> mapOf(
                "ok" to true,
                "packageRootPath" to result.packageRoot.path,
                "manifestPath" to result.manifestFile.path,
                "recordingCount" to result.recordingCount,
                "warnings" to result.warnings,
                "reusedExisting" to result.reusedExisting,
            )
            is DatasetExportResult.Failure -> mapOf(
                "ok" to false,
                "code" to result.code,
                "message" to result.message,
                "details" to result.details,
            )
        }

    private fun datasetDeletionPayload(result: DatasetDeletionResult): Map<String, Any?> =
        when (result) {
            is DatasetDeletionResult.Success -> mapOf(
                "ok" to true,
                "deletedFiles" to result.deletedFiles,
                "remainingFiles" to result.remainingFiles,
                "integrityOk" to result.integrityOk,
                "warnings" to result.warnings,
            )
            is DatasetDeletionResult.Failure -> mapOf(
                "ok" to false,
                "code" to result.code,
                "message" to result.message,
                "details" to result.details,
            )
        }

    private fun playFragment(path: String, result: MethodChannel.Result) {
        if (!isPlayableFragment(path)) {
            result.error(
                "FRAGMENT_UNAVAILABLE",
                "Файл фрагмента недоступен",
                null
            )
            return
        }

        fragmentPlayer.play(path)
        result.success(true)
    }

    private fun isPlayableFragment(path: String): Boolean {
        if (path.isBlank()) return false

        val file = File(path)
        if (!file.isFile) return false
        if (!file.name.endsWith(".wav", ignoreCase = true)) return false

        return try {
            val directory = recordingsDirectory().canonicalFile
            val canonical = file.canonicalFile
            canonical.parentFile == directory
        } catch (_: IOException) {
            false
        }
    }

    private fun Map<*, *>.requiredMap(key: String): Map<*, *> =
        this[key] as? Map<*, *>
            ?: throw IllegalArgumentException("$key должен быть объектом")

    private fun Map<*, *>.requiredList(key: String): List<*> =
        this[key] as? List<*>
            ?: throw IllegalArgumentException("$key должен быть массивом")

    private fun Map<*, *>.requiredString(key: String): String =
        optionalString(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$key должен быть непустой строкой")

    private fun Map<*, *>.optionalString(key: String): String? =
        this[key] as? String

    private fun Map<*, *>.requiredLong(key: String): Long =
        optionalLong(key) ?: throw IllegalArgumentException("$key должен быть числом")

    private fun Map<*, *>.optionalLong(key: String): Long? =
        (this[key] as? Number)?.toLong()

    private fun Map<*, *>.requiredInt(key: String): Int =
        (this[key] as? Number)?.toInt()
            ?: throw IllegalArgumentException("$key должен быть числом")

    private fun Map<*, *>.optionalDouble(key: String): Double? =
        (this[key] as? Number)?.toDouble()

    private fun Map<*, *>.optionalBoolean(key: String): Boolean? =
        this[key] as? Boolean

    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.map { value ->
            value as? String ?: throw IllegalArgumentException("$key должен содержать строки")
        } ?: emptyList()

    private fun recordingsDirectory(): File {
        return File(filesDir, SleepRecordingService.RECORDINGS_DIRECTORY)
    }

    private fun runStorage(
        result: MethodChannel.Result,
        block: () -> Any?,
    ) {
        val executor = storageExecutor
        if (executor == null || destroyed) {
            result.error("STORAGE_UNAVAILABLE", "Хранилище недоступно", null)
            return
        }

        executor.execute {
            try {
                val value = block()
                postToUi { result.success(value) }
            } catch (exception: Exception) {
                postToUi {
                    result.error(
                        "STORAGE_ERROR",
                        exception.message ?: "Ошибка локального хранилища",
                        null,
                    )
                }
            }
        }
    }

    private fun AlarmScheduleResult.toPayload(): Map<String, Any?> {
        return mapOf(
            "scheduled" to scheduled,
            "exact" to exact,
            "triggerAtMillis" to triggerAtMillis,
            "message" to message,
        )
    }

    private fun postToUi(block: () -> Unit) {
        if (destroyed) return

        runOnUiThread {
            if (!destroyed) block()
        }
    }
}
