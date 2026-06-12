package com.socatv.nova.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.socatv.nova.data.model.Profile
import com.socatv.nova.utils.Prefs
import java.util.UUID

class ProfileRepository {

    private val gson = Gson()
    private val MAX_PROFILES = 4

    fun getAllProfiles(): List<Profile> {
        val json = Prefs.profilesJson
        return if (json.isBlank()) {
            // Create default profile if none exist
            val default = Profile(id = "default", name = "Main", avatarIndex = 0)
            saveProfiles(listOf(default))
            listOf(default)
        } else {
            val type = object : TypeToken<List<Profile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        }
    }

    fun getActiveProfile(): Profile {
        val activeId = Prefs.activeProfileId
        return getAllProfiles().find { it.id == activeId } ?: getAllProfiles().firstOrNull()
            ?: Profile(id = "default", name = "Main", avatarIndex = 0)
    }

    fun setActiveProfile(profileId: String) {
        Prefs.activeProfileId = profileId
    }

    fun createProfile(name: String, avatarIndex: Int, isPinProtected: Boolean = false, pin: String = "", isKidsMode: Boolean = false): Result<Profile> {
        val profiles = getAllProfiles()
        if (profiles.size >= MAX_PROFILES) {
            return Result.failure(Exception("Maximum $MAX_PROFILES profiles allowed"))
        }
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(20),
            avatarIndex = avatarIndex,
            isPinProtected = isPinProtected,
            pin = pin,
            isKidsMode = isKidsMode
        )
        saveProfiles(profiles + profile)
        return Result.success(profile)
    }

    fun updateProfile(updated: Profile): Boolean {
        val profiles = getAllProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.id == updated.id }
        if (idx == -1) return false
        profiles[idx] = updated
        saveProfiles(profiles)
        return true
    }

    fun deleteProfile(profileId: String): Boolean {
        val profiles = getAllProfiles().toMutableList()
        if (profiles.size <= 1) return false // can't delete last profile
        if (!profiles.removeIf { it.id == profileId }) return false
        saveProfiles(profiles)
        if (Prefs.activeProfileId == profileId) {
            Prefs.activeProfileId = profiles.first().id
        }
        return true
    }

    fun verifyPin(profileId: String, pin: String): Boolean {
        val profile = getAllProfiles().find { it.id == profileId } ?: return false
        return !profile.isPinProtected || profile.pin == pin
    }

    private fun saveProfiles(profiles: List<Profile>) {
        Prefs.profilesJson = gson.toJson(profiles)
    }

    companion object {
        val AVATAR_COUNT = 8 // number of avatar options available
    }
}
