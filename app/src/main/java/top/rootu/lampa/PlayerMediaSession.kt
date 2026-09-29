package top.rootu.lampa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/** Publishes the existing player's state and artwork; never owns or restarts playback. */
@UnstableApi
class PlayerMediaSession(private val context: Context, private val player: Player) : Player.Listener {
    private val session = MediaSession(context, "lampa-player")
    private var artworkTarget: CustomTarget<Bitmap>? = null
    private var artwork: Bitmap? = null
    private var mediaKey = ""
    private var generation = 0
    private var closed = false

    init {
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { player.play() }
            override fun onPause() { player.pause() }
            override fun onSeekTo(pos: Long) { if (player.isCurrentMediaItemSeekable) player.seekTo(pos) }
            override fun onFastForward() { if (player.isCurrentMediaItemSeekable) player.seekForward() }
            override fun onRewind() { if (player.isCurrentMediaItemSeekable) player.seekBack() }
            override fun onSkipToNext() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
            override fun onSkipToPrevious() { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
        }, Handler(Looper.getMainLooper()))
        session.isActive = true
        player.addListener(this)
        update()
    }

    override fun onEvents(player: Player, events: Player.Events) = update()

    private fun update() {
        if (closed) return
        val metadata = player.mediaMetadata
        val uri = metadata.artworkUri
        val key = "${player.currentMediaItem?.mediaId}|${metadata.title}|$uri"
        if (key != mediaKey) {
            mediaKey = key
            val token = ++generation
            artworkTarget?.let { Glide.with(context).clear(it) }
            artworkTarget = null
            artwork = null
            if (uri != null && uri.scheme in setOf("https", "http")) {
                val target = object : CustomTarget<Bitmap>(720, 720) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        if (!closed && generation == token) { artwork = resource; publishMetadata() }
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        if (!closed && generation == token) { artwork = null; publishMetadata() }
                    }
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (!closed && generation == token) { artwork = null; publishMetadata() }
                    }
                }
                artworkTarget = target
                Glide.with(context).asBitmap().load(uri).fitCenter().into(target)
            }
        }
        publishMetadata()
        val state = when {
            player.playerError != null -> PlaybackState.STATE_ERROR
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE -> PlaybackState.STATE_STOPPED
            player.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (player.isCurrentMediaItemSeekable) actions = actions or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (player.hasNextMediaItem()) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (player.hasPreviousMediaItem()) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state, player.currentPosition.coerceAtLeast(0),
                if (player.isPlaying) player.playbackParameters.speed else 0f, SystemClock.elapsedRealtime())
            .setBufferedPosition(player.bufferedPosition.coerceAtLeast(0)).build())
    }

    private fun publishMetadata() {
        if (closed) return
        val media = player.mediaMetadata
        val metadata = MediaMetadata.Builder()
            .putText(MediaMetadata.METADATA_KEY_TITLE, media.title ?: "")
            .putText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, media.title ?: "")
        media.artist?.let { metadata.putText(MediaMetadata.METADATA_KEY_ARTIST, it) }
        media.artworkUri?.let { metadata.putString(MediaMetadata.METADATA_KEY_ART_URI, it.toString()) }
        if (player.duration != C.TIME_UNSET) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
        artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
        session.setMetadata(metadata.build())
    }

    fun release() {
        if (closed) return
        closed = true
        generation++
        player.removeListener(this)
        session.isActive = false
        session.release()
        artworkTarget?.let { Glide.with(context).clear(it) }
        artworkTarget = null
        artwork = null
    }
}
