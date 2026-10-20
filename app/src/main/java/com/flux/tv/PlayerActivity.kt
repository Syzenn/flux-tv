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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

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
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val startPending = Runnable { playCurrent() }
    private val retry = Runnable { startStream(currentId, currentHls) }

    private val epgCache = HashMap<Int, List<EpgEntry>>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

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
        progressView = findViewById(R.id.progress)
        loadingView = findViewById(R.id.loading)
        errorView = findViewById(R.id.error)

        player = buildPlayer()
        playerView.player = player
        playerView.useController = false

        onBackPressedDispatcher.addCallback(this) {
            if (overlay.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideOverlay)
                overlay.visibility = View.GONE
            } else {
                finish()
            }
        }

        showInfo()
        playCurrent()
    }

    /** Lecteur réglé pour démarrer vite : petit tampon de départ, gros tampon ensuite. */
    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
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
                            // On retient le format qui marche avec ce serveur.
                            if (currentHls != prefs.useHls) prefs.useHls = currentHls
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
        startStream(currentId, prefs.useHls)
    }

    private fun startStream(id: Int, hls: Boolean) {
        currentHls = hls
        errorView.visibility = View.GONE
        val item = MediaItem.Builder()
            .setUri(api.streamUrl(id, hls))
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
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
            // Certains serveurs ne servent que le .ts, d'autres que le .m3u8.
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
        numView.text = (index + 1).toString()
        nameView.text = ch?.displayName ?: "Chaîne $id"
        if (ch != null && ch.icon.isNotBlank()) {
            logoView.load(ch.icon) { crossfade(false) }
        } else {
            logoView.setImageDrawable(null)
        }

        val cached = epgCache[id]
        if (cached != null) {
            renderEpg(cached)
        } else {
            nowView.text = ""
            nextView.text = ""
            progressView.visibility = View.INVISIBLE
            lifecycleScope.launch {
                val epg = withContext(Dispatchers.IO) {
                    runCatching { api.shortEpg(id) }.getOrDefault(emptyList())
                }
                epgCache[id] = epg
                if (playlist[index] == id) renderEpg(epg)
            }
        }

        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, OVERLAY_MS)
    }

    private fun renderEpg(epg: List<EpgEntry>) {
        val now = System.currentTimeMillis() / 1000
        val currentIndex = epg.indexOfFirst { it.start <= now && it.end > now }
        val cur = epg.getOrNull(currentIndex)
        val next = if (currentIndex >= 0) epg.getOrNull(currentIndex + 1) else epg.firstOrNull { it.start > now }

        if (cur != null) {
            nowView.text = "${hhmm(cur.start)} – ${hhmm(cur.end)}   ${cur.title}"
            val total = (cur.end - cur.start).coerceAtLeast(1)
            progressView.progress = (((now - cur.start) * 100) / total).toInt().coerceIn(0, 100)
            progressView.visibility = View.VISIBLE
        } else {
            nowView.text = "Pas de programme disponible"
            progressView.visibility = View.INVISIBLE
        }
        nextView.text = next?.let { "Ensuite ${hhmm(it.start)}   ${it.title}" } ?: ""
    }

    private fun hhmm(seconds: Long): String = timeFormat.format(Date(seconds * 1000))

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
                    if (overlay.visibility == View.VISIBLE && keyCode != KeyEvent.KEYCODE_INFO) {
                        handler.removeCallbacks(hideOverlay)
                        overlay.visibility = View.GONE
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
