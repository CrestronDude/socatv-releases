package com.socatv.nova.utils

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Validates HMAC-signed license keys — 100% offline, no server required.
 *
 * Key format:  SOCANOVA-YYYYMMDD-XXXXXXXXXXXXXXXX
 *   YYYYMMDD   = expiry date (inclusive)
 *   XXXX...    = first 16 hex chars of HMAC-SHA256("SOCANOVA-YYYYMMDD", SECRET)
 *
 * Generate keys with the Admin Panel HTML tool (admin_panel.html).
 */
object LicenseManager {

    private const val PREFIX = "SOCANOVA"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_EXPIRY  = "license_expiry_ts"

    // ── Secret key: split across multiple strings to make APK extraction harder ──
    private val SECRET get() = buildString {
        append("pShzo7G5")
        append("Y1zoFPfw")
        append("uUC3KEn6")
        append("ixzqWycz")
    }

    enum class LicenseStatus {
        VALID,      // key is correct and not expired
        EXPIRED,    // key was valid but has passed its expiry date
        INVALID,    // key format or HMAC doesn't match
        NONE        // no key entered
    }

    data class LicenseResult(val status: LicenseStatus, val expiryDate: Date? = null)

    /** Returns true if a non-expired valid license is stored. */
    fun hasValidLicense(): Boolean {
        val stored = Prefs.getString(KEY_LICENSE, "")
        if (stored.isBlank()) return false
        return validate(stored).status == LicenseStatus.VALID
    }

    /** Validate key and persist if valid. Returns the result. */
    fun activateLicense(key: String): LicenseResult {
        val result = validate(key.trim().uppercase())
        if (result.status == LicenseStatus.VALID) {
            Prefs.putString(KEY_LICENSE, key.trim().uppercase())
            Prefs.putLong(KEY_EXPIRY, result.expiryDate?.time ?: 0L)
        }
        return result
    }

    fun validate(key: String): LicenseResult {
        val k = key.trim().uppercase()
        val parts = k.split("-")
        // Expect SOCANOVA-YYYYMMDD-XXXX...
        if (parts.size != 3 || parts[0] != PREFIX) return LicenseResult(LicenseStatus.INVALID)

        val dateStr = parts[1]
        val sig     = parts[2]

        if (dateStr.length != 8) return LicenseResult(LicenseStatus.INVALID)

        val expiry = try {
            SimpleDateFormat("yyyyMMdd", Locale.US).parse(dateStr)
                ?: return LicenseResult(LicenseStatus.INVALID)
        } catch (_: Exception) {
            return LicenseResult(LicenseStatus.INVALID)
        }

        val expectedSig = hmac("$PREFIX-$dateStr").take(16).uppercase()
        if (!sig.equals(expectedSig, ignoreCase = true)) {
            return LicenseResult(LicenseStatus.INVALID)
        }

        // Expire at END of expiry date
        val endOfDay = Calendar.getInstance().apply {
            time = expiry
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.time

        return if (Date().before(endOfDay)) {
            LicenseResult(LicenseStatus.VALID, endOfDay)
        } else {
            LicenseResult(LicenseStatus.EXPIRED, endOfDay)
        }
    }

    fun getStoredLicense(): String = Prefs.getString(KEY_LICENSE, "")

    fun getExpiryDate(): Date? {
        val ts = Prefs.getLong(KEY_EXPIRY, 0L)
        return if (ts > 0) Date(ts) else null
    }

    fun clearLicense() {
        Prefs.putString(KEY_LICENSE, "")
        Prefs.putLong(KEY_EXPIRY, 0L)
    }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray())
        return raw.joinToString("") { "%02x".format(it) }
    }
}
