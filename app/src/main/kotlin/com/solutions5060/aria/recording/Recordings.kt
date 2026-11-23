package com.solutions5060.aria.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One finished (or in-progress) call recording on disk. */
data class Recording(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    /** From the WAV header; null when the header is missing or unreadable. */
    val durationSeconds: Int?,
)

/**
 * Call recordings under `filesDir/recordings`: app-private, excluded from
 * backup (data_extraction_rules excludes the whole file domain), shared only
 * when the user asks through a FileProvider grant.
 *
 * Plain JVM file handling around [dir] so it is unit-tested with a temp dir.
 */
class RecordingsRepository(val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, DIR_NAME))

    companion object {
        const val DIR_NAME = "recordings"
        /** Matches the `<files-path>` in res/xml/file_paths.xml. */
        const val AUTHORITY_SUFFIX = ".recordings"

        private val unsafe = Regex("[^A-Za-z0-9_+-]")

        /** A file name from the time and the remote party, with nothing but safe characters. */
        fun fileNameFor(remote: String, atMillis: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMillis))
            val who = remote.removePrefix("sip:").substringBefore("@").replace(unsafe, "_").take(32)
                .ifEmpty { "call" }
            return "$stamp-$who.wav"
        }

        /**
         * Seconds of audio in a canonical PCM WAV: data size / byte rate.
         * Null for anything that is not a RIFF/WAVE file.
         */
        fun wavDurationSeconds(file: File): Int? = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return null
                val header = ByteArray(44)
                raf.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) return null
                val byteRate = le32(header, 28)
                if (byteRate <= 0) return null
                // The core rewrites the sizes when it finishes the file; an
                // unfinished one has 0 there, so fall back to the file size.
                val dataSize = le32(header, 40).takeIf { it > 0 } ?: (raf.length() - 44)
                (dataSize / byteRate).toInt()
            }
        } catch (_: Exception) {
            null
        }

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xff) or
                ((b[at + 1].toLong() and 0xff) shl 8) or
                ((b[at + 2].toLong() and 0xff) shl 16) or
                ((b[at + 3].toLong() and 0xff) shl 24)
    }

    /** A new file for a call with [remote], creating the directory. The core writes it. */
    fun newRecordingFile(remote: String, atMillis: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        var file = File(dir, fileNameFor(remote, atMillis))
        var n = 1
        while (file.exists()) {
            file = File(dir, fileNameFor(remote, atMillis).removeSuffix(".wav") + "-$n.wav")
            n++
        }
        return file
    }

    /** Every recording, newest first. */
    fun list(): List<Recording> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: emptyArray())
            .map {
                Recording(
                    file = it,
                    name = it.name,
                    sizeBytes = it.length(),
                    modifiedAtMillis = it.lastModified(),
                    durationSeconds = wavDurationSeconds(it),
                )
            }
            .sortedByDescending { it.modifiedAtMillis }

    /** Deletes every recording (sign-out). */
    fun deleteAll() {
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    /** Deletes [recording]; false if it was not one of ours or could not be removed. */
    fun delete(recording: Recording): Boolean = owns(recording.file) && recording.file.delete()

    /** Whether [file] lives directly in the recordings directory (no path tricks). */
    fun owns(file: File): Boolean =
        file.canonicalFile.parentFile == dir.canonicalFile

    /** An `ACTION_SEND` chooser for [recording] with a read grant, or null if it is not ours. */
    fun shareIntent(context: Context, recording: Recording): Intent? {
        if (!owns(recording.file)) return null
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, recording.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * Recording a call without the other party's consent is illegal in many
 * places. Before the first recording the app shows a notice and the user
 * confirms they will tell the other party; [CallManager.startRecording]
 * refuses until then.
 */
object RecordingConsent {
    private const val PREFS = "aria_prefs"
    private const val KEY = "recording_consent_acknowledged"

    fun isAcknowledged(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun acknowledge(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }
    }
}
