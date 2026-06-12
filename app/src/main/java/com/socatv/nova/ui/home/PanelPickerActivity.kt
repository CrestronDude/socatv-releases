package com.socatv.nova.ui.home

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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.socatv.nova.NovaApp
import com.socatv.nova.R
import com.socatv.nova.data.repository.IptvRepository
import com.socatv.nova.ui.account.AccountActivity
import com.socatv.nova.ui.browse.BrowseActivity
import com.socatv.nova.ui.epg.GridEpgActivity
import com.socatv.nova.ui.login.LoginActivity
import com.socatv.nova.ui.multiscreen.MultiScreenActivity
import com.socatv.nova.ui.paywall.PaywallActivity
import com.socatv.nova.ui.search.SearchActivity
import com.socatv.nova.ui.settings.SettingsActivity
import com.socatv.nova.ui.subscription.SubscriptionActivity
import com.socatv.nova.utils.AppUpdater
import com.socatv.nova.utils.LicenseManager
import com.socatv.nova.utils.PanelAvailability
import com.socatv.nova.utils.Prefs
import com.socatv.nova.utils.RemoteConfigManager
import com.socatv.nova.utils.TrialManager
import com.socatv.nova.utils.setFocusBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Panel(
    val id: String,
    val label: String,
    val subLabel: String,
    val iconRes: Int
)

class PanelPickerActivity : AppCompatActivity() {

    private val repo by lazy { IptvRepository(NovaApp.instance.database) }
    private lateinit var adapter: PanelAdapter

    private val allPanels = listOf(
        Panel("live",         "LIVE TV",       "LIVE.TV",    R.drawable.ic_live),
        Panel("epg",          "TV GUIDE",      "GUIDE",      R.drawable.ic_epg),
        Panel("vod",          "MOVIES",        "VOD",        R.drawable.ic_vod),
        Panel("series",       "SERIES",        "SERIES",     R.drawable.ic_series),
        Panel("all",          "ALL STREAMS",   "ALL",        R.drawable.ic_all_streams),
        Panel("multiscreen",  "MULTI-SCREEN",  "MULTI",      R.drawable.ic_multiscreen),
        Panel("catchup",      "CATCH-UP",      "CATCH UP",   R.drawable.ic_catchup),
        Panel("radio",        "RADIO",         "RADIO",      R.drawable.ic_radio),
        Panel("favorites",    "FAVORITES",     "FAVORITES",  R.drawable.ic_favorites),
        Panel("search",       "SEARCH",        "SEARCH",     R.drawable.ic_search),
        Panel("subscription", "SUBSCRIPTION",  "LICENSE",    R.drawable.ic_subscription),
        Panel("account",      "ACCOUNT",       "ACCOUNT",    R.drawable.ic_account),
        Panel("settings",     "SETTINGS",      "SETTINGS",   R.drawable.ic_settings)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel_picker)

        applyFanart()
        setupGrid()
        setupHeaderInfo()
        refreshStatusBars()
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.tvUser)?.text =
            if (Prefs.isLoggedIn) Prefs.username.uppercase() else "GUEST"
        refreshStatusBars()
        AppUpdater.showPendingDialog(this, lifecycleScope)

        // Register for live availability updates from the background probe started in LoginActivity
        PanelAvailability.onUpdated = { refreshPanelAvailability() }
        refreshPanelAvailability()
    }

    override fun onPause() {
        super.onPause()
        PanelAvailability.onUpdated = null
    }

    private fun applyFanart() {
        CoroutineScope(Dispatchers.Main).launch {
            val url = withContext(Dispatchers.IO) {
                try { repo.getTrendingBackdrop() } catch (_: Exception) { null }
            }
            if (!url.isNullOrBlank()) {
                Glide.with(this@PanelPickerActivity)
                    .load(url)
                    .centerCrop()
                    .into(findViewById(R.id.imgFanart))
            }
        }
    }

    private fun setupGrid() {
        val rv = findViewById<RecyclerView>(R.id.rvPanels)
        rv.layoutManager = GridLayoutManager(this, 4)
        adapter = PanelAdapter(allPanels) { panel -> onPanelSelected(panel) }
        rv.adapter = adapter
        // Focus will be set after availability is known; default to LIVE TV (index 0) for now
        rv.post {
            rv.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun refreshPanelAvailability() {
        val rv = findViewById<RecyclerView>(R.id.rvPanels) ?: return
        adapter.notifyDataSetChanged()

        // Move focus to the best available panel
        val bestId  = PanelAvailability.bestPanelId()
        val bestIdx = allPanels.indexOfFirst { it.id == bestId }.takeIf { it >= 0 } ?: 0
        rv.post {
            rv.findViewHolderForAdapterPosition(bestIdx)?.itemView?.requestFocus()
        }
    }

    private fun refreshStatusBars() {
        val cfg = RemoteConfigManager.getCached()

        val layoutAnn = findViewById<android.widget.LinearLayout>(R.id.layoutAnnouncement)
        val ann = cfg.announcement
        if (ann.isNotBlank()) {
            layoutAnn?.visibility = View.VISIBLE
            layoutAnn?.findViewById<TextView>(R.id.tvAnnouncement)?.text = ann
        } else {
            layoutAnn?.visibility = View.GONE
        }

        val layoutTrial = findViewById<android.widget.LinearLayout>(R.id.layoutTrialBar)
        val tvBarText   = layoutTrial?.findViewById<TextView>(R.id.tvTrialBarText)
        val tvBarAction = layoutTrial?.findViewById<TextView>(R.id.tvTrialBarAction)

        val isLicensed  = LicenseManager.hasValidLicense()
        val trialStatus = TrialManager.getStatus(cfg.trialDays)
        val daysLeft    = TrialManager.getDaysRemaining(cfg.trialDays)

        when {
            isLicensed -> {
                layoutTrial?.visibility = View.VISIBLE
                tvBarText?.text = "★  Licensed — full access active"
                tvBarText?.setTextColor(0xFF00DC6E.toInt())
                tvBarAction?.visibility = View.GONE
            }
            trialStatus == TrialManager.Status.ACTIVE -> {
                layoutTrial?.visibility = View.VISIBLE
                tvBarText?.text = "◉  Free Trial — $daysLeft day${if (daysLeft != 1) "s" else ""} remaining"
                tvBarText?.setTextColor(0xFF00DCFF.toInt())
                tvBarAction?.visibility = View.VISIBLE
                tvBarAction?.text = "UPGRADE"
                tvBarAction?.setTextColor(0xFF00DCFF.toInt())
                tvBarAction?.setOnClickListener {
                    startActivity(Intent(this, SubscriptionActivity::class.java))
                }
            }
            else -> {
                layoutTrial?.visibility = View.VISIBLE
                tvBarText?.text = "✕  Trial expired — activate a license to continue"
                tvBarText?.setTextColor(0xFFFF5555.toInt())
                tvBarAction?.visibility = View.VISIBLE
                tvBarAction?.text = "GET ACCESS"
                tvBarAction?.setTextColor(0xFFFF5555.toInt())
                tvBarAction?.setOnClickListener {
                    startActivity(Intent(this, PaywallActivity::class.java).apply {
                        putExtra("trial_expired", true)
                    })
                }
            }
        }
    }

    private fun setupHeaderInfo() {
        val tvTime = findViewById<TextView>(R.id.tvTime)
        val tvDate = findViewById<TextView>(R.id.tvDate)
        val tvUser = findViewById<TextView>(R.id.tvUser)

        val now = java.util.Calendar.getInstance()
        tvTime.text = String.format("%02d:%02d",
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE))
        tvDate.text = android.text.format.DateFormat.format("EEE, dd MMM yyyy", now).toString()
        tvUser.text = if (Prefs.isLoggedIn) Prefs.username.uppercase() else "GUEST"
    }

    private fun onPanelSelected(panel: Panel) {
        if (panel.id == "settings")     { startActivity(Intent(this, SettingsActivity::class.java)); return }
        if (panel.id == "search")       { startActivity(Intent(this, SearchActivity::class.java)); return }
        if (panel.id == "subscription") { startActivity(Intent(this, SubscriptionActivity::class.java)); return }

        if (!Prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                putExtra("pending_panel_id", panel.id)
            })
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            return
        }

        navigateToContent(panel.id)
    }

    private fun navigateToContent(panelId: String) {
        val intent = when (panelId) {
            "epg"         -> Intent(this, GridEpgActivity::class.java)
            "multiscreen" -> Intent(this, MultiScreenActivity::class.java)
            "account"     -> Intent(this, AccountActivity::class.java)
            "search"      -> Intent(this, SearchActivity::class.java)
            else -> Intent(this, BrowseActivity::class.java).apply {
                putExtra("content_type", when (panelId) {
                    "live"      -> "LIVE"
                    "vod"       -> "VOD"
                    "series"    -> "SERIES"
                    "radio"     -> "RADIO"
                    "favorites" -> "FAVORITES"
                    "catchup"   -> "CATCHUP"
                    "all"       -> "ALL"
                    else        -> "LIVE"
                })
            }
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { showExitDialog(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.NovaDialogTheme)
            .setTitle("Exit SocaTV Nova?")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class PanelAdapter(
    private val items: List<Panel>,
    private val onClick: (Panel) -> Unit
) : RecyclerView.Adapter<PanelAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon:  ImageView = view.findViewById(R.id.ivPanelIcon)
        val label: TextView  = view.findViewById(R.id.tvPanelLabel)
        val sub:   TextView  = view.findViewById(R.id.tvPanelSub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_panel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val panel     = items[position]
        val available = PanelAvailability.isAvailable(panel.id)
        val probing   = PanelAvailability.live == -1   // still probing

        holder.icon.setImageResource(panel.iconRes)
        holder.label.text = panel.label
        holder.sub.text   = panel.subLabel

        // Dim panels that are confirmed unavailable; loading state stays full alpha
        val alpha = when {
            probing    -> 0.85f   // subtle dim while we're still checking
            available  -> 1.0f
            else       -> 0.40f   // clearly unavailable
        }
        holder.itemView.alpha = alpha

        holder.itemView.isFocusable           = true
        holder.itemView.isFocusableInTouchMode = true
        holder.itemView.setOnClickListener { onClick(panel) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                onClick(panel); true
            } else false
        }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.08f else 1f)
                .scaleY(if (hasFocus) 1.08f else 1f)
                .setDuration(120).start()
            holder.label.setTextColor(
                if (hasFocus) 0xFF00DCFF.toInt() else 0xFFFFFFFF.toInt()
            )
            v.setFocusBorder(hasFocus)
        }
    }

    override fun getItemCount() = items.size
}
