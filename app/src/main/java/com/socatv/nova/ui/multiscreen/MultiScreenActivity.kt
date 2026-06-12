@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.socatv.nova.ui.multiscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bumptech.glide.Glide
import com.socatv.nova.R
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityMultiScreenBinding
import com.socatv.nova.utils.setFocusBorder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MultiScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiScreenBinding
    private val repo by lazy { IptvRepository(com.socatv.nova.NovaApp.instance.database) }

    private var playerLeft: ExoPlayer? = null
    private var playerRight: ExoPlayer? = null
    private var isLeftActive = true

    private var listenerLeft: Player.Listener? = null
    private var listenerRight: Player.Listener? = null

    private val pickLeftLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            channelFromResult(result.data)?.let { ch ->
                playChannel(ch, left = true)
                binding.btnPickLeft.post { binding.btnPickLeft.requestFocus() }
            }
        }
    }

    private val pickRightLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            channelFromResult(result.data)?.let { ch ->
                playChannel(ch, left = false)
                binding.btnPickRight.post { binding.btnPickRight.requestFocus() }
            }
        }
    }

    private fun channelFromResult(data: Intent?): Channel? {
        data ?: return null
        val id   = data.getStringExtra("stream_id")   ?: return null
        val name = data.getStringExtra("stream_name")  ?: return null
        val icon = data.getStringExtra("stream_icon")
        val url  = data.getStringExtra("stream_url")
        return Channel(streamId = id, name = name, streamIcon = icon,
            epgChannelId = null, added = null, categoryId = null,
            customSid = null, directSource = url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayers()
        setupControls()
        setActiveSlot(left = true)
        binding.tvStatusLeft.text  = "No channel selected"
        binding.tvStatusRight.text = "No channel selected"
    }

    private fun buildOkHttp() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "VLC/3.0 LibVLC/3.0").build())
        }
        .build()

    private fun buildExoPlayer(): ExoPlayer {
        val dsFactory = OkHttpDataSource.Factory(buildOkHttp())
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory))
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
            }
    }

    private fun setupPlayers() {
        playerLeft  = buildExoPlayer()
        playerRight = buildExoPlayer()
        binding.playerViewLeft.player  = playerLeft
        binding.playerViewRight.player = playerRight
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            v.setFocusBorder(f)
        }

        binding.btnPickLeft.setOnClickListener {
            setActiveSlot(left = true)
            pickLeftLauncher.launch(Intent(this, ChannelPickerActivity::class.java))
        }
        binding.btnPickRight.setOnClickListener {
            setActiveSlot(left = false)
            pickRightLauncher.launch(Intent(this, ChannelPickerActivity::class.java))
        }

        listOf(binding.btnPickLeft, binding.btnPickRight).forEach { btn ->
            btn.setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
                v.setFocusBorder(f)
                if (f) setActiveSlot(left = (v.id == R.id.btnPickLeft))
            }
        }

        binding.btnPickLeft.post { binding.btnPickLeft.requestFocus() }
    }

    private fun setActiveSlot(left: Boolean) {
        isLeftActive = left
        binding.slotLeft.setBackgroundResource(
            if (left) R.drawable.slot_active else R.drawable.slot_inactive)
        binding.slotRight.setBackgroundResource(
            if (!left) R.drawable.slot_active else R.drawable.slot_inactive)
        playerLeft?.volume  = if (left) 1f else 0f
        playerRight?.volume = if (!left) 1f else 0f
    }

    private fun playChannel(channel: Channel, left: Boolean) {
        val url = channel.directSource
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: repo.buildStreamUrl(channel.streamId)

        val player = if (left) playerLeft else playerRight
        player?.apply {
            stop()
            setMediaItem(MediaItem.Builder().setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8).build())
            prepare()
            play()
        }

        if (left) {
            binding.tvChannelLeft.text    = channel.name
            binding.tvStatusLeft.text     = "Loading..."
            binding.btnPickLeft.text      = "▶ Change"
            binding.emptyLeft.visibility  = View.GONE
            Glide.with(this).load(channel.streamIcon).placeholder(R.drawable.ic_live).into(binding.ivLogoLeft)
        } else {
            binding.tvChannelRight.text   = channel.name
            binding.tvStatusRight.text    = "Loading..."
            binding.btnPickRight.text     = "▶ Change"
            binding.emptyRight.visibility = View.GONE
            Glide.with(this).load(channel.streamIcon).placeholder(R.drawable.ic_live).into(binding.ivLogoRight)
        }
        setActiveSlot(left)

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val statusView = if (left) binding.tvStatusLeft else binding.tvStatusRight
                statusView.post {
                    statusView.text = when (state) {
                        Player.STATE_READY     -> "Playing"
                        Player.STATE_BUFFERING -> "Buffering..."
                        Player.STATE_ENDED     -> "Stream ended"
                        else                   -> ""
                    }
                }
            }
        }
        if (left) {
            listenerLeft?.let { playerLeft?.removeListener(it) }
            listenerLeft = listener
        } else {
            listenerRight?.let { playerRight?.removeListener(it) }
            listenerRight = listener
        }
        player?.addListener(listener)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (isLeftActive) pickLeftLauncher.launch(Intent(this, ChannelPickerActivity::class.java))
                else pickRightLauncher.launch(Intent(this, ChannelPickerActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        playerLeft?.pause()
        playerRight?.pause()
    }

    override fun onResume() {
        super.onResume()
        playerLeft?.play()
        playerRight?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerLeft?.release()
        playerRight?.release()
    }
}
