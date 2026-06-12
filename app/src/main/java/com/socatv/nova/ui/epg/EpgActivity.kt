package com.socatv.nova.ui.epg

import android.content.Intent
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.model.EpgEntry
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityEpgBinding
import com.socatv.nova.ui.player.PlayerActivity
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgBinding
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    private val channelAdapter = EpgChannelAdapter { channel -> loadEpgForChannel(channel) }
    private val programAdapter = EpgProgramAdapter { entry, channel -> onProgramSelected(entry, channel) }

    private var currentChannel: Channel? = null
    private var epgJob: Job? = null
    private val epgDebounceHandler = Handler(Looper.getMainLooper())
    private var epgDebounceRunnable: Runnable? = null

    private var allChannels: List<Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChannelList()
        setupProgramList()
        setupSearch()
        setupHeader()
        loadChannels()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            v.setFocusBorder(f)
        }
    }

    private fun setupChannelList() {
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter
        binding.rvChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {}
        })
    }

    private fun setupProgramList() {
        binding.rvPrograms.layoutManager = LinearLayoutManager(this)
        binding.rvPrograms.adapter = programAdapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                val filtered = if (query.isEmpty()) allChannels
                    else allChannels.filter { it.name.contains(query, ignoreCase = true) }
                channelAdapter.submit(filtered)
                binding.tvChannelCount.text = "${filtered.size} CHANNELS"
            }
        })
    }

    private fun loadChannels() {
        showChannelLoading(true)
        lifecycleScope.launch {
            repo.getLiveStreams(null).fold(
                onSuccess = { channels ->
                    allChannels = channels
                    channelAdapter.submit(channels)
                    binding.tvChannelCount.text = "${channels.size} CHANNELS"
                    showChannelLoading(false)
                    // Auto-focus first item and load its EPG
                    binding.rvChannels.post {
                        val vh = binding.rvChannels.findViewHolderForAdapterPosition(0)
                        vh?.itemView?.requestFocus()
                        if (channels.isNotEmpty()) loadEpgForChannel(channels[0])
                    }
                },
                onFailure = {
                    showChannelLoading(false)
                    binding.tvNowPlaying.text = "Failed to load channels"
                }
            )
        }
    }

    private fun loadEpgForChannel(channel: Channel) {
        currentChannel = channel
        binding.tvEpgTitle.text = channel.name
        binding.tvNowPlaying.text = "Loading guide..."
        programAdapter.submit(emptyList(), channel)

        epgDebounceRunnable?.let { epgDebounceHandler.removeCallbacks(it) }
        epgDebounceRunnable = Runnable {
            epgJob?.cancel()
            epgJob = lifecycleScope.launch {
                repo.getShortEpg(channel.streamId).fold(
                    onSuccess = { entries ->
                        programAdapter.submit(entries, channel)
                        val now = entries.firstOrNull { it.nowPlaying == 1 }
                        binding.tvNowPlaying.text = when {
                            now != null -> "NOW: ${decodeTitle(now.title)}"
                            entries.isNotEmpty() -> decodeTitle(entries[0].title)
                            else -> "No guide available"
                        }
                    },
                    onFailure = {
                        binding.tvNowPlaying.text = "No guide available"
                        programAdapter.submit(emptyList(), channel)
                    }
                )
            }
        }
        epgDebounceHandler.postDelayed(epgDebounceRunnable!!, 350L)
    }

    private fun onProgramSelected(entry: EpgEntry, channel: Channel) {
        when {
            entry.nowPlaying == 1 -> {
                // Play live
                val url = channel.directSource
                    ?.takeIf { it.isNotBlank() && it.startsWith("http") }
                    ?: repo.buildStreamUrl(channel.streamId)
                launchPlayer(channel.streamId, channel.name, channel.streamIcon, url, "live")
            }
            entry.hasArchive == 1 && entry.startTimestamp != null && entry.stopTimestamp != null -> {
                val durationMinutes = ((entry.stopTimestamp - entry.startTimestamp) / 60).toInt()
                    .coerceAtLeast(1)
                val url = repo.buildTimeshiftUrl(channel.streamId, durationMinutes, entry.startTimestamp)
                val label = "${channel.name} — ${decodeTitle(entry.title)}"
                launchPlayer(channel.streamId, label, channel.streamIcon, url, "catchup")
            }
            else -> {
                android.widget.Toast.makeText(this,
                    "This program is not available for playback", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPlayer(id: String, name: String, icon: String?, url: String, type: String) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_id", id)
            putExtra("stream_name", name)
            putExtra("stream_icon", icon)
            putExtra("stream_url", url)
            putExtra("content_type", type)
        })
    }

    private fun showChannelLoading(show: Boolean) {
        binding.channelLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    internal fun decodeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown Program"
        return try {
            String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            raw
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        epgDebounceHandler.removeCallbacksAndMessages(null)
        epgJob?.cancel()
    }
}

// ─── Channel list adapter ────────────────────────────────────────────────────

class EpgChannelAdapter(
    private val onFocus: (Channel) -> Unit
) : RecyclerView.Adapter<EpgChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.ivEpgChannelLogo)
        val name: TextView  = v.findViewById(R.id.tvEpgChannelName)
        val num:  TextView  = v.findViewById(R.id.tvEpgChannelNum)

        fun bind(ch: Channel) {
            name.text = ch.name
            num.text  = if (ch.num > 0) ch.num.toString() else ""
            Glide.with(itemView).load(ch.streamIcon).placeholder(R.drawable.ic_live)
                .error(R.drawable.ic_live).into(logo)

            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f)
                    .scaleY(if (hasFocus) 1.04f else 1f).setDuration(80).start()
                v.setFocusBorder(hasFocus)
                if (hasFocus) onFocus(ch)
            }
            itemView.setOnClickListener { onFocus(ch) }
            itemView.isFocusable = true
        }
    }
}

// ─── Program list adapter ─────────────────────────────────────────────────────

class EpgProgramAdapter(
    private val onClick: (EpgEntry, Channel) -> Unit
) : RecyclerView.Adapter<EpgProgramAdapter.VH>() {

    private var items: List<EpgEntry> = emptyList()
    private var channel: Channel? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(list: List<EpgEntry>, ch: Channel) {
        items = list
        channel = ch
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_program, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = channel ?: return
        holder.bind(items[position], ch)
    }

    override fun getItemCount() = items.size

    inner class VH(private val v: View) : RecyclerView.ViewHolder(v) {
        val tvTime:    TextView    = v.findViewById(R.id.tvProgramTime)
        val tvTitle:   TextView    = v.findViewById(R.id.tvProgramTitle)
        val tvDesc:    TextView    = v.findViewById(R.id.tvProgramDesc)
        val badgeLive: TextView    = v.findViewById(R.id.badgeProgramLive)
        val badgeArch: TextView    = v.findViewById(R.id.badgeProgramArchive)
        val progress:  ProgressBar = v.findViewById(R.id.progressProgram)

        fun bind(entry: EpgEntry, ch: Channel) {
            val start = entry.startTimestamp?.let { timeFmt.format(Date(it * 1000)) } ?: "?"
            val end   = entry.stopTimestamp?.let  { timeFmt.format(Date(it * 1000)) } ?: "?"
            tvTime.text  = "$start – $end"

            val title = try {
                String(android.util.Base64.decode(entry.title ?: "", android.util.Base64.DEFAULT))
            } catch (e: Exception) { entry.title ?: "Unknown" }
            tvTitle.text = title

            val desc = try {
                String(android.util.Base64.decode(entry.description ?: "", android.util.Base64.DEFAULT))
            } catch (e: Exception) { entry.description ?: "" }
            if (desc.isNotBlank()) {
                tvDesc.visibility = View.VISIBLE
                tvDesc.text = desc
            } else {
                tvDesc.visibility = View.GONE
            }

            badgeLive.visibility = if (entry.nowPlaying == 1) View.VISIBLE else View.GONE
            badgeArch.visibility = if (entry.hasArchive == 1) View.VISIBLE else View.GONE

            // Show progress bar only for current program
            if (entry.nowPlaying == 1 && entry.startTimestamp != null && entry.stopTimestamp != null) {
                val total   = (entry.stopTimestamp - entry.startTimestamp).toFloat()
                val elapsed = (System.currentTimeMillis() / 1000 - entry.startTimestamp).toFloat()
                val pct = ((elapsed / total) * 100).toInt().coerceIn(0, 100)
                progress.visibility = View.VISIBLE
                progress.progress   = pct
            } else {
                progress.visibility = View.GONE
            }

            v.alpha = if (entry.stopTimestamp != null &&
                entry.stopTimestamp < System.currentTimeMillis() / 1000 &&
                entry.nowPlaying != 1) 0.55f else 1f

            v.setOnClickListener { onClick(entry, ch) }
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.03f else 1f)
                    .scaleY(if (hasFocus) 1.03f else 1f).setDuration(80).start()
                view.setFocusBorder(hasFocus)
            }
            v.isFocusable = true
        }
    }
}
