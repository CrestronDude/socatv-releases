package com.socatv.nova.ui.multiscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.model.Category
import com.socatv.nova.data.model.Channel
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.databinding.ActivityChannelPickerBinding
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChannelPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPickerBinding
    private val repo by lazy { IptvRepository(NovaApp.instance.database) }

    private var allChannels: List<Channel> = emptyList()
    private var selectedCategoryId: String = ""   // empty = all

    private lateinit var categoryAdapter: PickerCategoryAdapter
    private lateinit var channelAdapter: PickerChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapters()
        loadData()

        binding.btnPickerBack.setOnClickListener { finish() }
        binding.btnPickerBack.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(100).start()
            v.setFocusBorder(f)
        }
    }

    private fun setupAdapters() {
        categoryAdapter = PickerCategoryAdapter { catId ->
            selectedCategoryId = catId
            filterChannels()
        }
        binding.rvPickerCategories.layoutManager = LinearLayoutManager(this)
        binding.rvPickerCategories.adapter = categoryAdapter

        channelAdapter = PickerChannelAdapter { ch ->
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("stream_id",   ch.streamId)
                putExtra("stream_name", ch.name)
                putExtra("stream_icon", ch.streamIcon)
                putExtra("stream_url",  ch.directSource)
            })
            finish()
        }
        binding.rvPickerChannels.layoutManager = GridLayoutManager(this, 5)
        binding.rvPickerChannels.adapter = channelAdapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            // Load channels
            repo.getLiveStreams(null).fold(
                onSuccess = { allChannels = it },
                onFailure = {
                    allChannels = try {
                        NovaApp.instance.database.channelDao().getAllChannels().first()
                    } catch (e: Exception) { emptyList() }
                }
            )

            // Load categories
            val cats: List<Category> = try {
                repo.getLiveCategories().getOrDefault(emptyList())
            } catch (e: Exception) { emptyList() }

            // Build list with "All" sentinel first
            val allEntry = Category(categoryId = "", categoryName = "All Channels", parentId = 0)
            categoryAdapter.submit(listOf(allEntry) + cats)
            selectedCategoryId = ""
            filterChannels()

            binding.rvPickerCategories.post {
                binding.rvPickerCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun filterChannels() {
        val filtered = if (selectedCategoryId.isEmpty()) allChannels
        else allChannels.filter { it.categoryId == selectedCategoryId }
        channelAdapter.submit(filtered)
        binding.rvPickerChannels.scrollToPosition(0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}

// ── Category sidebar ──────────────────────────────────────────────────────────

class PickerCategoryAdapter(
    private val onSelect: (String) -> Unit
) : RecyclerView.Adapter<PickerCategoryAdapter.VH>() {

    private var items: List<Category> = emptyList()
    private var selectedPos = 0

    fun submit(list: List<Category>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvPickerCatName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_picker_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        holder.tvName.text = cat.categoryName
        val isSel = (position == selectedPos)
        holder.tvName.setTextColor(if (isSel) 0xFF00DCFF.toInt() else 0xFFCCCCCC.toInt())
        holder.itemView.setBackgroundColor(if (isSel) 0x3300DCFF else 0x00000000)
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener {
            val prev = selectedPos
            selectedPos = position
            notifyItemChanged(prev)
            notifyItemChanged(position)
            onSelect(cat.categoryId)
        }
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(80).start()
            v.setFocusBorder(f)
        }
    }

    override fun getItemCount() = items.size
}

// ── Channel grid ──────────────────────────────────────────────────────────────

class PickerChannelAdapter(
    private val onSelect: (Channel) -> Unit
) : RecyclerView.Adapter<PickerChannelAdapter.VH>() {

    private var channels: List<Channel> = emptyList()

    fun submit(list: List<Channel>) {
        channels = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivLogo: ImageView = v.findViewById(R.id.ivPickerLogo)
        val tvName: TextView  = v.findViewById(R.id.tvPickerName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_picker, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = channels[position]
        holder.tvName.text = ch.name
        Glide.with(holder.itemView).load(ch.streamIcon).placeholder(R.drawable.ic_live).into(holder.ivLogo)
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { onSelect(ch) }
        holder.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(100).start()
            v.setFocusBorder(f)
        }
    }

    override fun getItemCount() = channels.size
}
