package com.sleepy.app.sleepy.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioFragmentRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fragments = mutableListOf<SavedAudioFragment>()
    private val warnings = mutableListOf<String>()
    private val events = mutableListOf<Long>()

    private var nowMillis = 1_700_000_000_000L
    private var freeSpace = Long.MAX_VALUE

    private lateinit var directory: File
    private lateinit var recorder: AudioFragmentRecorder

    private var fed = ShortArray(1 shl 16)
    private var fedSamples = 0L

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("fragments")
        fed = ShortArray(1 shl 16)
        fedSamples = 0L
        nowMillis = 1_700_000_000_000L
        freeSpace = Long.MAX_VALUE
        fragments.clear()
        warnings.clear()
        events.clear()
        createRecorder()
    }

    @Test
    fun wavHeaderIsCorrectPcm16Mono() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)

        assertEquals("RIFF", wav.ascii(0))
        assertEquals("WAVE", wav.ascii(8))
        assertEquals("fmt ", wav.ascii(12))
        assertEquals("data", wav.ascii(36))

        assertEquals(wav.bytes.size - 8, wav.le32(4))
        assertEquals(wav.bytes.size - 44, wav.le32(40))
        assertEquals(16, wav.le32(16))
        assertEquals(1, wav.le16(20))
        assertEquals(1, wav.le16(22))
        assertEquals(16_000, wav.le32(24))
        assertEquals(32_000, wav.le32(28))
        assertEquals(2, wav.le16(32))
        assertEquals(16, wav.le16(34))
        assertEquals(0, wav.le32(40) % 2)
    }

    @Test
    fun fileSizeMatchesHeaderAfterClose() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)

        assertEquals(44L + fragment.samples * 2, fragment.file.length())
        assertEquals(fragment.samples, wav.samples.size.toLong())
        assertEquals(wav.bytes.size - 44, fragment.samples.toInt() * 2)
        assertEquals(wav.le32(40), wav.bytes.size - 44)
        assertEquals(wav.le32(4), wav.bytes.size - 8)
    }

    @Test
    fun preRollAndPostRollAreSaved() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val config = RecordingConfig.DEFAULT
        val expectedSamples =
            (config.preRollSamples + config.sampleRate + config.postRollSamples).toLong()

        assertEquals(expectedSamples, fragment.samples)
        assertEquals(17_000L, fragment.durationMs)
        assertWavMatches(fragment, fromSample = 2 * 16_000L)
    }

    @Test
    fun audioBeforeConfirmationIsIncluded() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)
        val config = RecordingConfig.DEFAULT
        val eventStart = 10L * config.sampleRate

        assertEquals(fed[eventStart.toInt() - 1], wav.samples[config.preRollSamples - 1])
        assertEquals(fed[eventStart.toInt()], wav.samples[config.preRollSamples])
        assertEquals(
            fed[eventStart.toInt() + 100],
            wav.samples[config.preRollSamples + 100],
        )
        assertEquals(eventStart, fragment.startSample)
    }

    @Test
    fun chunksAreWrittenExactlyOnce() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)
        val from = (fragment.startSample - RecordingConfig.DEFAULT.preRollSamples).toInt()
        val expected = fed.copyOfRange(from, from + wav.samples.size)

        assertArrayEquals(expected, wav.samples)
    }

    @Test
    fun bufferHistoryShorterThanPreRollIsHandled() {
        feed(seconds = 3, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)

        assertEquals(3L * 16_000, fragment.startSample)
        assertEquals(12_000L, fragment.durationMs)
        assertArrayEquals(fed.copyOfRange(0, wav.samples.size), wav.samples)
    }

    @Test
    fun truncatedEventCreatesTwoContinuousFragments() {
        feed(seconds = 10, loud = false)
        feed(seconds = 61, loud = true)
        feed(seconds = 10, loud = false)
        recorder.stop()

        assertEquals(2, fragments.size)
        // Одно подтверждённое событие может дать несколько файлов.
        assertEquals(1, events.size)
        assertEquals(2, recorder.savedFragments)

        val first = fragments[0]
        val second = fragments[1]

        assertTrue(first.truncated)
        assertFalse(second.truncated)
        assertEquals(first.endSample, second.startSample)
        assertEquals(68_000L, first.durationMs)
        assertEquals(9_000L, second.durationMs)

        val firstWav = readWav(first.file)
        val secondWav = readWav(second.file)
        val firstFrom = (first.startSample - RecordingConfig.DEFAULT.preRollSamples).toInt()

        assertArrayEquals(
            fed.copyOfRange(firstFrom, firstFrom + firstWav.samples.size),
            firstWav.samples,
        )
        assertArrayEquals(
            fed.copyOfRange(
                second.startSample.toInt(),
                second.startSample.toInt() + secondWav.samples.size,
            ),
            secondWav.samples,
        )
        assertEquals(fed[first.endSample.toInt() - 1], firstWav.samples.last())
        assertEquals(fed[second.startSample.toInt()], secondWav.samples.first())
    }

    @Test
    fun insufficientSpaceSkipsFragment() {
        createRecorder(config = RecordingConfig(minFreeSpaceMb = 1))
        freeSpace = 0L

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertTrue(fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
        assertTrue(warnings.any { it.contains("мест", ignoreCase = true) })
    }

    @Test
    fun spaceExhaustedDuringRecordingAbortsFragment() {
        createRecorder(config = RecordingConfig(minFreeSpaceMb = 1))

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        freeSpace = 0L
        feed(seconds = 3, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertTrue(fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
        assertTrue(warnings.any { it.contains("мест", ignoreCase = true) })
        assertFalse(recorder.isRecordingEvent)
    }

    @Test
    fun writeErrorDeletesPartialFileAndRecovers() {
        var opened = 0
        createRecorder(
            openFile = { file ->
                opened += 1
                if (opened == 1) {
                    FailingRandomAccessFile(file)
                } else {
                    RandomAccessFile(file, "rw")
                }
            },
        )

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals(1, fragments.size)
        assertEquals(1, wavFiles().size)
        assertTrue(warnings.any { it.contains("запис", ignoreCase = true) })
    }

    @Test
    fun stopDuringActiveEventClosesValidFile() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)

        assertTrue(recorder.isRecordingEvent)

        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)

        assertEquals(9_000L, fragment.durationMs)
        assertEquals(wav.bytes.size - 44, wav.le32(40))
        assertEquals(wav.bytes.size - 8, wav.le32(4))
        assertFalse(recorder.isRecordingEvent)
        assertArrayEquals(
            fed.copyOfRange(2 * 16_000, wav.samples.size + 2 * 16_000),
            wav.samples,
        )
    }

    @Test
    fun stopDuringPostRollClosesFileEarly() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 3, loud = false)

        assertTrue(recorder.isWritingPostRoll)

        recorder.stop()

        val fragment = fragments.single()

        assertEquals(12_000L, fragment.durationMs)
        assertWavMatches(fragment, fromSample = 2 * 16_000L)
    }

    @Test
    fun consecutiveEventsDoNotShareSamples() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 4, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals(2, fragments.size)
        assertEquals(2, events.size)
        assertEquals(2, recorder.savedFragments)

        val first = fragments[0]
        val second = fragments[1]

        assertEquals(9_000L, first.durationMs)
        assertEquals(13_000L, second.durationMs)
        assertEquals(2L * 16_000, first.contentStartSample)
        assertEquals(11L * 16_000, first.contentEndSample)
        assertEquals(first.contentEndSample, second.contentStartSample)
        assertEquals(11L * 16_000, second.contentStartSample)
        assertEquals(24L * 16_000, second.contentEndSample)
        assertEquals(11L * 16_000, first.endSample)
        assertEquals(15L * 16_000, second.startSample)

        assertWavMatches(first, first.contentStartSample)
        assertWavMatches(second, second.contentStartSample)
        assertNoSharedSamples()
        assertTrue(first.file.name.startsWith("event_"))
        assertEquals("wav", first.file.extension)
        assertEquals(2, wavFiles().size)
    }

    @Test
    fun threeEventsFiveSecondsApart() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 5, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 5, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals(3, fragments.size)
        assertEquals(3, events.size)
        assertEquals(3, recorder.savedFragments)

        val first = fragments[0]
        val second = fragments[1]
        val third = fragments[2]

        assertEquals(9_000L, first.durationMs)
        assertEquals(6_000L, second.durationMs)
        assertEquals(14_000L, third.durationMs)

        assertEquals(2L * 16_000, first.contentStartSample)
        assertEquals(11L * 16_000, first.contentEndSample)
        assertEquals(first.contentEndSample, second.contentStartSample)
        assertEquals(17L * 16_000, second.contentEndSample)
        assertEquals(second.contentEndSample, third.contentStartSample)
        assertEquals(31L * 16_000, third.contentEndSample)

        fragments.forEach { assertWavMatches(it, it.contentStartSample) }
        assertNoSharedSamples()
    }

    @Test
    fun gapMatrixAroundMergeAndPreRoll() {
        assertGap(gapMillis = 1_990, expectedEvents = 1, expectedFragments = 1)
        assertGap(gapMillis = 2_010, expectedEvents = 2, expectedFragments = 2)
        assertGap(gapMillis = 7_900, expectedEvents = 2, expectedFragments = 2)
        assertGap(gapMillis = 8_100, expectedEvents = 2, expectedFragments = 2)
    }

    private fun assertGap(gapMillis: Int, expectedEvents: Int, expectedFragments: Int) {
        resetHarness("gap-$gapMillis")

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feedMillis(gapMillis, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals("События при интервале $gapMillis мс", expectedEvents, events.size)
        assertEquals("Файлы при интервале $gapMillis мс", expectedFragments, fragments.size)
        assertEquals(expectedFragments, recorder.savedFragments)
        assertNoSharedSamples()

        fragments.forEachIndexed { index, fragment ->
            assertWavMatches(fragment, fragment.contentStartSample)
            assertTrue(
                "Конец события должен входить в файл: ${fragment.file.name}",
                fragment.contentEndSample >= fragment.endSample,
            )
            val onsetCovered = fragment.contentStartSample <= fragment.startSample ||
                (index > 0 &&
                    fragments[index - 1].contentEndSample >= fragment.startSample)
            assertTrue(
                "Начало события должно быть в своём или предыдущем файле: " +
                    fragment.file.name,
                onsetCovered,
            )
        }
    }

    @Test
    fun truncatedContinuationHasNoOverlap() {
        recorder.stop()
        createRecorder(writerQueueBlocks = 1_024)

        feed(seconds = 10, loud = false)
        feed(seconds = 61, loud = true)
        feed(seconds = 10, loud = false)
        recorder.stop()

        assertEquals(2, fragments.size)

        val first = fragments[0]
        val second = fragments[1]

        assertEquals(2L * 16_000, first.contentStartSample)
        assertEquals(first.contentEndSample, second.contentStartSample)
        assertEquals(first.endSample, second.startSample)
        assertEquals(second.startSample, second.contentStartSample)
        assertEquals(1_088_000L, first.samples)
        assertEquals(144_000L, second.samples)

        assertWavMatches(first, first.contentStartSample)
        assertWavMatches(second, second.contentStartSample)
        assertNoSharedSamples()

        val firstWav = readWav(first.file)
        val secondWav = readWav(second.file)
        assertEquals(fed[first.contentEndSample.toInt() - 1], firstWav.samples.last())
        assertEquals(fed[second.contentStartSample.toInt()], secondWav.samples.first())
    }

    @Test
    fun preRollAcrossRingWrap() {
        feed(seconds = 35, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val config = RecordingConfig.DEFAULT
        val fragment = fragments.single()

        assertEquals(35L * 16_000, fragment.startSample)
        assertEquals(27L * 16_000, fragment.contentStartSample)
        assertEquals(17_000L, fragment.durationMs)
        assertEquals(44L * 16_000, fragment.contentEndSample)
        assertWavMatches(fragment, fragment.contentStartSample)
        assertTrue(
            "Кольцевой буфер должен успеть перезаписаться",
            fedSamples > config.ringCapacitySamples,
        )
    }

    @Test
    fun slowWriterDoesNotLoseOrDuplicateAudio() {
        createRecorder(openFile = { file -> SlowRandomAccessFile(file, delayMs = 1) })

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        val fragment = fragments.single()
        val wav = readWav(fragment.file)
        val from = (fragment.startSample - RecordingConfig.DEFAULT.preRollSamples).toInt()

        assertEquals(17_000L, fragment.durationMs)
        assertEquals(1, recorder.savedFragments)
        assertArrayEquals(fed.copyOfRange(from, from + wav.samples.size), wav.samples)
    }

    @Test
    fun writerQueueOverflowAbortsFragment() {
        createRecorder(
            writerQueueBlocks = 1,
            openFile = { file -> SlowRandomAccessFile(file, delayMs = 250) },
        )

        feed(seconds = 10, loud = false)
        feed(seconds = 2, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertTrue(fragments.isEmpty())
        assertTrue(wavFiles().isEmpty())
        assertFalse(recorder.isRecordingEvent)
        assertTrue(
            warnings.any {
                it.contains("буфер", ignoreCase = true) ||
                    it.contains("переполн", ignoreCase = true)
            },
        )
    }

    @Test
    fun writerFailureCancelsFragmentAndNextEventIsSaved() {
        createRecorder(
            openFile = { file -> DataFailFirstFileRandomAccessFile(file) },
        )

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals(2, events.size)
        assertEquals(
            "fragments=$fragments warnings=$warnings files=${wavFiles().map { it.name }}",
            1,
            fragments.size,
        )
        assertEquals(1, recorder.savedFragments)
        assertEquals(1, wavFiles().size)
        assertFalse(recorder.isRecordingEvent)
        assertTrue(warnings.any { it.contains("записи") })
    }

    @Test
    fun writerOpenFailureDoesNotFillQueueAndNextEventIsSaved() {
        createRecorder(
            writerQueueBlocks = 256,
            openFile = { file ->
                if (file.name.endsWith("_001.wav")) {
                    throw IOException("Смоделированный отказ создания")
                }
                RandomAccessFile(file, "rw")
            },
        )

        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        feed(seconds = 9, loud = false)
        recorder.stop()

        assertEquals(2, events.size)
        assertEquals(
            "fragments=$fragments warnings=$warnings files=${wavFiles().map { it.name }}",
            1,
            fragments.size,
        )
        assertEquals(1, wavFiles().size)
        assertTrue(warnings.any { it.contains("создать") })
        assertFalse(
            "После отказа записи очередь не должна переполняться: $warnings",
            warnings.any { it.contains("буфер") || it.contains("переполн") },
        )
    }

    @Test
    fun processAfterStopIsIgnored() {
        feed(seconds = 10, loud = false)
        feed(seconds = 1, loud = true)
        recorder.stop()

        val file = fragments.single().file
        val lengthAfterStop = file.length()

        feed(seconds = 1, loud = true)
        recorder.stop()

        assertEquals(1, fragments.size)
        assertEquals(lengthAfterStop, file.length())
    }

    private fun createRecorder(
        config: RecordingConfig = RecordingConfig.DEFAULT,
        openFile: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
        writerQueueBlocks: Int = AudioFragmentRecorder.DEFAULT_WRITER_BLOCKS,
    ) {
        recorder = AudioFragmentRecorder(
            config = config,
            directory = directory,
            clock = { nowMillis },
            freeSpaceProvider = { freeSpace },
            listener = object : AudioFragmentRecorder.Listener {
                override fun onFragmentSaved(fragment: SavedAudioFragment) {
                    fragments += fragment
                }

                override fun onEventDetected(event: DetectedAudioEvent) {
                    events += event.endedAtMillis
                }

                override fun onWarning(message: String) {
                    warnings += message
                }
            },
            openFile = openFile,
            writerQueueBlocks = writerQueueBlocks,
        )
    }

    private fun resetHarness(folderName: String) {
        if (::recorder.isInitialized) {
            recorder.stop()
        }
        fragments.clear()
        warnings.clear()
        events.clear()
        fed = ShortArray(1 shl 16)
        fedSamples = 0L
        nowMillis = 1_700_000_000_000L
        freeSpace = Long.MAX_VALUE
        directory = temporaryFolder.newFolder(folderName)
        createRecorder()
    }

    private fun feed(seconds: Int, loud: Boolean) {
        feedMillis(seconds * 1_000, loud)
    }

    private fun feedMillis(millis: Int, loud: Boolean) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(RecordingConfig.DEFAULT.chunkMs, remaining)
            val samples = RecordingConfig.DEFAULT.sampleRate * step / 1_000
            emitSamples(samples, loud)
            remaining -= step
        }
    }

    private fun emitSamples(count: Int, loud: Boolean) {
        val chunk = ShortArray(count)

        var index = 0
        while (index < count) {
            val value = if (loud) {
                (((fedSamples % 13) - 6) * 300).toShort()
            } else {
                ((fedSamples % 7) - 3).toShort()
            }

            if (fedSamples.toInt() == fed.size) {
                fed = fed.copyOf(fed.size * 2)
            }
            fed[fedSamples.toInt()] = value
            chunk[index] = value

            fedSamples += 1
            index += 1
        }

        recorder.process(chunk)
        nowMillis += count * 1_000L / RecordingConfig.DEFAULT.sampleRate
    }

    private fun assertNoSharedSamples() {
        fragments.zipWithNext().forEachIndexed { index, (first, second) ->
            assertTrue(
                "Фрагменты $index и ${index + 1} содержат общие сэмплы: " +
                    "${first.file.name} заканчивается на ${first.contentEndSample}, " +
                    "${second.file.name} начинается с ${second.contentStartSample}",
                first.contentEndSample <= second.contentStartSample,
            )
        }
    }

    private fun assertWavMatches(fragment: SavedAudioFragment, fromSample: Long) {
        val wav = readWav(fragment.file)
        val from = fromSample.toInt()

        assertArrayEquals(fed.copyOfRange(from, from + wav.samples.size), wav.samples)
        assertEquals(44L + wav.samples.size * 2L, fragment.file.length())
        assertEquals(wav.bytes.size - 44, wav.le32(40))
    }

    private fun wavFiles(): List<File> =
        directory.listFiles { file -> file.extension == "wav" }?.toList().orEmpty()

    private fun readWav(file: File): WavData {
        assertTrue("Файл должен существовать: ${file.absolutePath}", file.isFile)

        val bytes = file.readBytes()
        assertTrue("WAV должен быть не короче заголовка", bytes.size >= 44)

        return WavData(bytes, decodeSamples(bytes))
    }

    private fun decodeSamples(bytes: ByteArray): ShortArray {
        val count = (bytes.size - 44) / 2
        return ShortArray(count) { index ->
            val low = bytes[44 + index * 2].toInt() and 0xFF
            val high = bytes[45 + index * 2].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    private class WavData(val bytes: ByteArray, val samples: ShortArray) {
        fun ascii(offset: Int): String =
            String(bytes, offset, 4, StandardCharsets.US_ASCII)

        fun le16(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        fun le32(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private class FailingRandomAccessFile(file: File) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("Смоделированная ошибка записи")
        }
    }

    /**
     * Первый фрагмент ломается на записи аудиоданных, последующие файлы
     * пишутся нормально. Так проверяется, что recorder перестаёт кормить
     * мёртвый writer и продолжает работу.
     */
    private class DataFailFirstFileRandomAccessFile(
        file: File,
    ) : RandomAccessFile(file, "rw") {

        private val failWrites = file.name.endsWith("_001.wav")

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failWrites && filePointer >= 44L && len > 4) {
                throw IOException("Смоделированная ошибка записи")
            }
            super.write(b, off, len)
        }
    }

    private class SlowRandomAccessFile(
        file: File,
        private val delayMs: Long,
    ) : RandomAccessFile(file, "rw") {
        override fun write(b: ByteArray, off: Int, len: Int) {
            Thread.sleep(delayMs)
            super.write(b, off, len)
        }
    }
}
