package com.sleepy.app.sleepy.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFragmentMetadataTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndReadsExactBoundaries() {
        val directory = temporaryFolder.newFolder("metadata")
        val wav = File(directory, "event_20261119_230000_000_001.wav")
        wav.writeBytes(ByteArray(64_000))
        val fragment = fragment(wav)

        assertTrue(AudioFragmentMetadata.write(wav, fragment, 16_000))

        val metadata = requireNotNull(AudioFragmentMetadata.read(wav))

        assertEquals(AudioFragmentMetadata.SCHEMA_VERSION, metadata.schemaVersion)
        assertEquals(wav.name, metadata.fileName)
        assertEquals(16_000, metadata.sampleRateHz)
        assertEquals(1_000L, metadata.startedAtMillis)
        assertEquals(2_000L, metadata.endedAtMillis)
        assertEquals(1_000L, metadata.durationMs)
        assertEquals(32_000L, metadata.samples)
        assertEquals(32_000L, metadata.startSample)
        assertEquals(64_000L, metadata.endSample)
        assertEquals(0L, metadata.contentStartSample)
        assertEquals(32_000L, metadata.contentEndSample)
        assertFalse(metadata.truncated)
        assertEquals(64_000L, metadata.sizeBytes)
        assertTrue(AudioFragmentMetadata.sidecarFile(wav).isFile)
    }

    @Test
    fun rejectsSidecarWhenWavSizeChanges() {
        val directory = temporaryFolder.newFolder("metadata")
        val wav = File(directory, "event_20261119_230000_000_001.wav")
        wav.writeBytes(ByteArray(64_000))
        assertTrue(AudioFragmentMetadata.write(wav, fragment(wav), 16_000))

        wav.appendBytes(ByteArray(2))

        assertNull(AudioFragmentMetadata.read(wav))
    }

    @Test
    fun rejectsIncompleteOrCorruptedSidecar() {
        val directory = temporaryFolder.newFolder("metadata")
        val wav = File(directory, "event_20261119_230000_000_001.wav")
        wav.writeBytes(ByteArray(64_000))
        val sidecar = AudioFragmentMetadata.sidecarFile(wav)
        sidecar.writeText("schema_version=1\nfile_name=${wav.name}\n")

        assertNull(AudioFragmentMetadata.read(wav))
    }

    @Test
    fun deleteRemovesSidecarAndTemporaryFile() {
        val directory = temporaryFolder.newFolder("metadata")
        val wav = File(directory, "event_20261119_230000_000_001.wav")
        wav.writeBytes(ByteArray(64_000))
        assertTrue(AudioFragmentMetadata.write(wav, fragment(wav), 16_000))
        val sidecar = AudioFragmentMetadata.sidecarFile(wav)
        assertTrue(sidecar.isFile)

        AudioFragmentMetadata.delete(wav)

        assertFalse(sidecar.exists())
        assertNull(AudioFragmentMetadata.read(wav))
    }

    @Test
    fun writeFailsWhenWavIsMissing() {
        val wav = File(temporaryFolder.root, "missing.wav")

        assertFalse(AudioFragmentMetadata.write(wav, fragment(wav), 16_000))
        assertNull(AudioFragmentMetadata.read(wav))
    }

    @Test
    fun readsLegacySchemaV1WithSafeDefaults() {
        val directory = temporaryFolder.newFolder("metadata-v1")
        val wav = File(directory, "event_20261119_230000_000_001.wav")
        wav.writeBytes(ByteArray(64_000))
        val sidecar = AudioFragmentMetadata.sidecarFile(wav)
        sidecar.writeText(
            """
            schema_version=1
            file_name=${wav.name}
            sample_rate_hz=16000
            started_at_millis=1000
            ended_at_millis=2000
            duration_ms=1000
            samples=32000
            start_sample=32000
            end_sample=64000
            content_start_sample=0
            content_end_sample=32000
            truncated=false
            size_bytes=64000
            """.trimIndent(),
        )

        val metadata = requireNotNull(AudioFragmentMetadata.read(wav))

        assertEquals(1, metadata.schemaVersion)
        assertEquals(RecordingProfile.STANDARD.storageValue, metadata.profileId)
        assertEquals(RecordingEventMetadata.EVENT_CLASS_NORMAL, metadata.eventClass)
        assertEquals(RecordingEventMetadata.REVIEW_STATUS_UNREVIEWED, metadata.reviewStatus)
        assertNull(metadata.peakDbfs)
        assertNull(metadata.averageDbfs)
    }

    private fun fragment(wav: File): SavedAudioFragment = SavedAudioFragment(
        file = wav,
        startedAtMillis = 1_000L,
        endedAtMillis = 2_000L,
        durationMs = 1_000L,
        samples = 32_000L,
        startSample = 32_000L,
        endSample = 64_000L,
        truncated = false,
        contentStartSample = 0L,
        contentEndSample = 32_000L,
    )
}
