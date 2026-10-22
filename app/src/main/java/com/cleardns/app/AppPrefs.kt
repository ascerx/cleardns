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
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_LIST_IDS = "list_ids"
    private const val KEY_ENDPOINT = "doh_endpoint"
    private const val KEY_MODE = "mode"
    private const val KEY_APP_RULES = "app_rules"
    private const val KEY_IP_RULES = "ip_rules"
    private const val KEY_ISOLATE_DOMAINS = "isolate_domains"

    val VALID_RULES = setOf("block", "bypass", "block_wifi", "block_mobile", "isolate")

    fun safety(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(key, default)

    fun setSafety(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun mode(context: Context, default: String): String =
        prefs(context).getString(KEY_MODE, default) ?: default

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun ipRules(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IP_RULES, emptySet())?.toSet() ?: emptySet()

    fun setIpRules(context: Context, rules: Set<String>) {
        prefs(context).edit().putStringSet(KEY_IP_RULES, rules).apply()
    }

    /** Per-uid firewall rules stored as "uid:state" entries.
     *  States: "block" | "bypass" | "block_wifi" | "block_mobile" | "isolate" */
    fun appRules(context: Context): Map<Int, String> =
        prefs(context).getStringSet(KEY_APP_RULES, emptySet())
            ?.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2 && parts[1] in VALID_RULES) {
                    parts[0].toIntOrNull()?.let { it to parts[1] }
                } else null
            }
            ?.toMap() ?: emptyMap()

    fun setAppRules(context: Context, rules: Map<Int, String>) {
        prefs(context).edit()
            .putStringSet(KEY_APP_RULES, rules.map { "${it.key}:${it.value}" }.toSet())
            .apply()
    }

    /** Per-uid isolate allow-domains: JSON { uid: csv } */
    fun isolateDomains(context: Context): Map<Int, Set<String>> {
        val json = prefs(context).getString(KEY_ISOLATE_DOMAINS, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().mapNotNull { key ->
                val uid = key.toIntOrNull() ?: return@mapNotNull null
                val csv = obj.optString(key)
                uid to csv.split(',').filter { it.isNotBlank() }.toSet()
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setIsolateDomains(context: Context, rules: Map<Int, Set<String>>) {
        val obj = org.json.JSONObject()
        rules.forEach { (uid, domains) -> obj.put(uid.toString(), domains.joinToString(",")) }
        prefs(context).edit().putString(KEY_ISOLATE_DOMAINS, obj.toString()).apply()
    }

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

    fun whitelist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()

    fun setWhitelist(context: Context, domains: Set<String>) {
        prefs(context).edit().putStringSet(KEY_WHITELIST, domains).apply()
    }

    fun listIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LIST_IDS, null) ?: defaultListIds()

    fun setListIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_LIST_IDS, ids).apply()
    }

    private fun defaultListIds(): Set<String> = setOf("oisd_small")

    fun endpoint(context: Context, default: String): String =
        prefs(context).getString(KEY_ENDPOINT, default) ?: default

    fun setEndpoint(context: Context, endpoint: String) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint).apply()
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
    const val KEY_VPN_WAS_RUNNING = "vpn_was_running"
}
