package com.socatv.nova.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.socatv.nova.R
import com.socatv.nova.api.TMDbApi
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.Series
import com.socatv.nova.data.model.VodStream
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ItemContentBinding
import com.socatv.nova.utils.ColorExtractor
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.TmdbImageManager
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class ContentItem {
    data class Live(val channel: Channel) : ContentItem()
    data class Vod(val vod: VodStream) : ContentItem()
    data class SeriesItem(val series: Series) : ContentItem()
}

class ContentAdapter(
    private val repository: IptvRepository,
    private val scope: CoroutineScope,
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<ContentAdapter.VH>() {

    private var items: List<ContentItem> = emptyList()

    fun submitLive(channels: List<Channel>) {
        items = channels.map { ContentItem.Live(it) }
        BrowseActivity.channelCache = channels
        notifyDataSetChanged()
    }

    fun indexOfLive(streamId: String): Int =
        items.indexOfFirst { it is ContentItem.Live && it.channel.streamId == streamId }
            .coerceAtLeast(0)

    fun submitVod(vod: List<VodStream>) {
        items = vod.map { ContentItem.Vod(it) }
        notifyDataSetChanged()
    }

    fun submitSeries(series: List<Series>) {
        items = series.map { ContentItem.SeriesItem(it) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemContentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun onViewRecycled(holder: VH) { holder.cancelTmdb() }
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemContentBinding) : RecyclerView.ViewHolder(b.root) {

        private var tmdbJob: Job? = null

        fun cancelTmdb() { tmdbJob?.cancel() }

        fun bind(item: ContentItem) {
            cancelTmdb()

            when (item) {
                is ContentItem.Live -> bindLive(item.channel)
                is ContentItem.Vod  -> bindVod(item.vod)
                is ContentItem.SeriesItem -> bindSeries(item.series)
            }

            // Watch progress bar for VOD/Series
            val progressId = when (item) {
                is ContentItem.Live       -> null
                is ContentItem.Vod        -> item.vod.streamId
                is ContentItem.SeriesItem -> item.series.seriesId
            }
            if (progressId != null) {
                val (pos, dur) = Prefs.getProgress(progressId)
                if (dur > 0) setWatchProgress(pos.toInt(), dur.toInt()) else setWatchProgress(0, 0)
            } else {
                setWatchProgress(0, 0)
            }

            // Shared: favorites star
            val favId = when (item) {
                is ContentItem.Live       -> item.channel.streamId
                is ContentItem.Vod        -> item.vod.streamId
                is ContentItem.SeriesItem -> item.series.seriesId
            }
            b.badgeFavorite.visibility = if (Prefs.isFavorite(favId)) View.VISIBLE else View.GONE

            // Click
            b.root.setOnClickListener { onClick(item) }

            // Long press → favorites
            b.root.setOnLongClickListener {
                val added = Prefs.toggleFavorite(favId)
                b.badgeFavorite.visibility = if (added) View.VISIBLE else View.GONE
                val msg = if (added) "Added to Favorites ★" else "Removed from Favorites"
                Toast.makeText(b.root.context, msg, Toast.LENGTH_SHORT).show()
                true
            }

            b.root.setOnFocusChangeListener { _, hasFocus -> animateFocus(hasFocus) }
            b.root.isFocusable = true
            b.root.isFocusableInTouchMode = false
        }

        // ── Live channels: logo shown at fitCenter with padding ───────────

        private fun bindLive(ch: Channel) {
            b.tvTitle.text = ch.name
            b.tvSubtitle.visibility = View.GONE
            b.badgeLive.visibility = View.VISIBLE
            b.badgeRating.visibility = View.GONE

            // Logos need fitCenter — centerCrop massacres small PNGs
            b.ivThumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
            b.ivThumbnail.setPadding(12, 12, 12, 12)

            Glide.with(b.root)
                .load(ch.streamIcon)
                .placeholder(R.drawable.ic_live)
                .error(R.drawable.ic_live)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(b.ivThumbnail)
        }

        // ── VOD: load poster → replace with TMDb 16:9 backdrop ───────────

        private fun bindVod(vod: VodStream) {
            b.tvTitle.text = vod.name
            b.tvSubtitle.visibility = View.GONE
            b.badgeLive.visibility = View.GONE
            if (vod.rating5based > 0) {
                b.badgeRating.visibility = View.VISIBLE
                b.badgeRating.text = "★ %.1f".format(vod.rating5based)
            } else {
                b.badgeRating.visibility = View.GONE
            }

            setupBackdropCard(vod.name, vod.streamIcon, isMovie = true)
        }

        // ── Series: same backdrop strategy ───────────────────────────────

        private fun bindSeries(series: Series) {
            b.tvTitle.text = series.name
            if (!series.genre.isNullOrBlank()) {
                b.tvSubtitle.text = series.genre
                b.tvSubtitle.visibility = View.VISIBLE
            } else {
                b.tvSubtitle.visibility = View.GONE
            }
            b.badgeLive.visibility = View.GONE
            if (series.rating5based > 0) {
                b.badgeRating.visibility = View.VISIBLE
                b.badgeRating.text = "★ %.1f".format(series.rating5based)
            } else {
                b.badgeRating.visibility = View.GONE
            }

            // Prefer Xtream backdrop_path list, then TMDb search
            val xtreamBackdrop = series.backdropPath
                ?.split(",")?.firstOrNull { it.startsWith("http") }
            if (!xtreamBackdrop.isNullOrBlank()) {
                b.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                b.ivThumbnail.setPadding(0, 0, 0, 0)
                Glide.with(b.root).load(xtreamBackdrop)
                    .placeholder(R.drawable.ic_series)
                    .transition(DrawableTransitionOptions.withCrossFade(150))
                    .into(b.ivThumbnail)
                extractColor(xtreamBackdrop)
            } else {
                setupBackdropCard(series.name, series.cover, isMovie = false)
            }
        }

        // ── Shared backdrop loader with TMDb fallback ─────────────────────

        private fun setupBackdropCard(title: String, fallbackUrl: String?, isMovie: Boolean) {
            b.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            b.ivThumbnail.setPadding(0, 0, 0, 0)

            // Load local icon first as immediate placeholder
            Glide.with(b.root)
                .load(fallbackUrl)
                .placeholder(if (isMovie) R.drawable.ic_vod else R.drawable.ic_series)
                .error(if (isMovie) R.drawable.ic_vod else R.drawable.ic_series)
                .into(b.ivThumbnail)

            // Mark this bind slot so stale completions don't overwrite newer binds
            val tag = "${if (isMovie) "m" else "tv"}:$title"
            b.ivThumbnail.tag = tag

            tmdbJob = scope.launch {
                val backdrop = TmdbImageManager.getBackdrop(title, isMovie)
                    ?: if (!isMovie) null else TmdbImageManager.getBackdrop(title, isMovie = false)
                if (backdrop != null && b.ivThumbnail.tag == tag) {
                    Glide.with(b.root)
                        .load(backdrop)
                        .placeholder(b.ivThumbnail.drawable)
                        .transition(DrawableTransitionOptions.withCrossFade(300))
                        .into(b.ivThumbnail)
                    extractColor(backdrop)
                }
            }
        }

        private fun extractColor(imageUrl: String) {
            // Re-request as bitmap just for color extraction (Glide caches it)
            Glide.with(b.root).asBitmap().load(imageUrl)
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(r: android.graphics.Bitmap,
                        t: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                        ColorExtractor.extract(r) { scheme ->
                            b.root.post { b.cardColorBar.setBackgroundColor(scheme.primary) }
                        }
                    }
                    override fun onLoadCleared(p: android.graphics.drawable.Drawable?) {}
                })
        }

        private fun animateFocus(focused: Boolean) {
            val scale = if (focused) 1.08f else 1f
            b.root.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            b.root.elevation = if (focused) 12f else 3f
            b.root.setFocusBorder(focused)
        }

        fun setWatchProgress(progress: Int, max: Int) {
            if (progress > 0 && max > 0) {
                b.progressWatched.visibility = View.VISIBLE
                b.progressWatched.max = max
                b.progressWatched.progress = progress
            } else {
                b.progressWatched.visibility = View.GONE
            }
        }
    }
}
