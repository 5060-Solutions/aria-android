package com.solutions5060.aria.ui.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.messaging.Attachment
import com.solutions5060.aria.messaging.MessagingRepository
import com.solutions5060.aria.ui.theme.AriaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Decoded message pictures, kept in memory only and dropped on sign-out. */
object AttachmentCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(id: String): Bitmap? = cache.get(id)
    fun put(id: String, bitmap: Bitmap) {
        cache.put(id, bitmap)
    }

    fun clear() = cache.evictAll()
}

/** Pure sizing rules for decoding and for what the server accepts. */
object PhotoRules {
    /** The server takes JPEG, PNG and GIF pictures up to this size. */
    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    private val SERVER_TYPES = setOf("image/jpeg", "image/png", "image/gif")
    const val MAX_EDGE = 2048

    fun needsReencode(contentType: String?, size: Int): Boolean =
        contentType?.lowercase() !in SERVER_TYPES || size > MAX_UPLOAD_BYTES

    /** The `inSampleSize` that brings the longer edge to at most [target] without going below it. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= target) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

}

/** A picture ready to upload. */
class PreparedPhoto(val bytes: ByteArray, val contentType: String, val fileName: String, val uri: Uri)

object Photos {
    /**
     * Reads the picture [uri] names. A type or size the server will not take is
     * re-encoded as a JPEG of at most 2048 px; null when it cannot be read, or
     * is still too large afterwards.
     */
    fun prepare(context: Context, uri: Uri): PreparedPhoto? {
        val resolver = context.contentResolver
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val type = resolver.getType(uri)
        if (!PhotoRules.needsReencode(type, raw.size)) {
            val ext = type!!.substringAfter('/').replace("jpeg", "jpg")
            return PreparedPhoto(raw, type, "photo.$ext", uri)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoRules.sampleSize(bounds.outWidth, bounds.outHeight, PhotoRules.MAX_EDGE)
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val bytes = out.toByteArray()
        return if (bytes.size > PhotoRules.MAX_UPLOAD_BYTES) null else PreparedPhoto(bytes, "image/jpeg", "photo.jpg", uri)
    }
}

/** A picture in a message: fetched with the session, decoded at a bounded size. */
@Composable
fun AttachmentImage(repository: MessagingRepository, attachment: Attachment, description: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(AttachmentCache.get(attachment.id), attachment.id) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val bytes = repository.attachmentBytes(attachment.id)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = PhotoRules.sampleSize(bounds.outWidth, bounds.outHeight, 1024)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { AttachmentCache.put(attachment.id, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
    val shown = bitmap
    Surface(modifier, shape = AriaShapes.contentRow, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 280.dp),
            )
        } else {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {}
        }
    }
}
