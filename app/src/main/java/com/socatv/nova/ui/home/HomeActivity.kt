package com.socatv.nova.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.ContentType
import com.socatv.nova.data.model.Mood
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityHomeBinding
import com.socatv.nova.ui.browse.BrowseActivity
import com.socatv.nova.ui.player.PlayerActivity
import com.socatv.nova.ui.settings.SettingsActivity
import com.socatv.nova.data.repository.ProfileRepository
import com.socatv.nova.utils.KonamiDetector
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.TimeTheme
import com.socatv.nova.utils.setFocusBorder

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var konamiDetector: KonamiDetector
    private lateinit var trendingAdapter: TrendingTickerAdapter
    private lateinit var liveNowAdapter: LiveNowAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private val repository by lazy { IptvRepository(NovaApp.instance.database) }
    private val profileRepo by lazy { ProfileRepository() }

    // ── Screensaver ────────────────────────────────────────────────────────
    private val screensaverHandler = Handler(Looper.getMainLooper())
    private var screensaverRunnable: Runnable? = null
    private var isScreensaverActive = false
    private val SCREENSAVER_DELAY =
        (Prefs.screensaverDelayMinutes * 60 * 1000L).coerceAtLeast(60_000L)

    // ── Fanart slideshow ───────────────────────────────────────────────────
    private val fanartUrls = mutableListOf<String>()
    private var fanartIndex = 0
    private val fanartHandler = Handler(Looper.getMainLooper())
    private val FANART_CYCLE_MS = 9_000L
    private val fanartRunnable = object : Runnable {
        override fun run() {
            if (fanartUrls.size > 1) {
                fanartIndex = (fanartIndex + 1) % fanartUrls.size
                showFanart(fanartUrls[fanartIndex])
            }
            fanartHandler.postDelayed(this, FANART_CYCLE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        konamiDetector = KonamiDetector { onKonamiUnlocked() }

        applyTimeTheme()
        setupCategoryTiles()
        setupTrendingTicker()
        setupLiveNowRow()
        setupContinueWatching()
        setupSurpriseButton()
        setupMoodPicker()
        observeViewModel()
        scheduleScreensaver()

        binding.tvGreeting.text    = TimeTheme.getGreeting()
        binding.tvProfileName.text = profileRepo.getActiveProfile().name
    }

    private fun applyTimeTheme() {
        if (!Prefs.timeThemeEnabled) return
        val scheme = TimeTheme.getScheme()
        binding.root.setBackgroundColor(scheme.background)
        binding.viewAccentBar.setBackgroundColor(scheme.primary)
        binding.tvGreeting.setTextColor(scheme.primary)
    }

    private fun setupCategoryTiles() {
        val tiles = listOf(
            CategoryTile("Live TV",  R.drawable.ic_live,     ContentType.LIVE),
            CategoryTile("Movies",   R.drawable.ic_vod,      ContentType.VOD),
            CategoryTile("Series",   R.drawable.ic_series,   ContentType.SERIES),
            CategoryTile("Radio",    R.drawable.ic_radio,    ContentType.RADIO),
            CategoryTile("Settings", R.drawable.ic_settings, null)
        )

        val adapter = CategoryAdapter(tiles, lifecycleScope) { tile ->
            when (tile.type) {
                ContentType.LIVE -> openBrowse(ContentType.LIVE)
                ContentType.VOD  -> openBrowse(ContentType.VOD)
                ContentType.SERIES -> openBrowse(ContentType.SERIES)
                ContentType.RADIO  -> openBrowse(ContentType.RADIO)
                ContentType.EPG, ContentType.CATCHUP, ContentType.MULTISCREEN,
                ContentType.ALL, ContentType.FAVORITES, ContentType.ACCOUNT ->
                    openBrowse(tile.type)
                null -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        binding.rvCategories.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCategories.adapter = adapter

        binding.rvCategories.post {
            binding.rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupTrendingTicker() {
        trendingAdapter = TrendingTickerAdapter()
        binding.rvTrending.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvTrending.adapter = trendingAdapter
    }

    private fun setupContinueWatching() {
        continueWatchingAdapter = ContinueWatchingAdapter { history ->
            val url = when (history.contentType) {
                "vod"    -> repository.buildVodUrl(history.contentId, "mp4")
                "series" -> repository.buildVodUrl(history.contentId, "mkv")
                else     -> repository.buildStreamUrl(history.contentId)
            }
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("stream_id",    history.contentId)
                putExtra("stream_name",  history.title)
                putExtra("stream_icon",  history.thumbnailUrl)
                putExtra("stream_url",   url)
                putExtra("content_type", history.contentType)
                putExtra("resume_ms",    history.progressMs)
            })
        }
        binding.rvContinueWatching.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvContinueWatching.adapter = continueWatchingAdapter
    }

    private fun setupLiveNowRow() {
        liveNowAdapter = LiveNowAdapter { channel ->
            // Build stream URL (direct_source preferred, else constructed)
            val url = channel.directSource?.takeIf { it.isNotBlank() && it.startsWith("http") }
                ?: repository.buildStreamUrl(channel.streamId)
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("stream_id",   channel.streamId)
                putExtra("stream_name", channel.name)
                putExtra("stream_icon", channel.streamIcon)
                putExtra("stream_url",  url)
                putExtra("content_type", "live")
            })
        }
        binding.rvLiveNow.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvLiveNow.adapter = liveNowAdapter
    }

    private fun setupSurpriseButton() {
        binding.btnSurprise.setOnClickListener { surpriseMe() }
        binding.btnSurprise.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f).setDuration(150).start()
            v.setFocusBorder(hasFocus)
        }
    }

    private fun surpriseMe() {
        openBrowse(listOf(ContentType.LIVE, ContentType.VOD, ContentType.SERIES).random())
    }

    private fun setupMoodPicker() {
        binding.btnMood.setOnClickListener {
            binding.moodPickerView.visibility =
                if (binding.moodPickerView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.moodPickerView.onMoodSelected = { mood ->
            binding.moodPickerView.visibility = View.GONE
            Prefs.lastMood = mood.name
            Toast.makeText(this, "Mood: ${mood.label}", Toast.LENGTH_SHORT).show()
            openBrowseWithMood(mood)
        }
    }

    private fun openBrowseWithMood(mood: Mood) {
        val type = when (mood) {
            Mood.SPORTS   -> ContentType.LIVE
            Mood.SURPRISE -> listOf(ContentType.VOD, ContentType.SERIES).random()
            else          -> ContentType.VOD
        }
        startActivity(Intent(this, BrowseActivity::class.java).apply {
            putExtra("content_type",  type.name)
            putExtra("mood_filter", mood.label)
        })
    }

    private fun observeViewModel() {
        // Rotating backdrop slideshow
        viewModel.fanartUrls.observe(this) { urls ->
            if (urls.isEmpty()) return@observe
            fanartUrls.clear()
            fanartUrls.addAll(urls)
            fanartIndex = 0
            showFanart(urls[0])
            // Start rotation after first image settles
            fanartHandler.removeCallbacks(fanartRunnable)
            fanartHandler.postDelayed(fanartRunnable, FANART_CYCLE_MS)
        }

        viewModel.trending.observe(this) { items ->
            trendingAdapter.submitList(items)
        }

        viewModel.liveNow.observe(this) { channels ->
            liveNowAdapter.submitList(channels)
            binding.sectionLiveNow.visibility =
                if (channels.isNotEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.continueWatching.observe(this) { history ->
            continueWatchingAdapter.submitList(history)
            binding.sectionContinue.visibility =
                if (history.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ── Fanart ─────────────────────────────────────────────────────────────

    private fun showFanart(url: String) {
        // Glide crossfades from the currently displayed drawable to the new one.
        // alpha=0.38 is set in XML; Glide respects the view's alpha.
        Glide.with(this)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(1_400))
            .into(binding.ivFanartBg)
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun openBrowse(type: ContentType) {
        startActivity(Intent(this, BrowseActivity::class.java).apply {
            putExtra("content_type", type.name)
        })
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── Screensaver ────────────────────────────────────────────────────────

    private fun scheduleScreensaver() {
        screensaverRunnable?.let { screensaverHandler.removeCallbacks(it) }
        screensaverRunnable = Runnable { showScreensaver() }
        screensaverHandler.postDelayed(screensaverRunnable!!, SCREENSAVER_DELAY)
    }

    private fun showScreensaver() {
        isScreensaverActive = true
        binding.screensaverView.visibility = View.VISIBLE
        binding.screensaverView.startScreensaver(viewModel.trending.value ?: emptyList())
    }

    private fun hideScreensaver() {
        if (!isScreensaverActive) return
        isScreensaverActive = false
        binding.screensaverView.visibility = View.GONE
        binding.screensaverView.stopScreensaver()
        scheduleScreensaver()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isScreensaverActive) hideScreensaver() else scheduleScreensaver()
    }

    // ── Konami ─────────────────────────────────────────────────────────────

    private fun onKonamiUnlocked() {
        Prefs.konamiUnlocked = true
        Toast.makeText(this, "★ KONAMI CODE ACTIVATED ★ Retro Mode Unlocked!", Toast.LENGTH_LONG).show()
        binding.retroOverlay.visibility = View.VISIBLE
        binding.root.postDelayed({ binding.retroOverlay.visibility = View.GONE }, 5_000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && konamiDetector.onKeyDown(event.keyCode))
            return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        applyTimeTheme()
        binding.tvGreeting.text = TimeTheme.getGreeting()
    }

    override fun onDestroy() {
        super.onDestroy()
        fanartHandler.removeCallbacksAndMessages(null)
        screensaverHandler.removeCallbacksAndMessages(null)
    }
}
