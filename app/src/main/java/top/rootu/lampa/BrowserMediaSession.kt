package top.rootu.lampa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import top.rootu.lampa.browser.Browser
import top.rootu.lampa.tmdb.TMDB

/** Publishes the visible HTML player's metadata without starting playback. */
class BrowserMediaSession(context: Context, private val browser: () -> Browser?) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val script = context.assets.open("pult-web-media.js").bufferedReader().use { it.readText() }
    private var running = false
    private var query = 0
    private var session: MediaSession? = null
    private var snapshot: WebPlaybackSnapshot? = null
    private var artwork: Bitmap? = null
    private var artworkTarget: CustomTarget<Bitmap>? = null
    private var artworkKey = ""
    private var artworkGeneration = 0
    private var highQualityJpeg: String? = null
    private var highQualityJob: HighQualityArtwork? = null
    private val tick = Runnable { poll() }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        query++
        handler.removeCallbacksAndMessages(null)
        clearSession()
    }

    private fun poll() {
        if (!running) return
        val view = browser()?.takeUnless { it.isDestroyed }
        if (view == null) {
            clearSession()
            handler.postDelayed(tick, 1000)
            return
        }
        val token = ++query
        val timeout = Runnable {
            if (running && query == token) {
                query++
                clearSession()
                handler.postDelayed(tick, 1000)
            }
        }
        handler.postDelayed(timeout, 2000)
        try {
            view.evaluateJavascript(script) { raw ->
                if (running && query == token) {
                    query++
                    handler.removeCallbacks(timeout)
                    update(WebPlaybackSnapshot.parse(raw))
                    handler.postDelayed(tick, 1000)
                }
            }
        } catch (_: RuntimeException) {
            query++
            handler.removeCallbacks(timeout)
            clearSession()
            handler.postDelayed(tick, 1000)
        }
    }

    private fun update(next: WebPlaybackSnapshot?) {
        if (next == null) { clearSession(); return }
        snapshot = next
        if (session == null) {
            session = MediaSession(context, "lampa-browser").apply {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val event = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(
                            android.content.Intent.EXTRA_KEY_EVENT) ?: return false
                        return browser()?.getView()?.dispatchKeyEvent(event) == true
                    }
                }, handler)
                isActive = true
            }
        }
        val uri = artworkUri(next)
        val key = listOf(next.key, next.title, next.subtitle, uri.orEmpty()).joinToString("|")
        if (key != artworkKey) {
            clearArtwork()
            artworkKey = key
            if (uri != null) loadArtwork(uri)
        }
        publishMetadata()
        session?.setPlaybackState(PlaybackState.Builder().setActions(0)
            .setState(next.state, next.position, next.rate, SystemClock.elapsedRealtime()).build())
    }

    private fun artworkUri(value: WebPlaybackSnapshot): String? {
        val url = if (value.artwork.isNotBlank()) value.artwork else
            TMDB.imageUrl(value.tmdbPath)
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return if (uri.host == "image.tmdb.org") uri.buildUpon().scheme("https").build().toString() else uri.toString()
    }

    private fun loadArtwork(uri: String) {
        val token = artworkGeneration
        val target = object : CustomTarget<Bitmap>(720, 720) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (running && artworkGeneration == token) {
                    // Keep Binder's bitmap small; the separately encoded original supplies the phone background.
                    val scale = minOf(1f, 160f / maxOf(resource.width, resource.height))
                    artwork = Bitmap.createScaledBitmap(resource, (resource.width * scale).toInt().coerceAtLeast(1),
                        (resource.height * scale).toInt().coerceAtLeast(1), true)
                    publishMetadata()
                    highQualityJob?.cancel()
                    highQualityJob = HighQualityArtwork(context, uri) { encoded ->
                        if (running && artworkGeneration == token) {
                            highQualityJpeg = encoded
                            publishMetadata()
                        }
                    }
                }
            }
            override fun onLoadCleared(placeholder: Drawable?) {
                if (artworkGeneration == token) { artwork = null; publishMetadata() }
            }
        }
        artworkTarget = target
        Glide.with(context).asBitmap().load(uri).fitCenter().into(target)
    }

    private var publishedKey = ""
    private fun publishMetadata() {
        val value = snapshot ?: return
        val key = listOf(value.key, value.title, value.subtitle, value.duration.toString(),
            artworkKey, (artwork?.generationId ?: 0).toString(), highQualityJpeg?.hashCode().toString()).joinToString("|")
        if (key == publishedKey) return
        val metadata = MediaMetadata.Builder()
            .putText(MediaMetadata.METADATA_KEY_TITLE, value.title)
            .putText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, value.title)
            .putText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, value.subtitle)
            .putText(MediaMetadata.METADATA_KEY_ARTIST, value.subtitle)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, value.duration)
        metadata.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, value.key)
        artworkUri(value)?.let { metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it) }
        highQualityJpeg?.let { metadata.putString(HighQualityArtwork.KEY, it) }
        artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session?.setMetadata(metadata.build())
        publishedKey = key
    }

    private fun clearArtwork() {
        artworkGeneration++
        highQualityJob?.cancel()
        highQualityJob = null
        highQualityJpeg = null
        artworkTarget?.let { Glide.with(context).clear(it) }
        artworkTarget = null
        artwork = null
        artworkKey = ""
    }

    private fun clearSession() {
        snapshot = null
        publishedKey = ""
        session?.isActive = false
        session?.release()
        session = null
        clearArtwork()
    }
}
