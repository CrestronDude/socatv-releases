package com.socatv.nova.utils

/**
 * Manages the 10-day free trial.
 * Trial start is recorded on first launch and never reset (even on app update/reinstall
 * unless the user clears app data — acceptable for a $10/year app).
 */
object TrialManager {

    private const val KEY_INSTALL_TS = "trial_install_ts"
    private const val KEY_TRIAL_SEEN  = "trial_paywall_seen"

    enum class Status { ACTIVE, EXPIRED, LICENSED }

    /** Call AFTER checking license — only used if user has no valid license. */
    fun getStatus(trialDays: Int = 10): Status {
        if (LicenseManager.hasValidLicense()) return Status.LICENSED

        val installTs = Prefs.getLong(KEY_INSTALL_TS, 0L)
        if (installTs == 0L) {
            // First ever launch — record now
            Prefs.putLong(KEY_INSTALL_TS, System.currentTimeMillis())
            return Status.ACTIVE
        }

        val elapsedDays = (System.currentTimeMillis() - installTs) / 86_400_000L
        return if (elapsedDays < trialDays) Status.ACTIVE else Status.EXPIRED
    }

    fun getDaysRemaining(trialDays: Int = 10): Int {
        val installTs = Prefs.getLong(KEY_INSTALL_TS, System.currentTimeMillis())
        val elapsed = (System.currentTimeMillis() - installTs) / 86_400_000L
        return (trialDays - elapsed).toInt().coerceAtLeast(0)
    }

    fun getTrialDayNumber(trialDays: Int = 10): Int {
        val installTs = Prefs.getLong(KEY_INSTALL_TS, System.currentTimeMillis())
        val elapsed = (System.currentTimeMillis() - installTs) / 86_400_000L
        return (elapsed + 1).toInt().coerceIn(1, trialDays + 1)
    }

    fun markPaywallSeen() = Prefs.putBoolean(KEY_TRIAL_SEEN, true)
    fun hasSeenPaywall()  = Prefs.getBoolean(KEY_TRIAL_SEEN, false)
}
