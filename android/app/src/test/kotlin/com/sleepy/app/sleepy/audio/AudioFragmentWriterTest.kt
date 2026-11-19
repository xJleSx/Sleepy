package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFragmentWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = RecordingConfig.DEFAULT

    @Test
    fun savesFragmentAndReportsItOnce() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(directory, listener)

        writer.open(1L, 1_000L, 0L, 0L)
        assertTrue(writer.append(ShortArray(100) { 7 }, 0, 100))
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        assertEquals(1, listener.saved.size)
        assertEquals(100L, listener.saved.single().samples)
        assertTrue(listener.saved.single().file.isFile)
        assertTrue(listener.failed.isEmpty())
        assertTrue(listener.warnings.isEmpty())
        assertEquals(1L, writer.openCountTotal)
        assertEquals(1L, writer.savedCountTotal)
        assertEquals(0L, writer.failedCountTotal)
        assertEquals("", writer.lastErrorText)
        assertEquals(
            listener.saved.single().file.absolutePath,
            writer.lastSavedPathText,
        )
    }

    @Test
    fun savesSidecarMetadataWithExactBoundaries() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("metadata")
        val writer = newWriter(directory, listener)

        writer.open(1L, 1_000L, 100L, 150L)
        assertTrue(writer.append(ShortArray(100) { 7 }, 0, 100))
        writer.finish(2_000L, 200L, truncated = false, contentEndSample = 250L)
        assertTrue(writer.stop(5_000))

        val saved = listener.saved.single()
        val metadata = requireNotNull(AudioFragmentMetadata.read(saved.file))

        assertEquals(100L, metadata.startSample)
        assertEquals(200L, metadata.endSample)
        assertEquals(150L, metadata.contentStartSample)
        assertEquals(250L, metadata.contentEndSample)
        assertEquals(100L, metadata.samples)
        assertEquals(config.sampleRate, metadata.sampleRateHz)
        assertEquals(saved.file.length(), metadata.sizeBytes)
        assertTrue(AudioFragmentMetadata.sidecarFile(saved.file).isFile)
        assertTrue(listener.warnings.isEmpty())
    }

    @Test
    fun openingReusedNameRemovesStaleSidecar() {
        val directory = temporaryFolder.newFolder("metadata")
        val listener = RecordingListener()
        val first = newWriter(directory, listener)

        first.open(1L, 1_000L, 0L, 0L)
        assertTrue(first.append(ShortArray(100) { 1 }, 0, 100))
        first.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(first.stop(5_000))

        val savedFile = listener.saved.single().file
        val sidecar = AudioFragmentMetadata.sidecarFile(savedFile)
        assertTrue(sidecar.isFile)
        assertTrue(savedFile.delete())

        val second = newWriter(directory, RecordingListener())
        second.open(2L, 1_000L, 0L, 0L)
        second.abort()
        assertTrue(second.stop(5_000))

        assertFalse(sidecar.exists())
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun openFailureIsReportedAndFragmentIsNotSaved() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { throw IOException("Смоделированный отказ создания") },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        assertTrue(writer.append(ShortArray(100) { 1 }, 0, 100))
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("создать"))
        assertTrue(wavFiles(directory).isEmpty())
        assertEquals(1L, writer.openCountTotal)
        assertEquals(0L, writer.savedCountTotal)
        assertEquals(1L, writer.failedCountTotal)
        assertTrue(writer.lastErrorText.contains("создать"))
        assertEquals("", writer.lastSavedPathText)
    }

    @Test
    fun writeFailureIsReportedOnceAndFileIsDeleted() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { DataFailRandomAccessFile(it) },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.append(ShortArray(100) { 2 }, 0, 100)
        writer.finish(2_000L, 200L, truncated = false, contentEndSample = 200L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("записи"))
        assertTrue(wavFiles(directory).isEmpty())
        assertEquals(1L, writer.failedCountTotal)
        assertTrue(writer.lastErrorText.contains("записи"))
    }

    @Test
    fun headerUpdateFailureIsReportedAndFragmentIsDeleted() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { HeaderFailRandomAccessFile(it) },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("заголовка"))
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun securityFailureKeepsWriterAliveUntilStop() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("security")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { SecurityFailRandomAccessFile(it) },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)

        assertTrue(writer.stop(5_000))
        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("доступа"))
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun trimFailureIsReportedAndFragmentIsDeleted() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { TrimFailRandomAccessFile(it) },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 50L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("обрезке"))
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun closeFailureIsReportedAndFragmentIsNotCountedAsSaved() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { CloseFailRandomAccessFile(it) },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(listener.failed.single().contains("закрытии"))
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun abortDeletesFileWithoutSuccessOrFailure() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(directory, listener)

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.abort()
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertTrue(listener.failed.isEmpty())
        assertTrue(wavFiles(directory).isEmpty())
    }

    @Test
    fun nextFragmentIsSavedAfterPreviousFailure() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("fragments")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            openFile = { file ->
                if (file.name.endsWith("_001.wav")) {
                    DataFailRandomAccessFile(file)
                } else {
                    RandomAccessFile(file, "rw")
                }
            },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        writer.append(ShortArray(100) { 1 }, 0, 100)
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)

        writer.open(2L, 3_000L, 100L, 100L)
        writer.append(ShortArray(100) { 2 }, 0, 100)
        writer.finish(4_000L, 200L, truncated = false, contentEndSample = 200L)

        assertTrue(writer.stop(5_000))

        assertEquals(1, listener.failed.size)
        assertEquals(1, listener.saved.size)
        assertTrue(listener.saved.single().file.name.endsWith("_002.wav"))
        assertEquals(1, wavFiles(directory).size)
        assertEquals(2L, writer.openCountTotal)
        assertEquals(1L, writer.savedCountTotal)
        assertEquals(1L, writer.failedCountTotal)
        assertTrue(writer.lastSavedPathText.endsWith("_002.wav"))
    }

    @Test
    fun periodicSyncFailureDeletesFragmentAndWriterRecovers() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("sync")
        var syncCalls = 0
        val writer = newWriter(
            directory = directory,
            listener = listener,
            syncFile = {
                syncCalls += 1
                if (syncCalls == 1) {
                    throw IOException("Смоделированная ошибка sync")
                }
            },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        assertTrue(writer.append(ShortArray(80_000) { 1 }, 0, 80_000))
        writer.finish(6_000L, 80_000L, truncated = false, contentEndSample = 80_000L)

        writer.open(2L, 7_000L, 80_000L, 80_000L)
        assertTrue(writer.append(ShortArray(80_000) { 2 }, 0, 80_000))
        writer.finish(12_000L, 160_000L, truncated = false, contentEndSample = 160_000L)

        assertTrue(writer.stop(5_000))

        assertEquals(1, listener.failed.size)
        assertEquals(1, listener.saved.size)
        assertTrue(listener.saved.single().file.name.endsWith("_002.wav"))
        assertEquals(1, wavFiles(directory).size)
        assertTrue(writer.lastErrorText.contains("sync"))
        assertEquals(2L, writer.openCountTotal)
        assertEquals(1L, writer.savedCountTotal)
        assertEquals(1L, writer.failedCountTotal)
        assertTrue(syncCalls >= 3)
    }

    @Test
    fun closeSyncFailureDeletesFragmentWithoutSavingIt() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("close-sync")
        val writer = newWriter(
            directory = directory,
            listener = listener,
            syncFile = { throw IOException("Смоделированная ошибка sync") },
        )

        writer.open(1L, 1_000L, 0L, 0L)
        assertTrue(writer.append(ShortArray(100) { 1 }, 0, 100))
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        assertTrue(listener.saved.isEmpty())
        assertEquals(1, listener.failed.size)
        assertTrue(wavFiles(directory).isEmpty())
        assertEquals(1L, writer.failedCountTotal)
        assertTrue(writer.lastErrorText.contains("sync"))
    }

    @Test
    fun operationsAfterStopAreIgnoredAndRepeatedStopIsSafe() {
        val listener = RecordingListener()
        val directory = temporaryFolder.newFolder("stopped")
        val writer = newWriter(directory, listener)

        writer.open(1L, 1_000L, 0L, 0L)
        assertTrue(writer.append(ShortArray(100) { 1 }, 0, 100))
        writer.finish(2_000L, 100L, truncated = false, contentEndSample = 100L)
        assertTrue(writer.stop(5_000))

        val filesAfterStop = wavFiles(directory).toList()
        val savedAfterStop = listener.saved.size

        writer.open(2L, 3_000L, 100L, 100L)
        assertFalse(writer.append(ShortArray(100) { 2 }, 0, 100))
        writer.finish(4_000L, 200L, truncated = false, contentEndSample = 200L)
        writer.abort()

        assertTrue(writer.stop(5_000))
        assertTrue(writer.stop(5_000))

        assertEquals(filesAfterStop, wavFiles(directory))
        assertEquals(savedAfterStop, listener.saved.size)
        assertTrue(listener.failed.isEmpty())
        assertEquals(1L, writer.openCountTotal)
        assertTrue(listener.saved.single().file.isFile)
    }

    private fun newWriter(
        directory: File,
        listener: AudioFragmentWriter.Listener,
        openFile: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
        queueBlocks: Int = 256,
        syncFile: (RandomAccessFile) -> Unit = { it.fd.sync() },
    ): AudioFragmentWriter = AudioFragmentWriter(
        config = config,
        directory = directory,
        listener = listener,
        openFile = openFile,
        freeSpaceProvider = { Long.MAX_VALUE },
        queueBlocks = queueBlocks,
        syncFile = syncFile,
    )

    private fun wavFiles(directory: File): List<File> =
        directory.listFiles { file -> file.extension == "wav" }?.toList().orEmpty()

    private class RecordingListener : AudioFragmentWriter.Listener {

        val saved = CopyOnWriteArrayList<SavedAudioFragment>()
        val failed = CopyOnWriteArrayList<String>()
        val warnings = CopyOnWriteArrayList<String>()

        override fun onFragmentSaved(fragment: SavedAudioFragment) {
            saved += fragment
        }

        override fun onFragmentFailed(generation: Long, message: String) {
            failed += message
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    /** Пропускает запись заголовка, но ломает запись аудиоданных. */
    private class DataFailRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (filePointer >= 44L && len > 4) {
                throw IOException("Смоделированная ошибка записи данных")
            }
            super.write(b, off, len)
        }
    }

    /** Ломает правку заголовка после появления аудиоданных. */
    private class HeaderFailRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun seek(pos: Long) {
            if (pos == 4L && length() > 44L) {
                throw IOException("Смоделированная ошибка обновления заголовка")
            }
            super.seek(pos)
        }
    }

    /** Имитирует отзыв доступа к файлу во время записи аудиоданных. */
    private class SecurityFailRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (filePointer >= 44L) {
                throw SecurityException("Смоделированный отказ доступа")
            }
            super.write(b, off, len)
        }
    }

    private class TrimFailRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun setLength(newLength: Long) {
            if (newLength < length()) {
                throw IOException("Смоделированная ошибка обрезки")
            }
            super.setLength(newLength)
        }
    }

    private class CloseFailRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun close() {
            super.close()
            throw IOException("Смоделированная ошибка закрытия")
        }
    }
}
