package com.socatv.nova.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import java.security.MessageDigest

/**
 * Runtime integrity checks. Called once in NovaApp.onCreate().
 * If any check fails, the app exits — prevents repackaged/cracked APKs from running.
 */
object AppSecurity {

    // Signing cert SHA-256 fingerprint — split to resist static analysis
    private val CERT = buildString {
        append("2C86AD6023C24B"); append("F38D5B3BC9A6D5")
        append("FB86018059D34E"); append("62CBDC863B35C2")
        append("484A02F2")
    }.lowercase()

    fun verify(context: Context) {
        if (!checkSignature(context) || isDebuggable(context)) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun checkSignature(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val sigs = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures ?: return false
            if (sigs.isEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            val fp = md.digest(sigs[0].toByteArray())
                .joinToString("") { "%02x".format(it) }
            fp == CERT
        } catch (_: Exception) { false }
    }

    private fun isDebuggable(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || Debug.isDebuggerConnected()
    }
}
