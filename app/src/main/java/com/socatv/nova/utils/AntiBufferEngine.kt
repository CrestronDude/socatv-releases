package com.socatv.nova.utils

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Three-pronged anti-buffering system:
 *
 *  1. preWarm()       — resolves DNS + opens a TCP connection BEFORE the player
 *                        starts. Cuts 200-500ms off first-segment latency.
 *
 *  2. LiveEdgeGuard   — polls every 500 ms while STATE_BUFFERING. If the
 *                        buffer stays below 1.5 s for two consecutive polls
 *                        (~1 s of wall-clock stall), skips the stuck segment
 *                        by seeking to the live edge. The server's newest
 *                        segments are always the most reliably available.
 *
 *  3. SpeedBreather   — VOD only. When the ahead-buffer drains below 4 s,
 *                        slows playback to 0.96× (imperceptible) so download
 *                        can catch up. Returns to 1.0× once ≥ 12 s is buffered.
 *                        Never touches live streams (risks live-edge drift).
 */
@androidx.media3.common.util.UnstableApi
class AntiBufferEngine(
    private val player: ExoPlayer,
    private val isLive: Boolean,
    private val handler: Handler
) {

    companion object {
        private const val POLL_MS           = 500L
        private const val LIVE_JUMP_MS      = 1_500L  // seek to live edge below this
        private const val VOD_SLOW_MS       = 4_000L  // slow down below this
        private const val VOD_NORMAL_MS     = 12_000L // resume normal speed above this
        private const val SLOW_SPEED        = 0.96f
        private const val NORMAL_SPEED      = 1.0f

        /**
         * Makes a lightweight HEAD request to the stream host before the player
         * starts. This pre-resolves DNS and opens a keep-alive TCP connection
         * that OkHttp will reuse for the first HLS playlist fetch.
         */
        suspend fun preWarm(streamUrl: String, client: OkHttpClient) =
            withContext(Dispatchers.IO) {
                runCatching {
                    val u = java.net.URL(streamUrl)
                    val base = "${u.protocol}://${u.host}" +
                               if (u.port != -1 && u.port != 80 && u.port != 443)
                                   ":${u.port}" else ""
                    client.newCall(
                        Request.Builder()
                            .url("$base/player_api.php")
                            .head()
                            .header("User-Agent", "VLC/3.0 LibVLC/3.0 (IPTV)")
                            .header("Connection", "keep-alive")
                            .build()
                    ).execute().close()
                }
                Unit
            }
    }

    private var active = false

    // Consecutive buffering-state polls with low buffer (live guard)
    private var stalledPolls = 0
    // Whether we artificially slowed playback (VOD)
    private var isSlowed = false

    fun start() {
        active = true
        stalledPolls = 0
        isSlowed = false
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(poll)
        if (isSlowed) runCatching { player.setPlaybackSpeed(NORMAL_SPEED) }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!active) return

            val state   = player.playbackState
            val bufMs   = player.totalBufferedDuration
            val playing = player.isPlaying

            if (isLive) {
                // ── Live edge guard ──────────────────────────────────────────
                if (state == Player.STATE_BUFFERING && bufMs < LIVE_JUMP_MS) {
                    stalledPolls++
                    if (stalledPolls >= 2) {
                        // Stuck for ~1 s with empty buffer — jump to the live edge.
                        // The newest segments are always more reliably served.
                        stalledPolls = 0
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = true
                    }
                } else {
                    stalledPolls = 0
                }
            } else {
                // ── VOD speed breather ───────────────────────────────────────
                if (playing) {
                    when {
                        bufMs < VOD_SLOW_MS && !isSlowed -> {
                            player.setPlaybackSpeed(SLOW_SPEED)
                            isSlowed = true
                        }
                        bufMs > VOD_NORMAL_MS && isSlowed -> {
                            player.setPlaybackSpeed(NORMAL_SPEED)
                            isSlowed = false
                        }
                    }
                }
            }

            handler.postDelayed(this, POLL_MS)
        }
    }
}
