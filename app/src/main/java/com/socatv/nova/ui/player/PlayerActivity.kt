@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.socatv.nova.ui.player

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.StreamHealth
import com.socatv.nova.data.model.WatchHistory
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityPlayerBinding
import com.socatv.nova.utils.GoveeClient
import com.socatv.nova.utils.KonamiDetector
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.TimeTheme
import kotlinx.coroutines.launch
import com.socatv.nova.utils.AntiBufferEngine
import com.socatv.nova.utils.setFocusBorder
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var player2: ExoPlayer? = null
    private lateinit var repository: IptvRepository
    private lateinit var konamiDetector: KonamiDetector

    private var streamId: String = ""
    private var streamName: String = ""
    private var streamIcon: String? = null
    private var streamUrl: String = ""
    private var contentType: String = "live"

    // Retry state — silent, exponential backoff
    private var retryCount = 0
    private val RETRY_DELAYS_MS = longArrayOf(200, 500, 1_000, 2_000, 4_000, 8_000)

    // Playback speed
    private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private val SPEED_LABELS = arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×")
    private var speedIndex = 2 // starts at 1×

    // Zoom modes
    private val ZOOM_MODES = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val ZOOM_LABELS = arrayOf("Fit", "Zoom", "Fill")
    private var zoomIndex = 0

    // Sleep timer
    private var sleepTimerMs = 0L
    private var sleepTimerStart = 0L

    // Channel list for quick switch (live TV)
    private var channelIndex = 0
    private var previousChannelIndex = -1
    private var previousStreamId = ""

    // Number-key direct channel entry
    private val numberBuffer = StringBuilder()
    private val numberEntryHandler = Handler(Looper.getMainLooper())
    private val numberCommitRunnable = Runnable { commitNumberEntry() }

    // Channel strip adapter (horizontal list in overlay)
    private lateinit var channelStripAdapter: ChannelStripAdapter

    // Sleep timer runnable reference for reliable cancellation
    private var sleepTimerRunnable: Runnable? = null

    private var antiBufferEngine: AntiBufferEngine? = null
    private var sharedOkHttpClient: OkHttpClient? = null

    private val healthSamples = mutableListOf<StreamHealth>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val seekUpdateHandler = Handler(Looper.getMainLooper())

    private var isOverlayVisible = false
    private var isEpgVisible = false
    private var isHealthVisible = false
    private var isSplitScreen = false
    private var retroModeEnabled = false
    private var bingeCountdownActive = false

    private val isLive get() = contentType == "live"
    private val isVod  get() = contentType == "vod" || contentType == "series"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen awake and immersive fullscreen throughout playback
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        repository = IptvRepository(NovaApp.instance.database)
        konamiDetector = KonamiDetector { enableRetroMode() }

        streamId   = intent.getStringExtra("stream_id")    ?: ""
        streamName = intent.getStringExtra("stream_name")  ?: "Unknown"
        streamIcon = intent.getStringExtra("stream_icon")
        streamUrl  = intent.getStringExtra("stream_url")   ?: ""
        contentType = intent.getStringExtra("content_type") ?: "live"

        buildAndStartPlayer()
        setupOverlay()
        setupChannelStrip()
        setupEpgOverlay()
        setupHealthOverlay()
        setupBingeCountdown()
        setupSplitScreenButton()

        binding.tvChannelName.text = streamName
        if (!streamIcon.isNullOrBlank()) {
            Glide.with(this).load(streamIcon).into(binding.ivChannelIcon)
            binding.ivChannelIcon.visibility = View.VISIBLE
        }

        channelIndex = intent.getIntExtra("channel_index", 0)

        loadEpg()
        startHealthMonitoring()
        saveWatchHistory()
    }

    // ==================== PLAYER BUILD ====================

    // ==================== PLAYER BUILD ====================

    // Shared client so preWarm and the player reuse the same connection pool
    private fun getOrBuildOkHttpClient(): OkHttpClient {
        if (sharedOkHttpClient == null) {
            sharedOkHttpClient = OkHttpClient.Builder()
                // Prefer HTTP/2 for multiplexed segment fetching; fall back to HTTP/1.1
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                // Fail fast on connect — every saved second is playback time
                .connectTimeout(8, TimeUnit.SECONDS)
                // Live segments should arrive in <10 s; VOD slightly more lenient
                .readTimeout(if (isLive) 18 else 25, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // Keep 12 connections alive for 6 min — covers segment prefetch bursts
                .connectionPool(ConnectionPool(12, 6, TimeUnit.MINUTES))
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "VLC/3.0 LibVLC/3.0 (IPTV)")
                            .header("Connection", "keep-alive")
                            .header("Accept", "*/*")
                            .header("Icy-MetaData", "1")
                            .build()
                    )
                }
                .build()
        }
        return sharedOkHttpClient!!
    }

    private fun buildLoadControl(): DefaultLoadControl {
        return if (isLive) {
            DefaultLoadControl.Builder()
                // minBuf=4 s → don't start playing until we have a real cushion
                // maxBuf=25 s → build a large runway so network jitter doesn't stall us
                // plyBuf=1.2 s → begin playback quickly once we hit min
                // reBuf=3 s → resume after a stall once we have 3 s back
                .setBufferDurationsMs(4_000, 25_000, 1_200, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                // Hard cap at 60 MB — prevents OOM on fire tv while keeping plenty of segments
                .setTargetBufferBytes(60 * 1024 * 1024)
                .build()
        } else {
            DefaultLoadControl.Builder()
                // VOD: deep buffer = no interruptions during 4K/1080p playback
                .setBufferDurationsMs(8_000, 90_000, 3_000, 6_000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setTargetBufferBytes(200 * 1024 * 1024)
                .build()
        }
    }

    private fun buildTrackSelector(): DefaultTrackSelector {
        val params = DefaultTrackSelector.Parameters.Builder(this)
            .setPreferredAudioLanguage(Prefs.subtitleLanguage)
            .setPreferredTextLanguage(if (Prefs.subtitleEnabled) Prefs.subtitleLanguage else null)
            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
            .setAllowAudioMixedDecoderSupportAdaptiveness(true)
            .setAllowMultipleAdaptiveSelections(true)
            // Let ABR pick the best quality; don't artificially force highest bitrate
            .setForceHighestSupportedBitrate(false)
            .build()
        return DefaultTrackSelector(this, params)
    }

    private fun buildMediaItem(url: String = streamUrl): MediaItem {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".ts") -> {
                MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
            }
            lower.endsWith(".mpd") -> {
                MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
            }
            lower.endsWith(".m3u8") || isLive -> {
                val builder = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
                if (isLive) {
                    builder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            // 3 s behind live edge — enough cushion without too much latency
                            .setTargetOffsetMs(3_000)
                            .setMinOffsetMs(500)
                            // 30 s max drift before ExoPlayer seeks forward
                            .setMaxOffsetMs(30_000)
                            // Narrow speed window so live-edge control doesn't fight AntiBufferEngine
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.03f)
                            .build()
                    )
                }
                builder.build()
            }
            else -> MediaItem.fromUri(url)
        }
    }

    private fun buildAndStartPlayer() {
        val okHttpClient = getOrBuildOkHttpClient()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        // Seed the bandwidth estimator at a realistic WiFi baseline (8 Mbps).
        // Without this, ExoPlayer starts with a ~1 Mbps assumption and slowly
        // ramps up, wasting several seconds on artificially low quality tracks.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(8_000_000L)
            .setResetOnNetworkTypeChange(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(3_000)

        val trackSelector = buildTrackSelector()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(buildLoadControl())
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                binding.playerView.player = exo
                binding.playerView.useController = false

                exo.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )

                exo.addListener(playerListener)
                exo.setMediaItem(buildMediaItem())
                exo.prepare()
                exo.playWhenReady = true
            }

        // Pre-warm TCP connection in background (DNS resolution + connection setup
        // completes before the first HLS playlist request fires)
        lifecycleScope.launch {
            AntiBufferEngine.preWarm(streamUrl, okHttpClient)
        }

        // Start anti-buffer monitoring after player is created
        antiBufferEngine = AntiBufferEngine(player!!, isLive, mainHandler).also { it.start() }
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> showBuffering(true)
                Player.STATE_READY     -> {
                    showBuffering(false)
                    binding.tvError.visibility = View.GONE
                    retryCount = 0
                    syncGoveeColor()
                    if (isVod) startSeekUpdates()
                    // Update now-playing from EPG
                }
                Player.STATE_ENDED     -> {
                    showBuffering(false)
                    if (isVod && Prefs.bingeCountdownEnabled) showBingeCountdown()
                    // Live stream ended = provider issue → silent retry
                    if (isLive) silentRetry()
                }
                Player.STATE_IDLE      -> { /* do nothing */ }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                showBuffering(false)
                retryCount = 0
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            showBuffering(false)
            // NEVER show error message to user — just retry silently
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    // Live stream: we fell behind — jump to live edge instantly
                    player?.seekToDefaultPosition()
                    player?.prepare()
                    showBuffering(true)
                }
                else -> silentRetry()
            }
        }
    }

    private fun silentRetry() {
        val delayMs = RETRY_DELAYS_MS[minOf(retryCount, RETRY_DELAYS_MS.size - 1)]
        retryCount++
        showBuffering(true)
        binding.tvError.visibility = View.GONE

        mainHandler.postDelayed({
            if (!isDestroyed) {
                val p = player ?: return@postDelayed
                // After 3 failures try .ts; after 6 swing back to .m3u8
                val url = when {
                    retryCount > 3 && streamUrl.endsWith(".m3u8", true) ->
                        streamUrl.removeSuffix(".m3u8") + ".ts"
                    retryCount > 6 && streamUrl.endsWith(".ts", true) ->
                        streamUrl.removeSuffix(".ts") + ".m3u8"
                    else -> streamUrl
                }
                // Reset engine so the new attempt starts with a clean stall counter
                antiBufferEngine?.stop()
                antiBufferEngine = AntiBufferEngine(p, isLive, mainHandler).also { it.start() }
                p.setMediaItem(buildMediaItem(url))
                p.prepare()
                p.playWhenReady = true
            }
        }, delayMs)
    }

    // ==================== SEEK BAR ====================

    private val seekUpdateRunnable = object : Runnable {
        override fun run() {
            updateSeekBar()
            seekUpdateHandler.postDelayed(this, 1_000)
        }
    }

    private fun startSeekUpdates() {
        seekUpdateHandler.removeCallbacks(seekUpdateRunnable)
        seekUpdateHandler.postDelayed(seekUpdateRunnable, 1_000)
    }

    private fun stopSeekUpdates() = seekUpdateHandler.removeCallbacks(seekUpdateRunnable)

    private fun updateSeekBar() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0 && isOverlayVisible) {
            binding.seekBar.max = 1000
            binding.seekBar.progress = ((pos * 1000) / dur).toInt()
            binding.tvPosition.text = formatMs(pos)
            binding.tvDuration.text = formatMs(dur)
        }
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ==================== OVERLAY ====================

    private fun setupOverlay() {
        binding.overlayTop.visibility = View.GONE
        binding.overlayBottom.visibility = View.GONE
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAudioTrack.setOnClickListener { showAudioTrackMenu() }
        binding.btnSubtitles.setOnClickListener { showSubtitleMenu() }

        // Speed button — VOD only
        if (isVod) binding.btnSpeed.visibility = View.VISIBLE
        binding.btnSpeed.setOnClickListener { showSpeedMenu() }

        // Zoom button
        binding.btnZoom.setOnClickListener { cycleZoom() }

        // Sleep timer button
        binding.btnSleep.setOnClickListener { showSleepTimerMenu() }

        // Favorites button
        binding.btnFavorite.text = if (Prefs.isFavorite(streamId)) "★" else "☆"
        binding.btnFavorite.setOnClickListener {
            val isFav = Prefs.toggleFavorite(streamId)
            binding.btnFavorite.text = if (isFav) "★" else "☆"
            Toast.makeText(this, if (isFav) "Added to Favorites ★" else "Removed from Favorites", Toast.LENGTH_SHORT).show()
        }

        // External player button
        binding.btnExternal.setOnClickListener { showExternalPlayerMenu() }

        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = player?.duration ?: return
                    if (dur > 0) player?.seekTo((dur * progress) / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) = autoHideOverlay(30_000)
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) = autoHideOverlay(4_000)
        })
    }

    private fun toggleOverlay() {
        isOverlayVisible = !isOverlayVisible
        if (isOverlayVisible) {
            binding.overlayTop.alpha = 0f
            binding.overlayTop.visibility = View.VISIBLE
            binding.overlayTop.animate().alpha(1f).setDuration(180).start()
            if (isVod) {
                binding.seekBar.visibility = View.VISIBLE
                binding.overlayBottom.alpha = 0f
                binding.overlayBottom.visibility = View.VISIBLE
                binding.overlayBottom.animate().alpha(1f).setDuration(180).start()
                updateSeekBar()
            }
            binding.tvNowPlaying.visibility = if (isLive) View.VISIBLE else View.GONE

            // Show channel strip for live TV so user can zap without leaving fullscreen
            if (isLive) showChannelStrip()

            // Move D-pad focus to the back button so user can immediately navigate the overlay
            binding.overlayTop.postDelayed({ binding.btnBack.requestFocus() }, 180)

            autoHideOverlay(10_000)
        } else {
            binding.channelStripContainer.visibility = View.GONE
            binding.overlayTop.animate().alpha(0f).setDuration(180).withEndAction {
                binding.overlayTop.visibility = View.GONE
            }.start()
            binding.overlayBottom.animate().alpha(0f).setDuration(180).withEndAction {
                binding.overlayBottom.visibility = View.GONE
            }.start()
        }
    }

    private fun autoHideOverlay(delayMs: Long = 10_000) {
        overlayHandler.removeCallbacksAndMessages(null)
        overlayHandler.postDelayed({
            // Don't hide if any overlay button currently has focus
            val focused = binding.overlayTop.findFocus()
            if (isOverlayVisible && focused == null) {
                toggleOverlay()
            } else if (isOverlayVisible) {
                // Retry in 5 s while user is actively using overlay
                autoHideOverlay(5_000)
            }
        }, delayMs)
    }

    // ==================== AUDIO / SUBTITLE MENUS ====================

    private fun showAudioTrackMenu() {
        val groups = player?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: return
        if (groups.isEmpty()) { Toast.makeText(this, "No alternate audio tracks", Toast.LENGTH_SHORT).show(); return }
        val names = groups.mapIndexed { i, g ->
            val fmt = g.getTrackFormat(0)
            buildString {
                append(if (g.isSelected) "✓ " else "  ")
                append(fmt.language?.uppercase() ?: "Track ${i + 1}")
                if (fmt.channelCount > 0) append(" ${fmt.channelCount}ch")
                if (fmt.bitrate > 0) append(" ${fmt.bitrate / 1000}kbps")
            }
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Audio Track")
            .setItems(names) { _, idx ->
                player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[idx].mediaTrackGroup, 0))
                    .build()
            }.show()
    }

    private fun showSubtitleMenu() {
        val groups = player?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_TEXT } ?: return
        if (groups.isEmpty()) { Toast.makeText(this, "No embedded subtitles", Toast.LENGTH_SHORT).show(); return }
        val names = (listOf("  Off") + groups.mapIndexed { i, g ->
            val fmt = g.getTrackFormat(0)
            buildString {
                append(if (g.isSelected) "✓ " else "  ")
                append(fmt.language?.uppercase() ?: "Track ${i + 1}")
            }
        }).toTypedArray()
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Subtitles")
            .setItems(names) { _, idx ->
                if (idx == 0) {
                    player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                        .setDisabledTrackTypes(setOf<Int>(C.TRACK_TYPE_TEXT)).build()
                } else {
                    val g = groups[idx - 1]
                    player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                        .setDisabledTrackTypes(emptySet<Int>())
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
                        .build()
                }
            }.show()
    }

    // ==================== EPG ====================

    private fun setupEpgOverlay() { binding.epgOverlay.visibility = View.GONE }

    private fun toggleEpgOverlay() {
        isEpgVisible = !isEpgVisible
        binding.epgOverlay.visibility = if (isEpgVisible) View.VISIBLE else View.GONE
    }

    private fun decodeBase64Title(raw: String): String = try {
        String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT)).trim()
    } catch (_: Exception) { raw }

    private fun loadEpg() {
        if (!isLive || streamId.isBlank()) return
        lifecycleScope.launch {
            repository.getShortEpg(streamId).fold(
                onSuccess = { entries ->
                    binding.epgOverlay.setEpgData(entries)
                    entries.firstOrNull { it.nowPlaying == 1 }?.title?.let { rawTitle ->
                        binding.tvNowPlaying.text = decodeBase64Title(rawTitle)
                    }
                },
                onFailure = { /* silent */ }
            )
        }
    }

    // ==================== HEALTH ====================

    private fun setupHealthOverlay() {
        binding.healthOverlay.visibility = View.GONE
        binding.btnHealth.setOnClickListener { toggleHealthOverlay() }
    }

    private fun toggleHealthOverlay() {
        isHealthVisible = !isHealthVisible
        binding.healthOverlay.visibility = if (isHealthVisible) View.VISIBLE else View.GONE
    }

    private fun startHealthMonitoring() {
        val runnable = object : Runnable {
            override fun run() {
                val p = player ?: return
                val sample = StreamHealth(
                    timestampMs   = System.currentTimeMillis(),
                    bitrateKbps   = (p.videoFormat?.bitrate ?: 0) / 1000,
                    bufferMs      = p.totalBufferedDuration,
                    fps           = p.videoFormat?.frameRate ?: 0f,
                    droppedFrames = 0
                )
                healthSamples.add(sample)
                if (healthSamples.size > 60) healthSamples.removeAt(0)
                binding.healthOverlay.updateSamples(healthSamples.toList())
                mainHandler.postDelayed(this, 2_000)
            }
        }
        mainHandler.postDelayed(runnable, 2_000)
    }

    // ==================== BINGE COUNTDOWN ====================

    private fun setupBingeCountdown() {
        binding.bingeCountdown.visibility = View.GONE
        binding.bingeCountdown.onCountdownComplete = {
            Toast.makeText(this, "Playing next…", Toast.LENGTH_SHORT).show()
            hideBingeCountdown()
        }
        binding.bingeCountdown.onCancelCountdown = { hideBingeCountdown() }
    }

    private fun showBingeCountdown() {
        if (!bingeCountdownActive) {
            bingeCountdownActive = true
            binding.bingeCountdown.visibility = View.VISIBLE
            binding.bingeCountdown.startCountdown(10)
        }
    }

    private fun hideBingeCountdown() {
        bingeCountdownActive = false
        binding.bingeCountdown.visibility = View.GONE
        binding.bingeCountdown.cancelCountdown()
    }

    // ==================== SPLIT SCREEN ====================

    private fun setupSplitScreenButton() {
        binding.btnSplitScreen.setOnClickListener { toggleSplitScreen() }
    }

    private fun toggleSplitScreen() {
        isSplitScreen = !isSplitScreen
        val screenW = resources.displayMetrics.widthPixels

        if (isSplitScreen) {
            // Shrink player 1 to left half
            binding.playerView.layoutParams =
                (binding.playerView.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                    it.width   = screenW / 2
                    it.gravity = android.view.Gravity.START
                }
            // Show player 2 on right half
            binding.splitContainer.layoutParams =
                (binding.splitContainer.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                    it.width   = screenW / 2
                    it.gravity = android.view.Gravity.END
                }
            binding.splitContainer.visibility = View.VISIBLE
            binding.btnSplitScreen.text = "⊞ Exit Split"
            if (player2 == null) setupPlayer2()
        } else {
            // Restore full screen
            binding.playerView.layoutParams =
                (binding.playerView.layoutParams as android.widget.FrameLayout.LayoutParams).also {
                    it.width   = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    it.gravity = android.view.Gravity.NO_GRAVITY
                }
            binding.splitContainer.visibility = View.GONE
            binding.btnSplitScreen.text = "Split"
            player2?.stop()
            player2?.release()
            player2 = null
        }
    }

    private fun setupPlayer2() {
        // Use next channel so the two panels show different content
        val list      = com.socatv.nova.ui.browse.BrowseActivity.channelCache
        val nextIdx   = if (list.size > 1) (channelIndex + 1) % list.size else channelIndex
        val nextCh    = list.getOrNull(nextIdx)
        val nextUrl   = nextCh?.directSource?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: nextCh?.let { repository.buildStreamUrl(it.streamId) }
            ?: streamUrl

        val client  = getOrBuildOkHttpClient()
        val factory = DefaultMediaSourceFactory(
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client))

        player2 = ExoPlayer.Builder(this)
            .setMediaSourceFactory(factory)
            .setLoadControl(buildLoadControl())
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also { exo ->
                binding.playerView2.player = exo
                binding.playerView2.useController = false
                exo.setMediaItem(buildMediaItem(nextUrl))
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    // ==================== MISC ====================

    private fun showBuffering(show: Boolean) {
        binding.progressBuffering.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun syncGoveeColor() {
        if (!Prefs.goveeEnabled || Prefs.goveeIp.isBlank()) return
        val scheme = TimeTheme.getScheme()
        lifecycleScope.launch { GoveeClient.setColor(Prefs.goveeIp, scheme.primary) }
    }

    private fun saveWatchHistory() {
        if (streamId.isBlank()) return
        lifecycleScope.launch {
            repository.updateWatchHistory(WatchHistory(
                contentId    = streamId,
                contentType  = contentType,
                title        = streamName,
                thumbnailUrl = streamIcon,
                watchedAt    = System.currentTimeMillis(),
                profileId    = Prefs.activeProfileId
            ))
        }
    }

    // ==================== SPEED CONTROL ====================

    private fun showSpeedMenu() {
        val items = SPEED_LABELS.mapIndexed { i, label ->
            "${if (i == speedIndex) "✓ " else "  "}$label"
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Playback Speed")
            .setItems(items) { _, idx ->
                speedIndex = idx
                player?.setPlaybackSpeed(SPEEDS[idx])
                binding.btnSpeed.text = SPEED_LABELS[idx]
            }.show()
    }

    // ==================== EXTERNAL PLAYER ====================

    private fun showExternalPlayerMenu() {
        val options = arrayOf("VLC", "MX Player", "MX Player Pro", "Other Video Player")
        val packages = arrayOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", null)
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Open In External Player")
            .setItems(options) { _, idx ->
                launchExternalPlayer(packages[idx])
            }.show()
    }

    private fun launchExternalPlayer(pkg: String?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            putExtra("title", streamName)
            if (pkg != null) setPackage(pkg)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Specific app not installed — launch chooser
            try {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(streamUrl), "video/*")
                        putExtra("title", streamName)
                    }, "Open with…"
                ))
            } catch (_: Exception) {
                Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== ZOOM / ASPECT RATIO ====================

    private fun cycleZoom() {
        zoomIndex = (zoomIndex + 1) % ZOOM_MODES.size
        binding.playerView.resizeMode = ZOOM_MODES[zoomIndex]
        binding.btnZoom.text = ZOOM_LABELS[zoomIndex]
        Toast.makeText(this, "Aspect: ${ZOOM_LABELS[zoomIndex]}", Toast.LENGTH_SHORT).show()
    }

    // ==================== SLEEP TIMER ====================

    private fun showSleepTimerMenu() {
        val options = arrayOf("Off", "15 min", "30 min", "1 hour", "2 hours")
        val minutes = intArrayOf(0, 15, 30, 60, 120)
        AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Sleep Timer")
            .setItems(options) { _, idx ->
                setSleepTimer(minutes[idx])
            }.show()
    }

    private fun setSleepTimer(minutes: Int) {
        // Cancel any existing timer via reference (not token — tokens don't work on anonymous lambdas)
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        if (minutes == 0) {
            sleepTimerMs = 0
            binding.btnSleep.text = "⏱"
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
            return
        }
        sleepTimerMs = minutes * 60_000L
        sleepTimerStart = System.currentTimeMillis()
        binding.btnSleep.text = "${minutes}m"
        Toast.makeText(this, "Sleep in $minutes min", Toast.LENGTH_SHORT).show()
        val r = Runnable { player?.pause(); finish() }
        sleepTimerRunnable = r
        mainHandler.postDelayed(r, sleepTimerMs)
    }

    // ==================== CHANNEL STRIP ====================

    private fun setupChannelStrip() {
        channelStripAdapter = ChannelStripAdapter { ch ->
            binding.channelStripContainer.visibility = View.GONE
            switchToChannel(com.socatv.nova.ui.browse.BrowseActivity.channelCache.indexOf(ch).coerceAtLeast(0), ch)
        }
        binding.rvChannelStrip.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this@PlayerActivity, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            adapter = channelStripAdapter
        }
    }

    private fun showChannelStrip() {
        val list = com.socatv.nova.ui.browse.BrowseActivity.channelCache
        if (list.isEmpty() || !isLive) return
        val window = 8
        val start  = (channelIndex - window / 2 + list.size) % list.size
        val strip  = (0 until window + 1).map { list[(start + it) % list.size] }
        channelStripAdapter.submit(strip, list[(start + window / 2) % list.size])
        binding.channelStripContainer.visibility = View.VISIBLE
        // Scroll to current channel in strip
        binding.rvChannelStrip.post {
            (binding.rvChannelStrip.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(window / 2, 0)
        }
    }

    // ==================== NUMBER-KEY DIRECT CHANNEL ENTRY ====================

    private fun onNumberKey(digit: Int) {
        if (!isLive) return
        numberBuffer.append(digit)
        binding.tvNumberEntry.text = numberBuffer.toString()
        binding.tvNumberEntry.visibility = View.VISIBLE
        numberEntryHandler.removeCallbacks(numberCommitRunnable)
        numberEntryHandler.postDelayed(numberCommitRunnable, 1_800)
    }

    private fun commitNumberEntry() {
        val num = numberBuffer.toString().toIntOrNull() ?: run {
            binding.tvNumberEntry.visibility = View.GONE; numberBuffer.clear(); return
        }
        binding.tvNumberEntry.visibility = View.GONE
        numberBuffer.clear()
        val list = com.socatv.nova.ui.browse.BrowseActivity.channelCache
        val idx = list.indexOfFirst { it.num == num }
        if (idx >= 0) {
            switchToChannel(idx, list[idx])
        } else {
            Toast.makeText(this, "Channel $num not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToChannel(index: Int, ch: com.socatv.nova.data.model.Channel) {
        previousChannelIndex = channelIndex
        previousStreamId = streamId
        channelIndex = index
        streamId   = ch.streamId
        streamName = ch.name
        streamIcon = ch.streamIcon
        streamUrl  = ch.directSource?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: repository.buildStreamUrl(ch.streamId)
        retryCount = 0
        binding.tvChannelName.text = streamName
        if (!streamIcon.isNullOrBlank()) {
            Glide.with(this).load(streamIcon).into(binding.ivChannelIcon)
            binding.ivChannelIcon.visibility = View.VISIBLE
        }
        showBuffering(true)
        antiBufferEngine?.stop()
        antiBufferEngine = AntiBufferEngine(player!!, isLive, mainHandler).also { it.start() }
        player?.setMediaItem(buildMediaItem(streamUrl))
        player?.prepare()
        player?.playWhenReady = true
        loadEpg()
        saveWatchHistory()
        isOverlayVisible = true
        binding.overlayTop.visibility = View.VISIBLE
        binding.overlayTop.alpha = 1f
        binding.btnFavorite.text = if (Prefs.isFavorite(streamId)) "★" else "☆"
        autoHideOverlay(3_000)
    }

    private fun jumpToPreviousChannel() {
        if (!isLive || previousChannelIndex < 0) return
        val list = com.socatv.nova.ui.browse.BrowseActivity.channelCache
        if (previousChannelIndex >= list.size) return
        val ch = list[previousChannelIndex]
        switchToChannel(previousChannelIndex, ch)
    }

    // ==================== CHANNEL SWITCH (LIVE TV) ====================

    private fun switchChannel(delta: Int) {
        val list = com.socatv.nova.ui.browse.BrowseActivity.channelCache
        if (list.isEmpty()) return
        val newIndex = (channelIndex + delta + list.size) % list.size
        switchToChannel(newIndex, list[newIndex])
    }

    // ==================== PICTURE IN PICTURE ====================

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLive && player?.isPlaying == true) enterPiP()
    }

    override fun onPictureInPictureModeChanged(inPiP: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPiP, config)
        if (inPiP) {
            // Hide all overlays when entering PiP
            binding.overlayTop.visibility = View.GONE
            binding.overlayBottom.visibility = View.GONE
            binding.healthOverlay.visibility = View.GONE
            binding.channelStripContainer.visibility = View.GONE
            isOverlayVisible = false
            isHealthVisible = false
        }
        // When leaving PiP: overlays stay hidden — user presses OK/INFO to bring them back
    }

    private fun enableRetroMode() {
        if (retroModeEnabled) return
        retroModeEnabled = true
        binding.retroScanlines.visibility = View.VISIBLE
        mainHandler.postDelayed({
            binding.retroScanlines.visibility = View.GONE
            retroModeEnabled = false
        }, 8_000L)
    }

    // ==================== KEY DISPATCH ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (konamiDetector.onKeyDown(event.keyCode)) return true

        return when (event.keyCode) {
            KeyEvent.KEYCODE_INFO -> {
                // First INFO press: show main overlay. Second INFO press: toggle EPG strip (live only)
                when {
                    !isOverlayVisible        -> { toggleOverlay(); true }
                    isLive && !isEpgVisible  -> { toggleEpgOverlay(); true }
                    isLive && isEpgVisible   -> { toggleEpgOverlay(); true }
                    else                     -> { toggleOverlay(); true }
                }
            }

            KeyEvent.KEYCODE_MENU -> { toggleOverlay(); true }

            KeyEvent.KEYCODE_BACK -> {
                when {
                    isEpgVisible     -> { toggleEpgOverlay() }
                    isHealthVisible  -> { toggleHealthOverlay() }
                    isOverlayVisible -> toggleOverlay()
                    else             -> finish()
                }
                true
            }

            // DPAD_CENTER / ENTER:
            // When overlay is HIDDEN → show it.
            // When overlay is VISIBLE → return false so the focused button fires its click.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (!isOverlayVisible) { toggleOverlay(); true } else false
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                true
            }

            // FAST_FORWARD / REWIND media keys always seek (dedicated hardware buttons)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isVod) {
                    player?.seekTo((player!!.currentPosition + 30_000).coerceAtMost(player!!.duration))
                    updateSeekBar(); autoHideOverlay(4_000); true
                } else false
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isVod) {
                    player?.seekTo((player!!.currentPosition - 30_000).coerceAtLeast(0))
                    updateSeekBar(); autoHideOverlay(4_000); true
                } else false
            }

            // DPAD_RIGHT: seek only when overlay is hidden (overlay open = navigate buttons)
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOverlayVisible && isVod) {
                    player?.seekTo((player!!.currentPosition + 10_000).coerceAtMost(player!!.duration))
                    updateSeekBar(); true
                } else false
            }

            // DPAD_LEFT: seek only when overlay is hidden
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isOverlayVisible && isVod) {
                    player?.seekTo((player!!.currentPosition - 10_000).coerceAtLeast(0))
                    updateSeekBar(); true
                } else false
            }

            // DPAD_UP/DOWN: channel switch only when overlay is HIDDEN
            // When overlay is open, let the view system handle focus movement
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive && !isOverlayVisible) { switchChannel(-1); true } else false
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive && !isOverlayVisible) { switchChannel(+1); true } else false
            }

            // Number keys — direct channel entry (live TV)
            KeyEvent.KEYCODE_0 -> { onNumberKey(0); true }
            KeyEvent.KEYCODE_1 -> { onNumberKey(1); true }
            KeyEvent.KEYCODE_2 -> { onNumberKey(2); true }
            KeyEvent.KEYCODE_3 -> { onNumberKey(3); true }
            KeyEvent.KEYCODE_4 -> { onNumberKey(4); true }
            KeyEvent.KEYCODE_5 -> { onNumberKey(5); true }
            KeyEvent.KEYCODE_6 -> { onNumberKey(6); true }
            KeyEvent.KEYCODE_7 -> { onNumberKey(7); true }
            KeyEvent.KEYCODE_8 -> { onNumberKey(8); true }
            KeyEvent.KEYCODE_9 -> { onNumberKey(9); true }

            // Previous channel toggle (recall)
            KeyEvent.KEYCODE_BOOKMARK,
            KeyEvent.KEYCODE_LAST_CHANNEL -> { jumpToPreviousChannel(); true }

            KeyEvent.KEYCODE_Z -> { cycleZoom(); true }

            KeyEvent.KEYCODE_P -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { enterPiP(); true }
                else false
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    // ==================== LIFECYCLE ====================

    override fun onPause() {
        super.onPause()
        if (isLive) player?.pause() else {
            // VOD/Series: save position, pause
            player?.let { p ->
                Prefs.saveProgress(streamId, p.currentPosition, p.duration.coerceAtLeast(0L))
                p.pause()
            }
        }
        stopSeekUpdates()
    }

    override fun onResume() {
        super.onResume()
        // Restore VOD position
        if (isVod) {
            val (savedPos, _) = Prefs.getProgress(streamId)
            if (savedPos > 0 && (player?.currentPosition ?: 0L) < 1000L) {
                player?.seekTo(savedPos)
            }
        }
        player?.play()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onDestroy() {
        super.onDestroy()
        antiBufferEngine?.stop()
        player?.removeListener(playerListener)
        player?.release()
        player2?.release()
        sharedOkHttpClient?.connectionPool?.evictAll()
        mainHandler.removeCallbacksAndMessages(null)
        overlayHandler.removeCallbacksAndMessages(null)
        seekUpdateHandler.removeCallbacksAndMessages(null)
        numberEntryHandler.removeCallbacksAndMessages(null)
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
    }
}

// ==================== CHANNEL STRIP ADAPTER ====================

class ChannelStripAdapter(
    private val onClick: (com.socatv.nova.data.model.Channel) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ChannelStripAdapter.VH>() {

    private var items: List<com.socatv.nova.data.model.Channel> = emptyList()
    private var currentChannel: com.socatv.nova.data.model.Channel? = null

    fun submit(list: List<com.socatv.nova.data.model.Channel>, current: com.socatv.nova.data.model.Channel) {
        items = list; currentChannel = current; notifyDataSetChanged()
    }

    inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val logo: android.widget.ImageView = v.findViewById(com.socatv.nova.R.id.ivStripLogo)
        val name: android.widget.TextView  = v.findViewById(com.socatv.nova.R.id.tvStripName)
        val num:  android.widget.TextView  = v.findViewById(com.socatv.nova.R.id.tvStripNum)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(com.socatv.nova.R.layout.item_channel_strip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.num.text  = if (ch.num > 0) "CH ${ch.num}" else ""
        com.bumptech.glide.Glide.with(holder.itemView)
            .load(ch.streamIcon)
            .placeholder(com.socatv.nova.R.drawable.ic_live)
            .into(holder.logo)
        val isCurrent = ch.streamId == currentChannel?.streamId
        holder.itemView.alpha = if (isCurrent) 1f else 0.75f
        holder.itemView.scaleX = if (isCurrent) 1.1f else 1f
        holder.itemView.scaleY = if (isCurrent) 1.1f else 1f
        holder.itemView.setOnClickListener { onClick(ch) }
        holder.itemView.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.12f else if (isCurrent) 1.1f else 1f)
                .scaleY(if (focused) 1.12f else if (isCurrent) 1.1f else 1f)
                .alpha(if (focused) 1f else if (isCurrent) 1f else 0.75f)
                .setDuration(100).start()
            v.setFocusBorder(focused)
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}
