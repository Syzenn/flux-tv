package com.flux.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlayerActivity : FluxActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        private const val ZAP_DELAY_MS = 250L
        private const val OVERLAY_MS = 4500L
        private const val MAX_RETRIES = 3
    }

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var player: ExoPlayer

    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var numView: TextView
    private lateinit var logoView: ImageView
    private lateinit var nameView: TextView
    private lateinit var nowView: TextView
    private lateinit var nextView: TextView
    private lateinit var logoInitials: TextView
    private lateinit var timeline: View
    private lateinit var startTimeView: TextView
    private lateinit var endTimeView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView

    private var playlist: List<Int> = emptyList()
    private var index = 0
    private var currentId = -1
    private var currentHls = false
    private var triedOtherFormat = false
    private var retries = 0
    private var stopped = false

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { fadeOutOverlay() }
    private val startPending = Runnable { playCurrent() }
    private val retry = Runnable { startStream(currentId, currentHls) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        prefs = Prefs(this)
        api = prefs.api()
        playlist = Repo.playlist
        if (playlist.isEmpty()) {
            finish()
            return
        }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, playlist.size - 1)

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        numView = findViewById(R.id.num)
        logoView = findViewById(R.id.logo)
        nameView = findViewById(R.id.name)
        nowView = findViewById(R.id.now)
        nextView = findViewById(R.id.next)
        logoInitials = findViewById(R.id.logoInitials)
        timeline = findViewById(R.id.timeline)
        startTimeView = findViewById(R.id.startTime)
        endTimeView = findViewById(R.id.endTime)
        progressView = findViewById(R.id.progress)
        loadingView = findViewById(R.id.loading)
        errorView = findViewById(R.id.error)

        player = buildPlayer()
        playerView.player = player
        playerView.useController = false

        onBackPressedDispatcher.addCallback(this) {
            if (overlay.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideOverlay)
                fadeOutOverlay()
            } else {
                finish()
            }
        }

        showInfo()
        playCurrent()
    }

    private val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

    /** Accès réseau d'une chaîne : certaines chaînes gratuites exigent un navigateur ou une page d'origine précis. */
    private fun sourceFactory(ch: Channel?): DefaultMediaSourceFactory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ch?.userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        ch?.referrer?.takeIf { it.isNotBlank() }?.let { ref ->
            http.setDefaultRequestProperties(mapOf("Referer" to ref, "Origin" to originOf(ref)))
        }
        return DefaultMediaSourceFactory(http, extractors)
    }

    private fun originOf(url: String): String = runCatching {
        val u = java.net.URL(url)
        "${u.protocol}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrDefault(url)

    /** Lecteur réglé pour démarrer vite : petit tampon de départ, gros tampon ensuite. */
    private fun buildPlayer(): ExoPlayer {
        val stable = prefs.stableBuffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (stable) 30_000 else 15_000,
                /* maxBufferMs = */ if (stable) 60_000 else 50_000,
                /* bufferForPlaybackMs = */ if (stable) 2_500 else 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ if (stable) 5_000 else 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Lecture matérielle : la vidéo va directement du décodeur à l'écran.
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(prefs.tunneling))
        }

        return ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loadingView.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (state == Player.STATE_READY) {
                            retries = 0
                            errorView.visibility = View.GONE
                            // On retient le format qui marche avec ce serveur Xtream.
                            if (prefs.isXtream && currentHls != prefs.useHls) prefs.useHls = currentHls
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handleError(error)
                    }
                })
            }
    }

    private fun playCurrent() {
        handler.removeCallbacks(retry)
        currentId = playlist[index]
        triedOtherFormat = false
        retries = 0
        val ch = Repo.byId[currentId]
        val hls = if (ch != null && ch.url.isNotBlank()) {
            ch.url.contains(".m3u8", ignoreCase = true)
        } else {
            prefs.useHls
        }
        startStream(currentId, hls)
    }

    /** [hls] force la lecture en flux HLS ; sinon le lecteur devine d'après l'adresse. */
    private fun startStream(id: Int, hls: Boolean) {
        currentHls = hls
        errorView.visibility = View.GONE
        val ch = Repo.byId[id]
        val url = if (ch != null && ch.url.isNotBlank()) ch.url else api.streamUrl(id, hls)
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaSource(sourceFactory(ch).createMediaSource(item))
        player.prepare()
        player.play()
    }

    private fun handleError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        if (!triedOtherFormat) {
            // Certains serveurs ne servent que le .ts, d'autres que le .m3u8 : on essaie l'autre.
            triedOtherFormat = true
            startStream(currentId, !currentHls)
            return
        }
        if (retries < MAX_RETRIES) {
            retries++
            loadingView.visibility = View.VISIBLE
            handler.postDelayed(retry, 1_500L * retries)
            return
        }
        loadingView.visibility = View.GONE
        errorView.text = "Chaîne indisponible pour le moment\n▲ ▼ pour changer de chaîne"
        errorView.visibility = View.VISIBLE
    }

    private fun zap(delta: Int) {
        if (playlist.size <= 1) return
        index = (index + delta + playlist.size) % playlist.size
        handler.removeCallbacks(retry)
        handler.removeCallbacks(startPending)
        player.stop()
        errorView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        showInfo()
        // Si on enchaîne les appuis, on ne charge que la chaîne où on s'arrête.
        handler.postDelayed(startPending, ZAP_DELAY_MS)
    }

    private fun showInfo() {
        val id = playlist[index]
        val ch = Repo.byId[id]
        numView.text = "Chaîne ${index + 1} sur ${playlist.size}"
        nameView.text = ch?.displayName ?: "Chaîne $id"
        logoInitials.text = TileAdapter.initialsOf(ch?.displayName ?: "TV")
        if (ch != null && ch.icon.isNotBlank()) {
            logoInitials.visibility = View.GONE
            logoView.load(ch.icon) {
                crossfade(false)
                listener(onError = { _, _ -> logoInitials.visibility = View.VISIBLE })
            }
        } else {
            logoView.setImageDrawable(null)
            logoInitials.visibility = View.VISIBLE
        }

        val cached = Epg.cached(id)
        if (!prefs.isXtream) {
            // Les playlists M3U n'ont pas de guide des programmes.
            nowView.text = ch?.categoryId ?: ""
            timeline.visibility = View.GONE
            nextView.visibility = View.GONE
        } else if (cached != null) {
            renderEpg(cached)
        } else {
            nowView.text = ""
            nextView.text = ""
            timeline.visibility = View.INVISIBLE
            lifecycleScope.launch {
                val epg = Epg.get(api, id)
                if (playlist[index] == id) renderEpg(epg)
            }
        }

        fadeInOverlay()
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun fadeInOverlay() {
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.translationY = 24f
            overlay.visibility = View.VISIBLE
        }
        overlay.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun fadeOutOverlay() {
        overlay.animate().cancel()
        overlay.animate().alpha(0f).translationY(24f).setDuration(220)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun renderEpg(epg: List<EpgEntry>) {
        val (cur, next) = Epg.nowAndNext(epg)
        val now = System.currentTimeMillis() / 1000

        if (cur != null) {
            nowView.text = cur.title
            startTimeView.text = Epg.hhmm(cur.start)
            endTimeView.text = Epg.hhmm(cur.end)
            val total = (cur.end - cur.start).coerceAtLeast(1)
            progressView.progress = (((now - cur.start) * 100) / total).toInt().coerceIn(0, 100)
            timeline.visibility = View.VISIBLE
        } else {
            nowView.text = "Pas de programme disponible"
            timeline.visibility = View.GONE
        }
        nextView.text = next?.let { "Ensuite à ${Epg.hhmm(it.start)}  ·  ${it.title}" } ?: ""
        nextView.visibility = if (next == null) View.GONE else View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(+1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (event.repeatCount == 0) {
                    if (overlay.visibility == View.VISIBLE && overlay.alpha > 0.5f && keyCode != KeyEvent.KEYCODE_INFO) {
                        handler.removeCallbacks(hideOverlay)
                        fadeOutOverlay()
                    } else {
                        showInfo()
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (stopped && ::player.isInitialized) {
            stopped = false
            playCurrent()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            player.stop()
            stopped = true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
