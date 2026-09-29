package top.rootu.lampa

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.bumptech.glide.Glide
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Encodes the original already cached by Lampa. No new network request, no work on the UI thread. */
class HighQualityArtwork(context: Context, uri: String, deliver: (String?) -> Unit) {
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())
    init {
        val app = context.applicationContext
        thread(name = "lampa-pult-artwork", isDaemon = true) {
            val request = Glide.with(app).asBitmap().load(uri).onlyRetrieveFromCache(true).submit(1440, 2160)
            val encoded = try {
                val original = request.get(8, TimeUnit.SECONDS)
                if (cancelled) null else encode(original)
            } catch (_: Exception) { null }
            finally { main.post { Glide.with(app).clear(request) } }
            main.post { if (!cancelled) deliver(encoded) }
        }
    }
    fun cancel() { cancelled = true }

    private fun encode(original: Bitmap): String? {
        var current = original
        try {
            repeat(4) {
                for (quality in intArrayOf(86, 78)) {
                    if (cancelled) return null
                    val bytes = ByteArrayOutputStream()
                    current.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
                    if (bytes.size() <= 192 * 1024) return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
                val smaller = Bitmap.createScaledBitmap(current, (current.width * 0.8f).toInt().coerceAtLeast(1),
                    (current.height * 0.8f).toInt().coerceAtLeast(1), true)
                if (current !== original && current !== smaller) current.recycle()
                current = smaller
            }
            return null
        } finally { if (current !== original) current.recycle() }
    }

    companion object { const val KEY = "local.pult.ARTWORK_JPEG" }
}
