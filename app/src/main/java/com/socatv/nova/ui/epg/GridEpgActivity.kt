package com.socatv.nova.ui.epg

import android.content.Intent
import com.socatv.nova.utils.setFocusBorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.first
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.EpgEntry
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityGridEpgBinding
import com.socatv.nova.ui.player.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class GridEpgActivity : AppCompatActivity() {

    companion object {
        const val PIXELS_PER_MINUTE = 8  // 480px per hour
        const val CHANNEL_COL_WIDTH_DP = 180
        const val SLOT_MINUTES = 30       // time header slot size
    }

    private lateinit var binding: ActivityGridEpgBinding
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    private var allChannels: List<Channel> = emptyList()
    // epgData: streamId → list of programs
    private val epgData = mutableMapOf<String, List<EpgEntry>>()
    private val epgJobs = mutableMapOf<String, Job>()

    private lateinit var channelAdapter: GridChannelAdapter
    private lateinit var rowAdapter: GridRowAdapter

    private val nowMs = System.currentTimeMillis()
    // Grid starts 2 hours before now
    private val gridStartMs = nowMs - 2 * 3_600_000L
    // Total grid span: 8 hours
    private val gridEndMs = gridStartMs + 8 * 3_600_000L

    // Shared horizontal scroll position (pixels) for all rows + time header
    @Volatile private var sharedScrollX = 0
    private var isScrolling = false
    private var isSyncingVerticalScroll = false
    private val scrollHandler = Handler(Looper.getMainLooper())

    // Preview player
    private var previewJob: Job? = null
    private val previewDebounce = Handler(Looper.getMainLooper())

    // Focused channel for left-column highlight
    private var focusedChannelIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupChannelList()
        setupTimeHeader()
        setupGrid()
        loadChannels()
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private fun setupHeader() {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvCurrentTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        // Tick the clock every minute
        scrollHandler.postDelayed(object : Runnable {
            override fun run() {
                binding.tvCurrentTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                scrollHandler.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    // ── Channel list (left column) ────────────────────────────────────────────

    private fun setupChannelList() {
        channelAdapter = GridChannelAdapter { idx, ch ->
            focusedChannelIndex = idx
            ensureEpgLoaded(ch)
        }
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@GridEpgActivity)
            adapter = channelAdapter
            isNestedScrollingEnabled = false
        }
        // Sync vertical scroll between channel list and grid rows — guard prevents re-entrant loop
        binding.rvGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && !isSyncingVerticalScroll) {
                    isSyncingVerticalScroll = true
                    binding.rvChannels.scrollBy(0, dy)
                    isSyncingVerticalScroll = false
                }
            }
        })
        binding.rvChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && !isSyncingVerticalScroll) {
                    isSyncingVerticalScroll = true
                    binding.rvGrid.scrollBy(0, dy)
                    isSyncingVerticalScroll = false
                }
            }
        })
    }

    // ── Time header ───────────────────────────────────────────────────────────

    private fun setupTimeHeader() {
        val slots = mutableListOf<Long>()
        var t = gridStartMs - (gridStartMs % (SLOT_MINUTES * 60_000L))
        while (t <= gridEndMs) {
            slots.add(t)
            t += SLOT_MINUTES * 60_000L
        }
        binding.rvTimeHeader.apply {
            layoutManager = LinearLayoutManager(this@GridEpgActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = TimeHeaderAdapter(slots)
            isNestedScrollingEnabled = false
        }
    }

    // ── Grid rows ────────────────────────────────────────────────────────────

    private fun setupGrid() {
        rowAdapter = GridRowAdapter(
            onProgramClick = { ch, entry -> onProgramSelected(ch, entry) },
            onRowFocused   = { idx ->
                focusedChannelIndex = idx
                (binding.rvChannels.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(idx, binding.rvChannels.height / 3)
            },
            onRowScrolled  = { newScrollX ->
                sharedScrollX = newScrollX
                syncScrollAllRows(newScrollX, excludeIndex = null)
                syncTimeHeader(newScrollX)
            }
        )
        binding.rvGrid.apply {
            layoutManager = LinearLayoutManager(this@GridEpgActivity)
            adapter = rowAdapter
            isNestedScrollingEnabled = true
        }
        // Lazy-load EPG for every channel that scrolls into view
        binding.rvGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
                val last  = lm.findLastVisibleItemPosition()
                for (i in first..last) {
                    allChannels.getOrNull(i)?.let { ensureEpgLoaded(it) }
                }
            }
        })
    }

    private var isSyncingHorizontalScroll = false

    private fun syncScrollAllRows(scrollX: Int, excludeIndex: Int?) {
        if (isSyncingHorizontalScroll) return
        isSyncingHorizontalScroll = true
        val lm = binding.rvGrid.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        for (i in first..last) {
            if (i == excludeIndex) continue
            val vh = binding.rvGrid.findViewHolderForAdapterPosition(i) as? GridRowAdapter.VH
            vh?.syncScrollTo(scrollX)
        }
        isSyncingHorizontalScroll = false
    }

    private fun syncTimeHeader(scrollX: Int) {
        (binding.rvTimeHeader.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(0, -scrollX)
    }

    // ── Data loading ─────────────────────────────────────────────────────────

    private fun loadChannels() {
        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            repo.getLiveStreams(null).fold(
                onSuccess = { channels ->
                    allChannels = channels
                    channelAdapter.submit(channels)
                    rowAdapter.submitChannels(channels, epgData, gridStartMs, nowMs)
                    binding.progressLoading.visibility = View.GONE

                    // Immediately scroll to "now"
                    val nowOffset = ((nowMs - gridStartMs) / 60_000L * PIXELS_PER_MINUTE).toInt()
                    val initialScroll = max(0, nowOffset - dpToPx(CHANNEL_COL_WIDTH_DP))
                    sharedScrollX = initialScroll
                    binding.rvGrid.post {
                        syncScrollAllRows(initialScroll, excludeIndex = null)
                        syncTimeHeader(initialScroll)
                    }

                    // Focus first item
                    binding.rvGrid.post {
                        binding.rvGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }

                    // Pre-load EPG for first 20 visible channels; scroll listener handles the rest
                    channels.take(20).forEach { ensureEpgLoaded(it) }
                },
                onFailure = {
                    // Fall back to DB cache
                    lifecycleScope.launch {
                        NovaApp.instance.database.channelDao().getAllChannels().first().let { cached ->
                            binding.progressLoading.visibility = View.GONE
                            if (cached.isNotEmpty()) {
                                allChannels = cached
                                channelAdapter.submit(cached)
                                rowAdapter.submitChannels(cached, epgData, gridStartMs, nowMs)
                                cached.take(20).forEach { ensureEpgLoaded(it) }
                                android.widget.Toast.makeText(
                                    this@GridEpgActivity, "Using cached guide", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(
                                    this@GridEpgActivity, "No guide data available", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
        }
    }

    private fun ensureEpgLoaded(ch: Channel) {
        if (epgData.containsKey(ch.streamId)) return
        val existing = epgJobs[ch.streamId]
        if (existing?.isActive == true) return
        epgJobs[ch.streamId] = lifecycleScope.launch {
            repo.getShortEpg(ch.streamId).fold(
                onSuccess = { entries ->
                    epgData[ch.streamId] = entries
                    val idx = allChannels.indexOfFirst { it.streamId == ch.streamId }
                    if (idx >= 0) rowAdapter.updateRow(idx, entries)
                },
                onFailure = { epgData[ch.streamId] = emptyList() }
            )
        }
    }

    // ── Program selection ────────────────────────────────────────────────────

    private fun onProgramSelected(ch: Channel, entry: EpgEntry) {
        when {
            entry.nowPlaying == 1 -> {
                val url = ch.directSource?.takeIf { it.isNotBlank() && it.startsWith("http") }
                    ?: repo.buildStreamUrl(ch.streamId)
                launchPlayer(ch.streamId, ch.name, ch.streamIcon, url, "live")
            }
            entry.hasArchive == 1 && entry.startTimestamp != null && entry.stopTimestamp != null -> {
                val dur = ((entry.stopTimestamp - entry.startTimestamp) / 60).toInt().coerceAtLeast(1)
                val url = repo.buildTimeshiftUrl(ch.streamId, dur, entry.startTimestamp)
                val label = "${ch.name} — ${decodeTitle(entry.title)}"
                launchPlayer(ch.streamId, label, ch.streamIcon, url, "catchup")
            }
            else -> android.widget.Toast.makeText(
                this, "Not available for playback", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPlayer(id: String, name: String, icon: String?, url: String, type: String) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", id); putExtra("stream_name", name)
            putExtra("stream_icon", icon); putExtra("stream_url", url)
            putExtra("content_type", type)
        })
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun decodeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try { String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT)).trim() }
        catch (_: Exception) { raw }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    fun pxForMinutes(minutes: Long): Int = (minutes * PIXELS_PER_MINUTE).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        scrollHandler.removeCallbacksAndMessages(null)
        previewDebounce.removeCallbacksAndMessages(null)
        epgJobs.values.forEach { it.cancel() }
    }
}

// ── Time Header Adapter ────────────────────────────────────────────────────

class TimeHeaderAdapter(private val slots: List<Long>)
    : RecyclerView.Adapter<TimeHeaderAdapter.VH>() {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val slotWidth = GridEpgActivity.SLOT_MINUTES * GridEpgActivity.PIXELS_PER_MINUTE

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvGridTimeSlot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_time_slot, parent, false)
        v.layoutParams = ViewGroup.LayoutParams(
            slotWidth, ViewGroup.LayoutParams.MATCH_PARENT)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvTime.text = fmt.format(Date(slots[position]))
    }

    override fun getItemCount() = slots.size
}

// ── Channel (left column) Adapter ─────────────────────────────────────────

class GridChannelAdapter(
    private val onFocus: (Int, Channel) -> Unit
) : RecyclerView.Adapter<GridChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.ivGridChannelLogo)
        val name: TextView  = v.findViewById(R.id.tvGridChannelName)
        val num:  TextView  = v.findViewById(R.id.tvGridChannelNum)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val ch = items[pos]
        holder.name.text = ch.name
        holder.num.text  = if (ch.num > 0) ch.num.toString() else ""
        Glide.with(holder.itemView).load(ch.streamIcon)
            .placeholder(R.drawable.ic_live).error(R.drawable.ic_live).into(holder.logo)
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(80).start()
            v.setFocusBorder(f)
            if (f) onFocus(pos, ch)
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = items.size
}

// ── Grid Row Adapter ───────────────────────────────────────────────────────

class GridRowAdapter(
    private val onProgramClick: (Channel, EpgEntry) -> Unit,
    private val onRowFocused:   (Int) -> Unit,
    private val onRowScrolled:  (Int) -> Unit
) : RecyclerView.Adapter<GridRowAdapter.VH>() {

    private var channels: List<Channel> = emptyList()
    private var epgMap: Map<String, List<EpgEntry>> = emptyMap()
    private var gridStartMs: Long = 0L
    private var nowMs: Long = 0L

    fun submitChannels(ch: List<Channel>, epg: Map<String, List<EpgEntry>>, start: Long, now: Long) {
        channels = ch; epgMap = epg; gridStartMs = start; nowMs = now
        notifyDataSetChanged()
    }

    fun updateRow(index: Int, entries: List<EpgEntry>) {
        if (index >= 0 && index < channels.size) {
            epgMap = epgMap.toMutableMap().also { it[channels[index].streamId] = entries }
            notifyItemChanged(index)
        }
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rvPrograms: RecyclerView = v.findViewById(R.id.rvGridPrograms)
        private var programAdapter: GridProgramAdapter? = null

        fun bind(ch: Channel, epg: List<EpgEntry>?, start: Long, now: Long,
                 onProgramClick: (Channel, EpgEntry) -> Unit,
                 onRowFocused: (Int) -> Unit, pos: Int,
                 onScrolled: (Int) -> Unit) {

            val pa = GridProgramAdapter(ch, epg ?: emptyList(), start, now, onProgramClick)
            programAdapter = pa
            rvPrograms.adapter = pa
            if (rvPrograms.layoutManager == null) {
                rvPrograms.layoutManager = LinearLayoutManager(
                    itemView.context, LinearLayoutManager.HORIZONTAL, false)
            }
            rvPrograms.clearOnScrollListeners()
            rvPrograms.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dx != 0) {
                        val lm = rv.layoutManager as? LinearLayoutManager ?: return
                        val firstPos = lm.findFirstVisibleItemPosition()
                        val firstView = lm.findViewByPosition(firstPos) ?: return
                        val offset = -firstView.left + firstPos * (GridEpgActivity.SLOT_MINUTES * GridEpgActivity.PIXELS_PER_MINUTE)
                        onScrolled(offset)
                    }
                }
            })
            rvPrograms.setOnFocusChangeListener { _, f ->
                if (f) { onRowFocused(pos) }
            }
        }

        fun syncScrollTo(scrollX: Int) {
            val lm = rvPrograms.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(0, -scrollX)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val ch = channels[pos]
        holder.bind(ch, epgMap[ch.streamId], gridStartMs, nowMs, onProgramClick, onRowFocused, pos, onRowScrolled)
    }

    override fun getItemCount() = channels.size
}

// ── Program cell adapter within a single row ──────────────────────────────

class GridProgramAdapter(
    private val ch: Channel,
    private val entries: List<EpgEntry>,
    private val gridStartMs: Long,
    private val nowMs: Long,
    private val onClick: (Channel, EpgEntry) -> Unit
) : RecyclerView.Adapter<GridProgramAdapter.VH>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // If no EPG, create a single placeholder block spanning full grid
    private val displayEntries: List<EpgEntry> = entries.ifEmpty {
        listOf(EpgEntry(null, null, null, null, null, null, null, null,
            gridStartMs / 1000, (gridStartMs + 8 * 3_600_000L) / 1000))
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle:   TextView    = v.findViewById(R.id.tvGridProgramTitle)
        val tvTime:    TextView    = v.findViewById(R.id.tvGridProgramTime)
        val progress:  ProgressBar = v.findViewById(R.id.progressGridProgram)
        val badgeLive: TextView    = v.findViewById(R.id.badgeGridLive)
        val badgeArch: TextView    = v.findViewById(R.id.badgeGridArchive)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_program, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = displayEntries[position]
        val startSec = entry.startTimestamp ?: (gridStartMs / 1000)
        val endSec   = entry.stopTimestamp  ?: ((gridStartMs + 8 * 3_600_000L) / 1000)
        val durationMin = ((endSec - startSec) / 60).coerceAtLeast(15)
        val widthPx = (durationMin * GridEpgActivity.PIXELS_PER_MINUTE).toInt()

        holder.itemView.layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)

        val title = try {
            String(android.util.Base64.decode(entry.title ?: "", android.util.Base64.DEFAULT)).trim()
                .ifBlank { "—" }
        } catch (_: Exception) { entry.title ?: "—" }

        holder.tvTitle.text  = title
        holder.tvTime.text   = timeFmt.format(Date(startSec * 1000))
        holder.badgeLive.visibility  = if (entry.nowPlaying == 1) View.VISIBLE else View.GONE
        holder.badgeArch.visibility  = if (entry.hasArchive == 1) View.VISIBLE else View.GONE

        // Progress bar for current program
        val nowSec = nowMs / 1000
        if (entry.nowPlaying == 1 && startSec > 0 && endSec > startSec) {
            val pct = (((nowSec - startSec).toFloat() / (endSec - startSec)) * 100).toInt().coerceIn(0, 100)
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress   = pct
        } else {
            holder.progress.visibility = View.GONE
        }

        // Dim past programs
        val isPast = endSec < nowMs / 1000 && entry.nowPlaying != 1
        holder.itemView.alpha = if (isPast) 0.55f else 1f

        holder.itemView.setOnClickListener { onClick(ch, entry) }
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleY(if (f) 1.06f else 1f).setDuration(80).start()
            v.elevation = if (f) 8f else 2f
            v.setFocusBorder(f)
        }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = displayEntries.size
}
