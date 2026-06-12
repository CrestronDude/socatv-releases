package com.socatv.nova.utils

import com.socatv.nova.data.repository.IptvRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Lightweight singleton that holds how many content items each panel type has.
 * Populated in the background right after login. PanelPickerActivity observes [onUpdated].
 *
 * Counts: -1 = not yet probed, 0 = confirmed empty, >0 = available
 */
object PanelAvailability {

    var live    = -1; private set
    var vod     = -1; private set
    var series  = -1; private set
    var catchup = -1; private set  // channels with tv_archive

    /** Called on main thread whenever any count changes. */
    @Volatile var onUpdated: (() -> Unit)? = null

    /** Reset to unknown (e.g. on logout or credential change). */
    fun reset() {
        live = -1; vod = -1; series = -1; catchup = -1
    }

    /**
     * Fires three parallel lightweight category-count probes.
     * Results are posted to the main thread via [onUpdated].
     * Silent on any error — never blocks the caller.
     */
    fun probeAsync(repo: IptvRepository, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val liveJob    = async { try { repo.getLiveCategories().getOrDefault(emptyList()).size } catch (_: Exception) { 0 } }
            val vodJob     = async { try { repo.getVodCategories().getOrDefault(emptyList()).size }  catch (_: Exception) { 0 } }
            val seriesJob  = async { try { repo.getSeriesCategories().getOrDefault(emptyList()).size } catch (_: Exception) { 0 } }

            // Update as each probe finishes (fastest-first)
            val liveCount   = liveJob.await()
            live = liveCount
            notifyOnMain()

            val vodCount    = vodJob.await()
            vod = vodCount
            notifyOnMain()

            val seriesCount = seriesJob.await()
            series = seriesCount
            // Catchup = how many live channels have tv_archive — estimate from live count
            catchup = if (liveCount > 0) 1 else 0
            notifyOnMain()
        }
    }

    /**
     * Returns the panel id that should receive initial focus.
     * Priority: LIVE → VOD → SERIES → first panel.
     */
    fun bestPanelId(): String = when {
        live    > 0 -> "live"
        vod     > 0 -> "vod"
        series  > 0 -> "series"
        else        -> "live"   // fallback — always show LIVE first
    }

    fun isAvailable(panelId: String): Boolean = when (panelId) {
        "live"        -> live    != 0
        "vod"         -> vod     != 0
        "series"      -> series  != 0
        "catchup"     -> catchup != 0
        "epg"         -> live    != 0   // EPG only useful if there's live TV
        "multiscreen" -> live    != 0
        "radio"       -> live    != 0
        else          -> true            // settings, account, search, etc. always available
    }

    private fun notifyOnMain() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onUpdated?.invoke() }
    }
}
