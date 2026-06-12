package com.socatv.nova.data.model

import com.socatv.nova.utils.Prefs
import org.json.JSONArray
import org.json.JSONObject

data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val username: String,
    val password: String
)

object ServerProfileManager {

    fun getProfiles(): List<ServerProfile> {
        val json = Prefs.serverProfilesJson
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ServerProfile(
                    id       = obj.optString("id", i.toString()),
                    name     = obj.optString("name", "Server ${i + 1}"),
                    url      = obj.optString("url", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveProfiles(profiles: List<ServerProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("url", p.url)
                put("username", p.username)
                put("password", p.password)
            })
        }
        Prefs.serverProfilesJson = arr.toString()
    }

    fun addProfile(profile: ServerProfile) {
        val list = getProfiles().toMutableList()
        list.add(profile)
        saveProfiles(list)
    }

    fun removeProfile(id: String) {
        val list = getProfiles().filter { it.id != id }
        saveProfiles(list)
    }

    fun getActive(): ServerProfile? {
        val list = getProfiles()
        val idx = Prefs.activeServerProfileIndex
        return list.getOrNull(idx)
    }

    fun switchTo(index: Int) {
        val list = getProfiles()
        val profile = list.getOrNull(index) ?: return
        Prefs.activeServerProfileIndex = index
        Prefs.serverUrl = profile.url
        Prefs.username = profile.username
        Prefs.password = profile.password
    }

    fun syncCurrentToProfiles() {
        if (Prefs.serverUrl.isBlank() || Prefs.username.isBlank()) return
        val list = getProfiles()
        if (list.isEmpty()) {
            addProfile(ServerProfile(
                id = java.util.UUID.randomUUID().toString(),
                name = Prefs.username,
                url = Prefs.serverUrl,
                username = Prefs.username,
                password = Prefs.password
            ))
        }
    }
}
