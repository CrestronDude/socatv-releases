package com.socatv.nova.ui.browse

import android.content.Intent
import com.socatv.nova.utils.setFocusBorder
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.Episode
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivitySeriesDetailBinding
import com.socatv.nova.ui.player.PlayerActivity
import com.socatv.nova.utils.TmdbImageManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var repository: IptvRepository

    private var seriesId: String = ""
    private var seriesTitle: String = ""
    private var allEpisodes: Map<String, List<Episode>> = emptyMap()
    private var selectedSeason: String = "1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(NovaApp.instance.database)

        seriesId    = intent.getStringExtra("series_id") ?: ""
        seriesTitle = intent.getStringExtra("series_name") ?: "Series"
        val cover   = intent.getStringExtra("series_cover")
        val plot    = intent.getStringExtra("series_plot")
        val genre   = intent.getStringExtra("series_genre")
        val rating  = intent.getStringExtra("series_rating")

        binding.tvSeriesTitle.text  = seriesTitle
        binding.tvSeriesPlot.text   = plot ?: ""
        binding.tvSeriesGenre.text  = genre ?: ""
        binding.tvSeriesRating.text = if (!rating.isNullOrBlank()) "★ $rating" else ""

        Glide.with(this).load(cover).placeholder(R.drawable.ic_series)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(binding.imgSeriesCover)

        loadFanart()
        loadSeriesInfo()
    }

    private fun loadFanart() {
        // First: try to get a series-specific backdrop from TMDb
        lifecycleScope.launch {
            val tmdbBackdrop = TmdbImageManager.getSeriesBackdrop(seriesTitle)
            if (!tmdbBackdrop.isNullOrBlank()) {
                Glide.with(this@SeriesDetailActivity)
                    .load(tmdbBackdrop)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .into(binding.imgSeriesFanart)
            } else {
                // Fallback: a trending backdrop
                val trendingUrl = try { repository.getTrendingBackdrop() } catch (_: Exception) { null }
                if (!trendingUrl.isNullOrBlank()) {
                    Glide.with(this@SeriesDetailActivity)
                        .load(trendingUrl)
                        .centerCrop()
                        .into(binding.imgSeriesFanart)
                }
            }
        }
    }

    private fun loadSeriesInfo() {
        binding.progressSeries.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.getSeriesInfo(seriesId).fold(
                onSuccess = { response ->
                    binding.progressSeries.visibility = View.GONE
                    allEpisodes = response.episodes ?: emptyMap()

                    if (allEpisodes.isEmpty()) {
                        Toast.makeText(this@SeriesDetailActivity,
                            "No episodes found", Toast.LENGTH_SHORT).show()
                        return@fold
                    }

                    // Xtream backdrop overrides TMDb if available
                    val xtreamBackdrop = response.info?.backdropPath?.firstOrNull { it.startsWith("http") }
                    if (!xtreamBackdrop.isNullOrBlank()) {
                        Glide.with(this@SeriesDetailActivity).load(xtreamBackdrop)
                            .centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade(300))
                            .into(binding.imgSeriesFanart)
                    }

                    setupSeasons()
                },
                onFailure = {
                    binding.progressSeries.visibility = View.GONE
                    Toast.makeText(this@SeriesDetailActivity, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun setupSeasons() {
        val seasons = allEpisodes.keys.sortedBy { it.toIntOrNull() ?: 0 }
        if (seasons.isEmpty()) return

        selectedSeason = seasons.first()

        val seasonAdapter = SeasonAdapter(seasons, selectedSeason) { season ->
            selectedSeason = season
            loadEpisodesForSeason(season)
        }
        binding.rvSeasons.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeasons.adapter = seasonAdapter

        loadEpisodesForSeason(selectedSeason)
    }

    private fun loadEpisodesForSeason(season: String) {
        val episodes = allEpisodes[season]?.sortedBy { it.episodeNum } ?: return
        val epAdapter = EpisodeAdapter(episodes, seriesTitle, season, lifecycleScope) { episode ->
            playEpisode(episode)
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = epAdapter
        // Request focus on first episode
        binding.rvEpisodes.post {
            binding.rvEpisodes.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun playEpisode(episode: Episode) {
        val episodeId = episode.id ?: return
        val ext = episode.containerExtension ?: "mp4"
        val url = repository.buildEpisodeUrl(episodeId, ext)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", episodeId)
            putExtra("stream_name",
                "${binding.tvSeriesTitle.text} S${selectedSeason}E${episode.episodeNum} - ${episode.displayTitle}")
            putExtra("stream_icon", episode.info?.movieImage)
            putExtra("stream_url", url)
            putExtra("content_type", "series")
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

// ========== SEASON ADAPTER ==========

class SeasonAdapter(
    private val seasons: List<String>,
    private var selected: String,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    fun setSelected(s: String) { selected = s; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(seasons[position])
    override fun getItemCount() = seasons.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(season: String) {
            val tv = itemView.findViewById<TextView>(R.id.tvSeasonNum)
            tv.text = "S$season"
            val sel = season == selected
            tv.setTextColor(if (sel) 0xFF00DCFF.toInt() else 0xFFAAAAAA.toInt())
            itemView.background = android.graphics.drawable.ColorDrawable(
                if (sel) 0x3300DCFF.toInt() else 0)
            itemView.setOnClickListener { onClick(season); setSelected(season) }
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1f)
                    .scaleY(if (focused) 1.08f else 1f).setDuration(100).start()
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}

// ========== EPISODE ADAPTER ==========

class EpisodeAdapter(
    private val episodes: List<Episode>,
    private val showTitle: String,
    private val season: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(episodes[position])
    override fun onViewRecycled(holder: VH) { holder.cancelLoad() }
    override fun getItemCount() = episodes.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {

        private var tmdbJob: Job? = null
        private val ivThumb: ImageView = v.findViewById(R.id.ivEpThumb)

        fun cancelLoad() { tmdbJob?.cancel() }

        fun bind(ep: Episode) {
            cancelLoad()
            itemView.findViewById<TextView>(R.id.tvEpNumber).text = "E${ep.episodeNum}"
            itemView.findViewById<TextView>(R.id.tvEpTitle).text   = ep.displayTitle
            val info = ep.info
            itemView.findViewById<TextView>(R.id.tvEpInfo).text =
                listOfNotNull(info?.duration?.let { "⏱ $it" }, info?.plot)
                    .joinToString("  •  ").ifBlank { "" }

            val xtreamImage = info?.movieImage
            val tag = "$showTitle:s${season}e${ep.episodeNum}"

            // Load Xtream image first (usually a poster/still from the panel)
            if (!xtreamImage.isNullOrBlank()) {
                ivThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(itemView)
                    .load(xtreamImage)
                    .placeholder(R.drawable.ic_vod)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
                    .into(ivThumb)
            } else {
                Glide.with(itemView).load(R.drawable.ic_vod).into(ivThumb)
            }

            ivThumb.tag = tag

            // Async-fetch TMDb episode still (often higher quality)
            tmdbJob = scope.launch {
                val seasonNum  = season.toIntOrNull() ?: 1
                val still = TmdbImageManager.getEpisodeStill(showTitle, seasonNum, ep.episodeNum)
                if (!still.isNullOrBlank() && ivThumb.tag == tag) {
                    Glide.with(itemView)
                        .load(still)
                        .placeholder(ivThumb.drawable)
                        .transition(DrawableTransitionOptions.withCrossFade(300))
                        .into(ivThumb)
                }
            }

            itemView.setOnClickListener { onClick(ep) }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) { onClick(ep); true } else false
            }
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.02f else 1f)
                    .scaleY(if (focused) 1.02f else 1f).setDuration(80).start()
                v.elevation = if (focused) 6f else 1f
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}
