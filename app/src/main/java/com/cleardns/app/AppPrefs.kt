package com.cleardns.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Small persistence layer (SharedPreferences) for settings shared between
 * the UI, the VPN service and notifications.
 */
object AppPrefs {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("cleardns", Context.MODE_PRIVATE)

    private const val KEY_SKIPPED_UIDS = "skipped_uids"
    private const val KEY_CUSTOM_DOMAINS = "custom_domains"

    fun skippedUids(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_SKIPPED_UIDS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet() ?: emptySet()

    fun setSkippedUids(context: Context, uids: Set<Int>) {
        prefs(context).edit()
            .putStringSet(KEY_SKIPPED_UIDS, uids.map { it.toString() }.toSet())
            .apply()
    }

    fun customDomains(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_CUSTOM_DOMAINS, emptySet())?.toSet() ?: emptySet()

    fun setCustomDomains(context: Context, domains: Set<String>) {
        prefs(context).edit().putStringSet(KEY_CUSTOM_DOMAINS, domains).apply()
    }

    fun getBoolean(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(key, default)

    fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getLong(context: Context, key: String, default: Long): Long =
        prefs(context).getLong(key, default)

    fun putLong(context: Context, key: String, value: Long) {
        prefs(context).edit().putLong(key, value).apply()
    }

    const val KEY_BLOCKING = "blocking"
    const val KEY_AD_FREE_UNTIL = "ad_free_until"
    const val KEY_QUERIES_TOTAL = "queries_total"
    const val KEY_QUERIES_BLOCKED = "queries_blocked"
}
