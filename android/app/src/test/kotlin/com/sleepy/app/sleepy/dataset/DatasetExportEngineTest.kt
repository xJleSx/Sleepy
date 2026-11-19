package com.sleepy.app.sleepy.dataset

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatasetExportEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportCreatesPackageAndManifest() {
        val request = validRequest()

        val result = DatasetExportEngine().export(request)

        val success = result as DatasetExportResult.Success
        assertEquals(1, success.recordingCount)
        assertFalse(success.reusedExisting)
        assertEquals("sleepy-sleep-domain-0.1", success.packageRoot.name)
        assertTrue(success.manifestFile.isFile)
        assertTrue(File(success.packageRoot, "participants/ptc_demo.json").isFile)
        assertTrue(File(success.packageRoot, "devices/dev_demo.json").isFile)
        assertTrue(File(success.packageRoot, "sessions/ses_session-1.json").isFile)
        assertTrue(File(success.packageRoot, "qc/ses_session-1.qc.json").isFile)
        assertTrue(File(success.packageRoot, "README.md").isFile)
        assertTrue(
            File(
                success.packageRoot,
                "audio/event_fragments/ptc_demo/ses_session-1/event_1.wav",
            ).isFile,
        )
        assertTrue(success.warnings.any { it.contains("privacy") })
        val manifest = DatasetJson.parse(success.manifestFile.readText()) as JsonValue.ObjectValue
        assertEquals("sleepy-sleep-domain-0.1", manifest["dataset_id"]?.asText())
        assertEquals(7, manifest["files"]?.asArray()?.size)
    }

    @Test
    fun exportIsIdempotentAndReusesExistingPackage() {
        val request = validRequest()
        val engine = DatasetExportEngine()
        val first = engine.export(request) as DatasetExportResult.Success

        val second = engine.export(request) as DatasetExportResult.Success

        assertTrue(second.reusedExisting)
        assertEquals(first.packageRoot, second.packageRoot)
        assertTrue(second.warnings.any { it.contains("переиспользован") })
        assertEquals(6, first.packageRoot.listFiles()!!.count { it.isDirectory })
    }

    @Test
    fun recordingIdIsStableAcrossFileNameAndTime() {
        val firstRequest = validRequest(fileName = "event_1.wav")
        val first = DatasetExportEngine().export(firstRequest) as DatasetExportResult.Success
        val secondSource = temporaryFolder.newFolder("source_renamed")
        val secondFile = writeWav(secondSource, "event_renamed.wav", 32_000)
        val secondRequest = validRequest(
            fileName = "event_renamed.wav",
            sourceRoot = secondSource,
            outputRoot = temporaryFolder.newFolder("output_renamed"),
            fileStartedAtMillis = firstRequest.session.startedAtMillis + 5_000,
            sourceFile = secondFile,
        )

        val second = DatasetExportEngine().export(secondRequest) as DatasetExportResult.Success

        val firstRecording = DatasetJson.parse(
            File(first.packageRoot, "recordings").listFiles()!!.single().readText(),
        ) as JsonValue.ObjectValue
        val secondRecording = DatasetJson.parse(
            File(second.packageRoot, "recordings").listFiles()!!.single().readText(),
        ) as JsonValue.ObjectValue
        assertEquals(firstRecording["recording_id"]?.asText(), secondRecording["recording_id"]?.asText())
        assertNotEquals(
            firstRecording["relative_path"]?.asText(),
            secondRecording["relative_path"]?.asText(),
        )
    }

    @Test
    fun missingContentBoundariesFailWithoutCreatingPackage() {
        val request = validRequest(contentStart = null, contentEnd = null)

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("MISSING_CONTENT_BOUNDARIES", failure.code)
        assertFalse(File(request.outputRoot, "sleepy-sleep-domain-0.1").exists())
    }

    @Test
    fun contentSampleMismatchFails() {
        val request = validRequest(contentEnd = 16_001)

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("CONTENT_SAMPLE_MISMATCH", failure.code)
    }

    @Test
    fun corruptedWavFails() {
        val request = validRequest()
        request.recordings.single().sourceFile.writeText("not a wave file")

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("INVALID_WAV", failure.code)
    }

    @Test
    fun expiredConsentFails() {
        val request = validRequest(participant = validParticipant().copy(consentStatus = "withdrawn"))

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("CONSENT_NOT_ACTIVE", failure.code)
    }

    @Test
    fun missingResearchScopeFails() {
        val request = validRequest(
            participant = validParticipant().copy(consentScopes = listOf("publication")),
        )

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("CONSENT_SCOPE_REQUIRED", failure.code)
    }

    @Test
    fun minorWithoutGuardianConsentFails() {
        val request = validRequest(
            participant = validParticipant().copy(minorInvolved = true, guardianConsent = false),
        )

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("GUARDIAN_CONSENT_REQUIRED", failure.code)
    }

    @Test
    fun sourceOutsideRootFails() {
        val outside = temporaryFolder.newFolder("outside")
        val file = writeWav(outside, "event_outside.wav", 32_000)
        val request = validRequest(sourceFile = file)

        val failure = failure(DatasetExportEngine().export(request))

        assertEquals("SOURCE_OUTSIDE_RECORDINGS", failure.code)
    }

    @Test
    fun duplicateRecordingFails() {
        val request = validRequest()
        val duplicate = request.copy(recordings = request.recordings + request.recordings)

        val failure = failure(DatasetExportEngine().export(duplicate))

        assertEquals("DUPLICATE_RECORDING_ID", failure.code)
    }

    @Test
    fun deleteParticipantRemovesOnlyPackageCopies() {
        val request = validRequest()
        val exported = DatasetExportEngine().export(request) as DatasetExportResult.Success
        val sourceBytes = request.recordings.single().sourceFile.readBytes()

        val result = DatasetPackageDeleter().deleteParticipant(exported.packageRoot, "ptc_demo")

        val success = result as DatasetDeletionResult.Success
        assertTrue(success.integrityOk)
        assertTrue(success.deletedFiles.any { it.startsWith("audio/event_fragments/ptc_demo/") })
        assertTrue(success.deletedFiles.contains("participants/ptc_demo.json"))
        assertTrue(success.deletedFiles.contains("sessions/ses_session-1.json"))
        assertTrue(success.deletedFiles.contains("qc/ses_session-1.qc.json"))
        assertTrue(success.deletedFiles.contains("devices/dev_demo.json"))
        assertFalse(File(exported.packageRoot, "participants/ptc_demo.json").exists())
        assertFalse(File(exported.packageRoot, "audio/event_fragments/ptc_demo").exists())
        assertFalse(File(exported.packageRoot, "sessions/ses_session-1.json").exists())
        assertFalse(File(exported.packageRoot, "recordings").exists())
        assertTrue(File(exported.packageRoot, "README.md").isFile)
        assertTrue(exported.manifestFile.isFile)
        assertEquals(1, success.remainingFiles)
        assertFalse(File(exported.packageRoot, "README.md").readText().contains("ptc_demo"))
        assertFalse(File(exported.packageRoot, "README.md").readText().contains("ses_session-1"))
        assertTrue(request.recordings.single().sourceFile.isFile)
        assertTrue(sourceBytes.contentEquals(request.recordings.single().sourceFile.readBytes()))

        val manifest = DatasetJson.parse(exported.manifestFile.readText()) as JsonValue.ObjectValue
        assertEquals(0, manifest["participant_ids"]?.asArray()?.size)
        assertEquals(0, manifest["session_ids"]?.asArray()?.size)
        assertEquals(0, manifest["recording_ids"]?.asArray()?.size)
        assertEquals(1, manifest["files"]?.asArray()?.size)
    }

    @Test
    fun deleteRejectsTamperedPackage() {
        val request = validRequest()
        val exported = DatasetExportEngine().export(request) as DatasetExportResult.Success
        val audio = File(
            exported.packageRoot,
            "audio/event_fragments/ptc_demo/ses_session-1/event_1.wav",
        )
        audio.appendBytes(byteArrayOf(1, 2, 3))

        val failure = failure(
            DatasetPackageDeleter().deleteParticipant(exported.packageRoot, "ptc_demo"),
        )

        assertEquals("PACKAGE_INTEGRITY_FAILED", failure.code)
        assertTrue(audio.isFile)
        assertTrue(File(exported.packageRoot, "participants/ptc_demo.json").isFile)
    }

    @Test
    fun deleteRejectsMissingParticipant() {
        val exported = DatasetExportEngine().export(validRequest()) as DatasetExportResult.Success

        val failure = failure(
            DatasetPackageDeleter().deleteParticipant(exported.packageRoot, "ptc_absent"),
        )

        assertEquals("PARTICIPANT_NOT_FOUND", failure.code)
    }

    @Test
    fun jsonParserReadsNestedAndEscapedValues() {
        val parsed = DatasetJson.parse(
            """
            {
              "text": "строка\nс escape",
              "number": 16000,
              "flag": true,
              "none": null,
              "items": [1, "two"]
            }
            """.trimIndent(),
        ) as JsonValue.ObjectValue

        assertEquals("строка\nс escape", parsed["text"]?.asText())
        assertEquals(16000L, parsed["number"]?.asLong())
        assertEquals(null, parsed["missing"])
        assertEquals(2, parsed["items"]?.asArray()?.size)
        assertEquals(JsonValue.NullValue, parsed["none"])
    }

    private fun validRequest(
        fileName: String = "event_1.wav",
        sourceRoot: File = temporaryFolder.newFolder(),
        outputRoot: File = temporaryFolder.newFolder(),
        sourceFile: File? = null,
        participant: DatasetParticipant = validParticipant(),
        fileStartedAtMillis: Long = 1_700_000_001_000L,
        contentStart: Long? = 0L,
        contentEnd: Long? = 16_000L,
    ): DatasetExportRequest {
        val file = sourceFile ?: writeWav(sourceRoot, fileName, 32_000)
        val startedAt = 1_700_000_000_000L
        return DatasetExportRequest(
            outputRoot = outputRoot,
            sourceRecordingsRoot = sourceRoot,
            participant = participant,
            device = DatasetDevice(
                deviceId = "dev_demo",
                createdAtMillis = 1_690_000_000_000L,
                manufacturer = "Demo",
                model = "Phone",
                osVersion = "Android 14",
                appVersion = "1.0.0",
                microphonePosition = "bedside_near_head",
                placementNotes = "Bedside table",
                calibrationStatus = "not_calibrated",
            ),
            session = DatasetSession(
                sourceSessionId = "session-1",
                startedAtMillis = startedAt,
                endedAtMillis = startedAt + 60_000L,
                timezone = "Europe/Moscow",
                utcOffsetMinutes = 180,
                nightIndex = 1,
            ),
            recordings = listOf(
                DatasetRecordingSource(
                    sourceFile = file,
                    sourceSessionId = "session-1",
                    fileStartedAtMillis = fileStartedAtMillis,
                    contentStartSample = contentStart,
                    contentEndSample = contentEnd,
                    detectorStartSample = 0L,
                    detectorEndSample = 16_000L,
                    isTruncated = false,
                ),
            ),
            exportedAtMillis = 1_700_001_000_000L,
        )
    }

    private fun validParticipant(): DatasetParticipant = DatasetParticipant(
        participantId = "ptc_demo",
        createdAtMillis = 1_690_000_000_000L,
        consentVersion = "1.0",
        consentGrantedAtMillis = 1_690_000_100_000L,
        consentStatus = "active",
        consentScopes = listOf("dataset_research", "publication"),
        minorInvolved = false,
        guardianConsent = false,
    )

    private fun writeWav(directory: File, name: String, dataBytes: Int): File {
        val header = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            .putInt(36 + dataBytes)
            .put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            .put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(16_000)
            .putInt(32_000)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray(StandardCharsets.US_ASCII))
            .putInt(dataBytes)
            .array()
        val file = File(directory, name)
        file.writeBytes(header + ByteArray(dataBytes))
        return file
    }

    private fun failure(result: DatasetExportResult): DatasetExportResult.Failure {
        assertTrue(result is DatasetExportResult.Failure)
        return result as DatasetExportResult.Failure
    }

    private fun failure(result: DatasetDeletionResult): DatasetDeletionResult.Failure {
        assertTrue(result is DatasetDeletionResult.Failure)
        return result as DatasetDeletionResult.Failure
    }
}
