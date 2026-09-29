package top.rootu.lampa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Built-in Media3/ExoPlayer video player for the LAMPA fork.
 *
 * Plays TorrServer / direct streams INSIDE the app (no external player app).
 * AC3/EAC3/DTS/DTS-HD/TrueHD are decoded by the jellyfin FFmpeg audio extension
 * (EXTENSION_RENDERER_MODE_ON => try the device's platform decoder / AC3 passthrough
 * first, fall back to FFmpeg software decode) so sound always works — no transcoding.
 *
 * Launched by MainActivity.launchInternalPlayer() through the SAME `resultLauncher`
 * used for external players, and returns its result via the GENERIC result path
 * (no custom intent action):
 *   - setResult(RESULT_OK,         { data=<current url>, position=<ms>, duration=<ms> })  on stop/back
 *   - setResult(RESULT_FIRST_USER)  when the whole playlist finished (fully watched)
 * MainActivity.handleGenericPlayerResult() reads those extras (milliseconds) and calls
 * resultPlayer(), which pushes `Lampa.Timeline.update` back into the web app so that
 * continue-watching / mark-watched / next-episode keep working.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var mediaSession: PlayerMediaSession? = null
    private lateinit var playerView: PlayerView

    private var startIndex = 0
    private var startPositionMs = 0L
    private var completed = false
    private var resultSent = false

    companion object {
        const val EXTRA_PLAYLIST_JSON = "lampa_playlist"   // JSONArray: [{url,title,subtitles:[{url,label,language}]}]
        const val EXTRA_TITLE = "lampa_title"
        const val EXTRA_START_INDEX = "lampa_start_index"
        const val EXTRA_START_POSITION = "lampa_start_position" // milliseconds
        const val EXTRA_HEADERS = "lampa_headers"               // Array<String>, alternating key,value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        goImmersive()

        startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        val headers = intent.getStringArrayExtra(EXTRA_HEADERS)
        val items = parsePlaylist(intent.getStringExtra(EXTRA_PLAYLIST_JSON))
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        initPlayer(items, headers)
    }

    private fun initPlayer(items: List<MediaItem>, headers: Array<String>?) {
        // Platform decoders / passthrough first (great for AC3/EAC3 on TV boxes + AVR),
        // FFmpeg extension as fallback for whatever the device can't decode.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        headersToMap(headers)?.let { httpFactory.setDefaultRequestProperties(it) }

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(true)
                .build()
        }

        val exo = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    probeVideoRefreshRate(exo)
                } else if (state == Player.STATE_ENDED) {
                    completed = true
                    finishWithResult()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "${error.errorCodeName}: ${error.message ?: ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        playerView.player = exo
        playerView.keepScreenOn = true
        exo.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), startPositionMs)
        exo.playWhenReady = true
        exo.prepare()
        player = exo
        mediaSession = PlayerMediaSession(this, exo)
    }

    private fun parsePlaylist(json: String?): List<MediaItem> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue

            val builder = MediaItem.Builder()
                .setUri(url)
                .setMediaId(url) // must equal the Lampa URL so resultPlayer() can match the playlist item

            val title = o.optString("title").ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
            val metadata = MediaMetadata.Builder().setTitle(title)
            o.optString("artwork").takeIf { it.isNotBlank() }?.let { value ->
                val uri = Uri.parse(value)
                if (uri.scheme in setOf("https", "http")) metadata.setArtworkUri(uri)
            }
            builder.setMediaMetadata(metadata.build())

            o.optJSONArray("subtitles")?.let { subsArr ->
                val subs = ArrayList<MediaItem.SubtitleConfiguration>()
                for (j in 0 until subsArr.length()) {
                    val s = subsArr.optJSONObject(j) ?: continue
                    val subUrl = s.optString("url")
                    if (subUrl.isBlank()) continue
                    subs.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                            .setMimeType(guessSubtitleMime(subUrl))
                            .setLanguage(s.optString("language").ifBlank { null })
                            .setLabel(s.optString("label").ifBlank { null })
                            .build()
                    )
                }
                if (subs.isNotEmpty()) builder.setSubtitleConfigurations(subs)
            }
            out.add(builder.build())
        }
        return out
    }

    private fun guessSubtitleMime(url: String): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            u.endsWith(".ass") || u.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            u.endsWith(".ttml") || u.endsWith(".dfxp") || u.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }
    }

    private fun headersToMap(headers: Array<String>?): Map<String, String>? {
        if (headers == null || headers.size < 2) return null
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < headers.size) {
            val key = headers[i]
            val value = headers[i + 1]
            if (key.isNotBlank()) map[key] = value
            i += 2
        }
        return map.ifEmpty { null }
    }

    private fun probeVideoRefreshRate(exo: ExoPlayer, attempt: Int = 0) {
        val fps = exo.videoFormat?.frameRate ?: -1f
        Log.i("PlayerActivity", "videoFormat frameRate=" + fps + " attempt=" + attempt)
        if (fps.isFinite() && fps > 0f) {
            applyVideoRefreshRate(fps)
            return
        }
        if (attempt < 20) {
            playerView.postDelayed({
                if (player === exo || player == null) probeVideoRefreshRate(exo, attempt + 1)
            }, 500L)
        }
    }

    private fun applyVideoRefreshRate(contentFps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !contentFps.isFinite() || contentFps <= 0f) return

        val display = window.decorView.display ?: return
        val currentMode = display.mode
        val fps = contentFps.toDouble()
        val candidates = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .filter { it.refreshRate <= 120.5f && it.refreshRate + 0.01f >= contentFps }

        val exactTarget = candidates
            .mapNotNull { mode ->
                val multiple = (mode.refreshRate / fps).roundToInt().coerceAtLeast(1)
                val error = abs(mode.refreshRate - fps * multiple)
                if (multiple in 1..6 && error < 0.15) Triple(mode, error, multiple) else null
            }
            .sortedWith(compareBy<Triple<android.view.Display.Mode, Double, Int>> { it.second }
                .thenByDescending { it.first.refreshRate })
            .firstOrNull()
            ?.first

        // Some sources really are odd rates (e.g. 23.000 fps). If the TV has no exact
        // integer-multiple mode, use the highest <=120 Hz mode. The repeat cadence then
        // varies by only one short refresh interval instead of a large 48/60-Hz judder.
        val target = exactTarget ?: candidates.maxByOrNull { it.refreshRate } ?: return

        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == target.modeId) return
        attrs.preferredDisplayModeId = target.modeId
        attrs.preferredRefreshRate = target.refreshRate
        window.attributes = attrs
        Log.i("PlayerActivity", "AFR " + contentFps + " fps -> " + target.refreshRate + " Hz (mode " + target.modeId + ")")
    }

    private fun resetVideoRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == 0 && attrs.preferredRefreshRate == 0f) return
        attrs.preferredDisplayModeId = 0
        attrs.preferredRefreshRate = 0f
        window.attributes = attrs
    }

    private fun finishWithResult() {
        if (resultSent) return
        resultSent = true
        val exo = player
        val currentUrl = exo?.currentMediaItem?.mediaId
        val positionMs = (exo?.currentPosition ?: 0L).coerceAtLeast(0L)
        val durationMs = (exo?.duration ?: 0L).let { if (it == C.TIME_UNSET) 0L else it }

        val data = Intent().apply {
            if (!currentUrl.isNullOrBlank()) data = Uri.parse(currentUrl)
            // handleGenericPlayerResult() reads these as Int milliseconds:
            putExtra("position", positionMs.toInt())
            putExtra("duration", durationMs.toInt())
        }
        setResult(if (completed) Activity.RESULT_FIRST_USER else Activity.RESULT_OK, data)
        releasePlayer()
        if (!isFinishing) finish()
    }

    private fun releasePlayer() {
        resetVideoRefreshRate()
        mediaSession?.release()
        mediaSession = null
        player?.let {
            playerView.player = null
            it.release()
        }
        player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithResult() // completed == false -> reports current position, keeps "continue watching"
    }

    override fun onStop() {
        super.onStop()
        // If the system stops us without a finish (Home / PIP dismissed), still report progress.
        if (!isFinishing) finishWithResult()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
