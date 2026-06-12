package com.socatv.nova.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import android.widget.ProgressBar
import com.socatv.nova.R
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.ContentType
import com.socatv.nova.data.model.TmdbItem
import com.socatv.nova.data.model.WatchHistory
import com.socatv.nova.databinding.ItemCategoryBinding
import com.socatv.nova.utils.TmdbImageManager
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class CategoryTile(
    val label: String,
    val iconRes: Int,
    val type: ContentType?
)

class CategoryAdapter(
    private val tiles: List<CategoryTile>,
    private val scope: CoroutineScope,
    private val onClick: (CategoryTile) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(tiles[position])
    }

    override fun onViewRecycled(holder: VH) { holder.cancelBg() }

    override fun getItemCount() = tiles.size

    inner class VH(private val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root) {

        private var bgJob: Job? = null

        fun cancelBg() { bgJob?.cancel() }

        fun bind(tile: CategoryTile) {
            cancelBg()
            binding.tvLabel.text = tile.label
            binding.ivIcon.setImageResource(tile.iconRes)

            // Clear stale bg
            Glide.with(binding.root).clear(binding.ivCategoryBg)

            // Load a category-appropriate TMDb backdrop as subtle background art
            val searchTitle: String? = when (tile.type) {
                ContentType.LIVE    -> "Formula 1: Drive to Survive"
                ContentType.VOD     -> "Inception"
                ContentType.SERIES  -> "The Last of Us"
                ContentType.RADIO   -> "Bohemian Rhapsody"
                else                -> null
            }
            val isMovie = tile.type == ContentType.VOD || tile.type == ContentType.RADIO

            if (!searchTitle.isNullOrBlank()) {
                bgJob = scope.launch {
                    val url = TmdbImageManager.getBackdrop(searchTitle, isMovie)
                    if (!url.isNullOrBlank()) {
                        Glide.with(binding.root)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .transition(DrawableTransitionOptions.withCrossFade(600))
                            .into(binding.ivCategoryBg)
                    }
                }
            }

            binding.root.setOnClickListener { onClick(tile) }
            binding.root.setOnFocusChangeListener { _, hasFocus -> animateFocus(hasFocus) }
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = true
        }

        private fun animateFocus(focused: Boolean) {
            val scale = if (focused) 1.12f else 1f
            binding.root.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            binding.focusRing.visibility = if (focused) View.VISIBLE else View.INVISIBLE
            binding.root.elevation = if (focused) 8f else 3f
            binding.tvLabel.setTextColor(if (focused) 0xFF00DCFF.toInt() else 0xFFFFFFFF.toInt())
            binding.root.setFocusBorder(focused)
        }
    }
}

// ========== TRENDING TICKER ADAPTER ==========

class TrendingTickerAdapter : RecyclerView.Adapter<TrendingTickerAdapter.VH>() {

    private var items: List<TmdbItem> = emptyList()

    fun submitList(list: List<TmdbItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ticker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position + 1)
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivPoster: ImageView = v.findViewById(R.id.ivTickerPoster)
        private val tvRank: TextView    = v.findViewById(R.id.tvTickerRank)
        private val tvTitle: TextView   = v.findViewById(R.id.tvTickerTitle)

        fun bind(item: TmdbItem, rank: Int) {
            tvRank.text  = "#$rank"
            tvTitle.text = item.title ?: item.name ?: ""

            val posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }
            if (!posterUrl.isNullOrBlank()) {
                Glide.with(itemView)
                    .load(posterUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(300))
                    .into(ivPoster)
            } else {
                Glide.with(itemView).clear(ivPoster)
                ivPoster.setImageDrawable(null)
            }

            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f).setDuration(100).start()
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}

// ========== LIVE NOW ADAPTER ==========

class LiveNowAdapter(
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<LiveNowAdapter.VH>() {

    private var channels: List<Channel> = emptyList()

    fun submitList(list: List<Channel>) {
        channels = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_live_now, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(channels[position])
    override fun getItemCount() = channels.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivIcon: ImageView  = v.findViewById(R.id.ivChannelIcon)
        private val tvName: TextView   = v.findViewById(R.id.tvChannelName)

        fun bind(channel: Channel) {
            tvName.text = channel.name
            // FIT_CENTER keeps logos from being cropped (same policy as BrowseActivity)
            ivIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(itemView)
                .load(channel.streamIcon)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_live)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(ivIcon)

            itemView.setOnClickListener { onClick(channel) }
            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.08f else 1f)
                    .scaleY(if (focused) 1.08f else 1f).setDuration(150).start()
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}

// ========== CONTINUE WATCHING ADAPTER ==========

class ContinueWatchingAdapter(
    private val onClick: (WatchHistory) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

    private var items: List<WatchHistory> = emptyList()

    fun submitList(list: List<WatchHistory>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb: ImageView  = v.findViewById(R.id.ivCwThumb)
        val tvTitle: TextView   = v.findViewById(R.id.tvCwTitle)
        val tvSub:   TextView   = v.findViewById(R.id.tvCwSub)
        val progress: ProgressBar = v.findViewById(R.id.progressCw)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_continue_watching, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvSub.text   = when {
            item.episodeInfo != null -> item.episodeInfo
            item.contentType == "live" -> "LIVE"
            else -> ""
        }
        val pct = if (item.durationMs > 0)
            ((item.progressMs.toFloat() / item.durationMs) * 100).toInt().coerceIn(0, 100) else 0
        holder.progress.progress = pct
        Glide.with(holder.itemView)
            .load(item.thumbnailUrl)
            .placeholder(R.drawable.ic_vod)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(holder.ivThumb)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            v.elevation = if (f) 8f else 2f
            v.setFocusBorder(f)
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}
