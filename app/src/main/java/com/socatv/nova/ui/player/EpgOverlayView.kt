package com.socatv.nova.ui.player

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.socatv.nova.R
import com.socatv.nova.data.model.EpgEntry
import com.socatv.nova.utils.setFocusBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val rvEpg: RecyclerView
    private val tvChannelLabel: TextView
    private val epgAdapter: EpgAdapter

    init {
        LayoutInflater.from(context).inflate(R.layout.view_epg_overlay, this, true)
        rvEpg = findViewById(R.id.rvEpgList)
        tvChannelLabel = findViewById(R.id.tvEpgChannelLabel)

        epgAdapter = EpgAdapter()
        rvEpg.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvEpg.adapter = epgAdapter

        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#CC080810"))
    }

    fun setEpgData(entries: List<EpgEntry>) {
        epgAdapter.submitList(entries)
        // Slide up animation
        translationY = 200f
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(350).start()
    }

    fun setChannelName(name: String) {
        tvChannelLabel.text = name
    }

    override fun setVisibility(visibility: Int) {
        if (visibility == View.VISIBLE && this.visibility != View.VISIBLE) {
            super.setVisibility(visibility)
            translationY = 200f
            alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(300).start()
        } else if (visibility != View.VISIBLE) {
            animate().translationY(200f).alpha(0f).setDuration(200).withEndAction {
                super.setVisibility(visibility)
            }.start()
        }
    }
}

class EpgAdapter : RecyclerView.Adapter<EpgAdapter.VH>() {
    private var entries: List<EpgEntry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submitList(list: List<EpgEntry>) {
        entries = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_entry, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(entry: EpgEntry) {
            val tvTitle = itemView.findViewById<TextView>(R.id.tvEpgTitle)
            val tvTime = itemView.findViewById<TextView>(R.id.tvEpgTime)
            val progressBar = itemView.findViewById<View>(R.id.epgProgressBar)

            tvTitle.text = entry.title?.let {
                try { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } catch (e: Exception) { it }
            } ?: "Unknown"

            val startMs = (entry.startTimestamp ?: 0) * 1000L
            val endMs = (entry.stopTimestamp ?: 0) * 1000L
            tvTime.text = if (startMs > 0) timeFormat.format(Date(startMs)) else ""

            // Show progress bar if currently airing
            val now = System.currentTimeMillis()
            if (entry.nowPlaying == 1 && startMs > 0 && endMs > startMs) {
                progressBar.visibility = View.VISIBLE
                val progress = ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
                progressBar.scaleX = progress
            } else {
                progressBar.visibility = View.GONE
            }

            // Highlight now playing
            itemView.alpha = if (entry.nowPlaying == 1) 1f else 0.7f
            tvTitle.setTextColor(if (entry.nowPlaying == 1) Color.parseColor("#00DCFF") else Color.WHITE)

            itemView.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f).setDuration(100).start()
                v.setFocusBorder(focused)
            }
            itemView.isFocusable = true
        }
    }
}
