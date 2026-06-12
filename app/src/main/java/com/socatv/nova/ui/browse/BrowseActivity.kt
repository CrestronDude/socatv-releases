package com.socatv.nova.ui.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.data.model.*
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityBrowseBinding
import com.socatv.nova.ui.player.PlayerActivity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    companion object {
        /** Shared channel list for quick-switch in PlayerActivity */
        var channelCache: List<com.socatv.nova.data.model.Channel> = emptyList()
        var channelIndexCache: Int = 0
    }

    private lateinit var binding: ActivityBrowseBinding
    private lateinit var repository: IptvRepository
    private lateinit var contentAdapter: ContentAdapter
    private lateinit var categoryAdapter: CategoryListAdapter

    private var contentType: ContentType = ContentType.LIVE
    private var allCategories: List<Category> = emptyList()
    private var selectedCategoryId: String? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(NovaApp.instance.database)

        contentType = try {
            ContentType.valueOf(intent.getStringExtra("content_type") ?: "LIVE")
        } catch (e: Exception) {
            ContentType.LIVE
        }

        setupHeader()
        setupSearch()
        setupCategoryPanel()
        setupContentGrid()
        loadFanart()
        loadContinueWatching()
        loadCategories()
    }

    private fun setupHeader() {
        binding.tvTitle.text = when (contentType) {
            ContentType.LIVE -> "Live TV"
            ContentType.VOD -> "Movies"
            ContentType.SERIES -> "Series"
            ContentType.RADIO -> "Radio"
            ContentType.EPG -> "TV Guide"
            ContentType.CATCHUP -> "Catch-Up TV"
            ContentType.MULTISCREEN -> "Multi-Screen"
            ContentType.ALL -> "All Streams"
            ContentType.FAVORITES -> "Favorites"
            ContentType.ACCOUNT -> "Account"
        }
        binding.tvUserBadge.text = Prefs.username.take(10).uppercase()
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun loadFanart() {
        lifecycleScope.launch {
            val url = try { repository.getTrendingBackdrop() } catch (e: Exception) { null }
            if (!url.isNullOrBlank()) {
                Glide.with(this@BrowseActivity)
                    .load(url)
                    .centerCrop()
                    .into(binding.imgBrowseFanart)
            }
        }
    }

    private fun loadContinueWatching() {
        if (contentType != ContentType.LIVE && contentType != ContentType.VOD
            && contentType != ContentType.SERIES) return

        lifecycleScope.launch {
            repository.getContinueWatching(Prefs.activeProfileId).collect { history ->
                if (history.isNotEmpty()) {
                    binding.continueWatchingSection.visibility = View.VISIBLE
                    val cwAdapter = ContinueWatchingAdapter(history) { item ->
                        val url = when (item.contentType) {
                            "vod"    -> repository.buildVodUrl(item.contentId, "mp4")
                            "series" -> repository.buildVodUrl(item.contentId, "mkv")
                            else     -> repository.buildStreamUrl(item.contentId)
                        }
                        startPlayer(item.contentId, item.title, item.thumbnailUrl, url, item.contentType)
                    }
                    binding.rvContinueWatching.layoutManager = LinearLayoutManager(
                        this@BrowseActivity, LinearLayoutManager.HORIZONTAL, false)
                    binding.rvContinueWatching.adapter = cwAdapter
                } else {
                    binding.continueWatchingSection.visibility = View.GONE
                }
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    val query = s?.toString()?.trim() ?: ""
                    if (query.isNotEmpty()) performSearch(query)
                    else loadContentForCategory(selectedCategoryId)
                }
                searchHandler.postDelayed(searchRunnable!!, 400L)
            }
        })
    }

    private fun setupCategoryPanel() {
        categoryAdapter = CategoryListAdapter { category ->
            selectedCategoryId = category.categoryId
            categoryAdapter.setSelected(category.categoryId)
            loadContentForCategory(category.categoryId)
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter
    }

    private fun setupContentGrid() {
        contentAdapter = ContentAdapter(repository, lifecycleScope) { item ->
            when (item) {
                is ContentItem.Live -> {
                    if (contentType == ContentType.CATCHUP && item.channel.tvArchive == 1) {
                        showCatchupTimePicker(item.channel)
                    } else {
                        val idx = contentAdapter.indexOfLive(item.channel.streamId)
                        channelIndexCache = idx
                        val liveUrl = item.channel.directSource
                            ?.takeIf { it.isNotBlank() && it.startsWith("http") }
                            ?: repository.buildStreamUrl(item.channel.streamId)
                        startPlayer(
                            item.channel.streamId, item.channel.name,
                            item.channel.streamIcon, liveUrl, "live",
                            channelIndex = idx)
                    }
                }
                is ContentItem.Vod -> startPlayer(
                    item.vod.streamId, item.vod.name,
                    item.vod.streamIcon,
                    repository.buildVodUrl(item.vod.streamId, item.vod.containerExtension ?: "mp4"),
                    "vod")
                is ContentItem.SeriesItem -> openSeriesDetail(item.series)
            }
        }

        binding.rvContent.layoutManager = GridLayoutManager(this, 5)
        binding.rvContent.adapter = contentAdapter
        binding.rvContent.setHasFixedSize(true)
    }

    private fun startPlayer(streamId: String, name: String, icon: String?, url: String, type: String,
                            channelIndex: Int = 0) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", streamId)
            putExtra("stream_name", name)
            putExtra("stream_icon", icon)
            putExtra("stream_url", url)
            putExtra("content_type", type)
            putExtra("channel_index", channelIndex)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun openSeriesDetail(series: Series) {
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra("series_id", series.seriesId)
            putExtra("series_name", series.name)
            putExtra("series_cover", series.cover)
            putExtra("series_plot", series.plot)
            putExtra("series_genre", series.genre)
            putExtra("series_rating", series.rating)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun loadCategories() {
        showLoading(true, "Loading categories...")
        lifecycleScope.launch {
            // ALL and FAVORITES use virtual categories — no API call needed
            if (contentType == ContentType.ALL) {
                val typeCats = listOf(
                    Category("live", "Live TV", 0),
                    Category("vod", "Movies", 0),
                    Category("series", "Series", 0)
                )
                categoryAdapter.submitList(typeCats)
                categoryAdapter.setSelected("live")
                binding.tvCategoryCount.text = "3 TYPES"
                loadContentForCategory("live")
                return@launch
            }
            if (contentType == ContentType.FAVORITES) {
                val typeCats = listOf(
                    Category("channels", "Channels", 0),
                    Category("movies", "Movies", 0),
                    Category("series", "Series", 0)
                )
                categoryAdapter.submitList(typeCats)
                categoryAdapter.setSelected("channels")
                binding.tvCategoryCount.text = "FAVORITES"
                loadContentForCategory("channels")
                return@launch
            }

            val result = when (contentType) {
                ContentType.RADIO    -> repository.getRadioCategories()
                ContentType.VOD      -> repository.getVodCategories()
                ContentType.SERIES   -> repository.getSeriesCategories()
                else                 -> repository.getLiveCategories() // LIVE, EPG, CATCHUP
            }

            result.fold(
                onSuccess = { cats ->
                    allCategories = cats
                    val allCat = Category("all", "All", 0)
                    categoryAdapter.submitList(listOf(allCat) + cats)
                    categoryAdapter.setSelected("all")
                    binding.tvCategoryCount.text = "${cats.size} CATEGORIES"
                    loadContentForCategory(null)
                },
                onFailure = {
                    showLoading(false)
                    Toast.makeText(this@BrowseActivity, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun loadContentForCategory(categoryId: String?) {
        showLoading(true, "Loading...")
        val effectiveCatId = if (categoryId == "all") null else categoryId

        lifecycleScope.launch {
            when (contentType) {
                ContentType.LIVE -> {
                    repository.getLiveStreams(effectiveCatId).fold(
                        onSuccess = { channels ->
                            contentAdapter.submitLive(channels)
                            updateCount(channels.size, "channels")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
                ContentType.RADIO -> {
                    repository.getLiveStreams(effectiveCatId).fold(
                        onSuccess = { channels ->
                            contentAdapter.submitLive(channels)
                            updateCount(channels.size, "stations")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
                ContentType.CATCHUP -> {
                    repository.getCatchupChannels(effectiveCatId).fold(
                        onSuccess = { channels ->
                            contentAdapter.submitLive(channels)
                            updateCount(channels.size, "channels")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
                ContentType.ALL -> {
                    when (categoryId) {
                        "vod" -> repository.getVodStreams(null).fold(
                            onSuccess = { contentAdapter.submitVod(it); updateCount(it.size, "movies"); showLoading(false) },
                            onFailure = { handleError(it) }
                        )
                        "series" -> repository.getSeries(null).fold(
                            onSuccess = { contentAdapter.submitSeries(it); updateCount(it.size, "series"); showLoading(false) },
                            onFailure = { handleError(it) }
                        )
                        else -> repository.getLiveStreams(null).fold(
                            onSuccess = { contentAdapter.submitLive(it); updateCount(it.size, "channels"); showLoading(false) },
                            onFailure = { handleError(it) }
                        )
                    }
                }
                ContentType.FAVORITES -> {
                    val (channels, vod, series) = repository.getFavoriteItems()
                    when (categoryId) {
                        "movies" -> { contentAdapter.submitVod(vod); updateCount(vod.size, "favorites") }
                        "series" -> { contentAdapter.submitSeries(series); updateCount(series.size, "favorites") }
                        else     -> { contentAdapter.submitLive(channels); updateCount(channels.size, "favorites") }
                    }
                    showLoading(false)
                    if (channels.isEmpty() && vod.isEmpty() && series.isEmpty()) {
                        Toast.makeText(this@BrowseActivity,
                            "No favorites yet — long-press any content to add", Toast.LENGTH_LONG).show()
                    }
                }
                ContentType.VOD -> {
                    repository.getVodStreams(effectiveCatId).fold(
                        onSuccess = { vod ->
                            contentAdapter.submitVod(vod)
                            updateCount(vod.size, "movies")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
                ContentType.SERIES -> {
                    repository.getSeries(effectiveCatId).fold(
                        onSuccess = { series ->
                            contentAdapter.submitSeries(series)
                            updateCount(series.size, "series")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
                ContentType.EPG, ContentType.MULTISCREEN, ContentType.ACCOUNT -> {
                    repository.getLiveStreams(effectiveCatId).fold(
                        onSuccess = { channels ->
                            contentAdapter.submitLive(channels)
                            updateCount(channels.size, "channels")
                            showLoading(false)
                        },
                        onFailure = { handleError(it) }
                    )
                }
            }
        }
    }

    private fun updateCount(count: Int, label: String) {
        binding.tvContentCount.visibility = View.VISIBLE
        binding.tvContentCount.text = "${count.formatCount()} $label".uppercase()
    }

    private fun Int.formatCount(): String = if (this >= 1000) "${this / 1000}K+" else toString()

    private fun performSearch(query: String) {
        showLoading(true, "Searching...")
        lifecycleScope.launch {
            val (channels, vod, series) = repository.searchAll(query)
            showLoading(false)
            when (contentType) {
                ContentType.LIVE, ContentType.RADIO, ContentType.CATCHUP, ContentType.EPG -> {
                    contentAdapter.submitLive(channels)
                    updateCount(channels.size, "results")
                }
                ContentType.VOD -> {
                    contentAdapter.submitVod(vod)
                    updateCount(vod.size, "results")
                }
                ContentType.SERIES -> {
                    contentAdapter.submitSeries(series)
                    updateCount(series.size, "results")
                }
                ContentType.ALL, ContentType.FAVORITES, ContentType.MULTISCREEN, ContentType.ACCOUNT -> {
                    when {
                        channels.size >= vod.size && channels.size >= series.size -> {
                            contentAdapter.submitLive(channels); updateCount(channels.size, "channels")
                        }
                        vod.size >= series.size -> {
                            contentAdapter.submitVod(vod); updateCount(vod.size, "movies")
                        }
                        else -> {
                            contentAdapter.submitSeries(series); updateCount(series.size, "series")
                        }
                    }
                }
            }
        }
    }

    private fun showLoading(show: Boolean, msg: String = "Loading...") {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvLoadingMsg.text = msg
    }

    private fun showCatchupTimePicker(channel: com.socatv.nova.data.model.Channel) {
        val maxHours = if (channel.tvArchiveDuration > 0) channel.tvArchiveDuration / 60 else 72
        val options = mutableListOf<String>()
        val offsets = mutableListOf<Int>() // hours ago
        listOf(1, 2, 4, 6, 12, 24).filter { it <= maxHours }.forEach { h ->
            options.add("$h hour${if (h > 1) "s" else ""} ago")
            offsets.add(h)
        }
        if (options.isEmpty()) { options.add("1 hour ago"); offsets.add(1) }

        androidx.appcompat.app.AlertDialog.Builder(this, com.socatv.nova.R.style.NovaDialogTheme)
            .setTitle("Watch: ${channel.name}")
            .setItems(options.toTypedArray()) { _, which ->
                val hoursAgo = offsets[which]
                val startEpoch = System.currentTimeMillis() / 1000 - hoursAgo * 3600L
                val duration = hoursAgo * 60
                val url = repository.buildTimeshiftUrl(channel.streamId, duration, startEpoch)
                startPlayer(channel.streamId, "${channel.name} (-${hoursAgo}h)",
                    channel.streamIcon, url, "catchup")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleError(e: Throwable) {
        showLoading(false)
        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        searchHandler.removeCallbacksAndMessages(null)
    }
}

// ========== CATEGORY ADAPTER ==========

class CategoryListAdapter(
    private val onClick: (Category) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<CategoryListAdapter.VH>() {

    private var items: List<Category> = emptyList()
    private var selectedId: String? = null

    fun submitList(list: List<Category>) {
        items = list
        notifyDataSetChanged()
    }

    fun setSelected(id: String) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(com.socatv.nova.R.layout.item_category_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        fun bind(cat: Category) {
            val tv = itemView.findViewById<android.widget.TextView>(com.socatv.nova.R.id.tvCategoryName)
            tv.text = cat.categoryName
            val isSelected = cat.categoryId == selectedId
            tv.setTextColor(if (isSelected) 0xFF00DCFF.toInt() else 0xFFCCCCCC.toInt())
            itemView.background = if (isSelected) {
                android.graphics.drawable.ColorDrawable(0x3300DCFF.toInt())
            } else null
            itemView.setOnClickListener { onClick(cat) }
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.03f else 1f)
                    .scaleY(if (focused) 1.03f else 1f).setDuration(80).start()
                v.setFocusBorder(focused)
                if (focused && !isSelected) {
                    tv.setTextColor(0xFFFFFFFF.toInt())
                } else if (!focused && !isSelected) {
                    tv.setTextColor(0xFFCCCCCC.toInt())
                }
            }
            itemView.isFocusable = true
        }
    }
}

// ========== CONTINUE WATCHING ADAPTER ==========

class ContinueWatchingAdapter(
    private val items: List<com.socatv.nova.data.model.WatchHistory>,
    private val onClick: (com.socatv.nova.data.model.WatchHistory) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(com.socatv.nova.R.layout.item_continue_watching, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        fun bind(item: com.socatv.nova.data.model.WatchHistory) {
            itemView.findViewById<android.widget.TextView>(com.socatv.nova.R.id.tvCwTitle).text = item.title
            val progress = if (item.durationMs > 0) ((item.progressMs * 100) / item.durationMs).toInt() else 0
            itemView.findViewById<android.widget.ProgressBar>(com.socatv.nova.R.id.progressCw).progress = progress
            com.bumptech.glide.Glide.with(itemView)
                .load(item.thumbnailUrl)
                .placeholder(com.socatv.nova.R.drawable.ic_vod)
                .into(itemView.findViewById(com.socatv.nova.R.id.ivCwThumb))
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}
