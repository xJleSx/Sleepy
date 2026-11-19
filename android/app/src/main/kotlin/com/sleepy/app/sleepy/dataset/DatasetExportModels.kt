package com.sleepy.app.sleepy.dataset

import java.io.File

data class DatasetExportRequest(
    val outputRoot: File,
    val sourceRecordingsRoot: File,
    val participant: DatasetParticipant,
    val device: DatasetDevice,
    val session: DatasetSession,
    val recordings: List<DatasetRecordingSource>,
    val exportedAtMillis: Long = System.currentTimeMillis(),
)

data class DatasetParticipant(
    val participantId: String,
    val createdAtMillis: Long,
    val consentVersion: String,
    val consentGrantedAtMillis: Long,
    val consentStatus: String,
    val consentScopes: List<String>,
    val minorInvolved: Boolean,
    val guardianConsent: Boolean,
    val ageGroup: String? = null,
    val notes: String = "",
)

data class DatasetDevice(
    val deviceId: String,
    val createdAtMillis: Long,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    val microphonePosition: String,
    val placementNotes: String,
    val calibrationStatus: String,
    val notes: String = "",
)

data class DatasetSession(
    val sourceSessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val timezone: String,
    val utcOffsetMinutes: Int,
    val nightIndex: Int,
    val recordingMode: String = "event_fragments",
    val roomType: String = "unknown",
    val sessionStatus: String = "completed",
    val householdAudioRisk: String? = null,
    val notes: String = "",
)

data class DatasetRecordingSource(
    val sourceFile: File,
    val sourceSessionId: String,
    val fileStartedAtMillis: Long,
    val contentStartSample: Long?,
    val contentEndSample: Long?,
    val detectorStartSample: Long? = null,
    val detectorEndSample: Long? = null,
    val peakDbfs: Double? = null,
    val averageDbfs: Double? = null,
    val isTruncated: Boolean,
    val privacyReviewStatus: String = "not_reviewed",
    val privacyFlags: List<String> = listOf("unknown"),
    val notes: String = "",
)

sealed class DatasetExportResult {
    data class Success(
        val packageRoot: File,
        val manifestFile: File,
        val recordingCount: Int,
        val warnings: List<String>,
        val reusedExisting: Boolean,
    ) : DatasetExportResult()

    data class Failure(
        val code: String,
        val message: String,
        val details: List<String> = emptyList(),
    ) : DatasetExportResult()
}

sealed class DatasetDeletionResult {
    data class Success(
        val deletedFiles: List<String>,
        val remainingFiles: Int,
        val integrityOk: Boolean,
        val warnings: List<String>,
    ) : DatasetDeletionResult()

    data class Failure(
        val code: String,
        val message: String,
        val details: List<String> = emptyList(),
    ) : DatasetDeletionResult()
}
