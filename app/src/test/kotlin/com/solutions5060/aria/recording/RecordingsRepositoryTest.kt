package com.solutions5060.aria.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = RecordingsRepository(File(tmp.root, "recordings"))

    private fun wav(file: File, seconds: Int, rate: Int = 8000, finished: Boolean = true) {
        val data = ByteArray(seconds * rate * 2)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(if (finished) 36 + data.size else 0)
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16)
        h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(if (finished) data.size else 0)
        file.writeBytes(h.array() + data)
    }

    @Test
    fun fileNamesAreSafe() {
        val name = RecordingsRepository.fileNameFor("sip:../../evil name@pbx", 0)
        assertTrue(name, name.matches(Regex("\\d{8}-\\d{6}-[A-Za-z0-9_+-]+\\.wav")))
        assertTrue(RecordingsRepository.fileNameFor("sip:@pbx", 0).endsWith("-call.wav"))
    }

    @Test
    fun newFilesNeverCollide() {
        val r = repo()
        val a = r.newRecordingFile("sip:100@x", 1_000)
        a.writeText("x")
        val b = r.newRecordingFile("sip:100@x", 1_000)
        assertFalse(a == b)
        assertTrue(r.dir.isDirectory)
    }

    @Test
    fun listsNewestFirstWithDurations() {
        val r = repo()
        val old = r.newRecordingFile("sip:100@x", 1_000).also { wav(it, 3) }
        old.setLastModified(1_000_000)
        val new = r.newRecordingFile("sip:200@x", 2_000).also { wav(it, 5, rate = 48000) }
        new.setLastModified(2_000_000)
        File(r.dir, "notes.txt").writeText("ignored")

        val list = r.list()
        assertEquals(listOf(new.name, old.name), list.map { it.name })
        assertEquals(5, list[0].durationSeconds)
        assertEquals(3, list[1].durationSeconds)
    }

    @Test
    fun anUnfinishedHeaderFallsBackToTheFileSize() {
        val f = repo().newRecordingFile("sip:1@x").also { wav(it, 2, finished = false) }
        assertEquals(2, RecordingsRepository.wavDurationSeconds(f))
    }

    @Test
    fun notAWavHasNoDuration() {
        val f = File(tmp.root, "x.wav").apply { writeText("hello") }
        assertNull(RecordingsRepository.wavDurationSeconds(f))
    }

    @Test
    fun deletesOnlyItsOwnFiles() {
        val r = repo()
        val mine = r.newRecordingFile("sip:1@x").also { wav(it, 1) }
        val outside = File(tmp.root, "outside.wav").also { wav(it, 1) }
        assertTrue(r.delete(r.list().single()))
        assertFalse(mine.exists())
        assertFalse(r.delete(Recording(outside, outside.name, 1, 0, 1)))
        assertTrue(outside.exists())
    }
}
