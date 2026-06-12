package com.socatv.nova.ui.search

import android.content.Intent
import com.socatv.nova.utils.setFocusBorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.Series
import com.socatv.nova.data.model.VodStream
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivitySearchBinding
import com.socatv.nova.ui.browse.BrowseActivity
import com.socatv.nova.ui.browse.SeriesDetailActivity
import com.socatv.nova.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceJob: Runnable? = null
    private var searchJob: Job? = null

    private val liveAdapter  = SearchResultAdapter { item -> onItemSelected(item) }
    private val vodAdapter   = SearchResultAdapter { item -> onItemSelected(item) }
    private val seriesAdapter = SearchResultAdapter { item -> onItemSelected(item) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapters()
        setupSearch()
        binding.btnBack.setOnClickListener { finish() }

        // Auto-focus search field
        binding.etSearch.post { binding.etSearch.requestFocus() }
    }

    private fun setupAdapters() {
        binding.rvLive.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = liveAdapter
        }
        binding.rvVod.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = vodAdapter
        }
        binding.rvSeries.apply {
            layoutManager = LinearLayoutManager(this@SearchActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seriesAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                debounceJob?.let { debounceHandler.removeCallbacks(it) }
                if (q.length < 2) {
                    clearResults(); return
                }
                debounceJob = Runnable { doSearch(q) }
                debounceHandler.postDelayed(debounceJob!!, 400L)
            }
        })

        binding.etSearch.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Move focus to first result section
                listOf(binding.rvLive, binding.rvVod, binding.rvSeries)
                    .firstOrNull { it.adapter?.itemCount ?: 0 > 0 }
                    ?.also { rv -> rv.post { rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } }
                true
            } else false
        }
    }

    private fun clearResults() {
        liveAdapter.submit(emptyList())
        vodAdapter.submit(emptyList())
        seriesAdapter.submit(emptyList())
        binding.sectionLive.visibility   = View.GONE
        binding.sectionVod.visibility    = View.GONE
        binding.sectionSeries.visibility = View.GONE
        binding.tvNoResults.visibility   = View.GONE
    }

    private fun doSearch(query: String) {
        searchJob?.cancel()
        binding.progressSearch.visibility = View.VISIBLE
        searchJob = lifecycleScope.launch {
            val live   = repo.searchChannels(query)
            val vod    = repo.searchVod(query)
            val series = repo.searchSeries(query)

            binding.progressSearch.visibility = View.GONE

            // Live
            val liveItems = live.take(20).map { SearchItem.LiveItem(it) }
            liveAdapter.submit(liveItems)
            binding.sectionLive.visibility = if (liveItems.isNotEmpty()) View.VISIBLE else View.GONE

            // VOD
            val vodItems = vod.take(20).map { SearchItem.VodItem(it) }
            vodAdapter.submit(vodItems)
            binding.sectionVod.visibility = if (vodItems.isNotEmpty()) View.VISIBLE else View.GONE

            // Series
            val seriesItems = series.take(20).map { SearchItem.SeriesItem(it) }
            seriesAdapter.submit(seriesItems)
            binding.sectionSeries.visibility = if (seriesItems.isNotEmpty()) View.VISIBLE else View.GONE

            val noResults = liveItems.isEmpty() && vodItems.isEmpty() && seriesItems.isEmpty()
            binding.tvNoResults.visibility = if (noResults) View.VISIBLE else View.GONE
        }
    }

    private fun onItemSelected(item: SearchItem) {
        when (item) {
            is SearchItem.LiveItem -> {
                val ch = item.channel
                val url = ch.directSource?.takeIf { it.isNotBlank() && it.startsWith("http") }
                    ?: repo.buildStreamUrl(ch.streamId)
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("stream_id", ch.streamId); putExtra("stream_name", ch.name)
                    putExtra("stream_icon", ch.streamIcon); putExtra("stream_url", url)
                    putExtra("content_type", "live")
                })
            }
            is SearchItem.VodItem -> {
                val v = item.vod
                val url = repo.buildVodUrl(v.streamId, v.containerExtension ?: "mp4")
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("stream_id", v.streamId); putExtra("stream_name", v.name)
                    putExtra("stream_icon", v.streamIcon); putExtra("stream_url", url)
                    putExtra("content_type", "vod")
                })
            }
            is SearchItem.SeriesItem -> {
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra("series_id", item.series.seriesId)
                    putExtra("series_name", item.series.name)
                    putExtra("series_icon", item.series.cover)
                })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        debounceHandler.removeCallbacksAndMessages(null)
        searchJob?.cancel()
    }
}

// ── Search result item model ──────────────────────────────────────────────────

sealed class SearchItem {
    data class LiveItem(val channel: Channel) : SearchItem()
    data class VodItem(val vod: VodStream) : SearchItem()
    data class SeriesItem(val series: Series) : SearchItem()
}

// ── Search result adapter ────────────────────────────────────────────────────

class SearchResultAdapter(
    private val onClick: (SearchItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private var items: List<SearchItem> = emptyList()

    fun submit(list: List<SearchItem>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivSearchThumb)
        val tv: TextView  = v.findViewById(R.id.tvSearchTitle)
        val badge: TextView = v.findViewById(R.id.tvSearchBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        when (item) {
            is SearchItem.LiveItem -> {
                holder.tv.text = item.channel.name
                holder.badge.text = "LIVE"
                holder.badge.setBackgroundColor(0xFF00DCFF.toInt())
                Glide.with(holder.itemView).load(item.channel.streamIcon)
                    .placeholder(R.drawable.ic_live).error(R.drawable.ic_live).into(holder.iv)
            }
            is SearchItem.VodItem -> {
                holder.tv.text = item.vod.name
                holder.badge.text = if (item.vod.rating5based > 0) "★ %.1f".format(item.vod.rating5based) else "VOD"
                holder.badge.setBackgroundColor(0xFF4444FF.toInt())
                Glide.with(holder.itemView).load(item.vod.streamIcon)
                    .placeholder(R.drawable.ic_vod).error(R.drawable.ic_vod).into(holder.iv)
            }
            is SearchItem.SeriesItem -> {
                holder.tv.text = item.series.name
                holder.badge.text = "SERIES"
                holder.badge.setBackgroundColor(0xFF8844FF.toInt())
                Glide.with(holder.itemView).load(item.series.cover)
                    .placeholder(R.drawable.ic_series).error(R.drawable.ic_series).into(holder.iv)
            }
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
            v.elevation = if (f) 10f else 2f
            v.setFocusBorder(f)
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}
