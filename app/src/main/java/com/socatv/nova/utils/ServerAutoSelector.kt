package com.socatv.nova.utils

import android.util.Log
import com.socatv.nova.data.repository.IptvRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Silently races multiple panel server endpoints and returns the first successful auth.
 * Never surfaces individual errors — only returns null when ALL endpoints fail.
 */
object ServerAutoSelector {

    private const val TAG = "ServerAutoSelector"

    // Fallback — only used when remote config has no panelServers list
    private val FALLBACK_SERVERS = listOf(
        "http://hostengine.live",
        "http://hostengine.live:8080",
        "http://hostengine.live:80",
        "http://hostengine.live:25461",
        "http://hostengine.live:8000"
    )

    data class AuthResult(
        val serverUrl: String,
        val response: com.socatv.nova.data.model.AuthResponse
    )

    /**
     * Launches all server probes in parallel. Returns as soon as ONE succeeds.
     * If ALL fail (timeout or error), returns null.
     */
    suspend fun findWorkingServer(
        repo: IptvRepository,
        username: String,
        password: String,
        extraServers: List<String> = emptyList()
    ): AuthResult? = withContext(Dispatchers.IO) {
        // Remote config servers take priority — admin can push new servers anytime
        val remoteServers = RemoteConfigManager.getCached().panelServers
            .filter { it.isNotBlank() }
            .map { it.trimEnd('/') }
        val baseServers = remoteServers.ifEmpty { FALLBACK_SERVERS }

        val savedServer = Prefs.serverUrl.takeIf { it.isNotBlank() }
        val candidates = buildList {
            savedServer?.let { add(it.trimEnd('/')) }
            extraServers.filter { it.isNotBlank() }.forEach { add(it.trimEnd('/')) }
            baseServers.forEach { add(it) }
        }.distinct()

        Log.d(TAG, "Racing ${candidates.size} servers for $username")

        // Buffered channel — capacity = total candidates, senders never block
        val resultCh = Channel<AuthResult?>(candidates.size)
        var found: AuthResult? = null

        coroutineScope {
            val probes = candidates.map { server ->
                launch {
                    val result = withTimeoutOrNull(9_000L) {
                        try {
                            val r = repo.authenticate(server, username, password)
                            if (r.isSuccess) AuthResult(server, r.getOrThrow()) else null
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e   // let cancellation propagate
                            null
                        }
                    }
                    // trySend never throws even if channel is full/closed
                    resultCh.trySend(result)
                }
            }

            // Collect results; stop as soon as we get a success
            repeat(candidates.size) {
                if (found != null) return@repeat    // already found — skip remaining receives
                val r = resultCh.receive()
                if (r != null) {
                    found = r
                    Log.d(TAG, "Got success from ${r.serverUrl}")
                    probes.forEach { it.cancel() }  // cancel remaining outstanding probes
                }
            }
        }

        resultCh.close()
        found
    }

    fun addCustomServer(url: String) {
        val existing = getCustomServers().toMutableList()
        val clean = url.trimEnd('/')
        if (!existing.contains(clean)) {
            existing.add(0, clean)
            Prefs.customServersJson = existing.joinToString("|")
        }
    }

    fun getCustomServers(): List<String> {
        val s = Prefs.customServersJson
        return if (s.isBlank()) emptyList() else s.split("|").filter { it.isNotBlank() }
    }
}
