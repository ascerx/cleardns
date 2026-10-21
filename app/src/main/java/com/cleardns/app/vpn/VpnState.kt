package com.cleardns.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared in-memory state between the VPN service, the UI and the quick-settings tile.
 */
object VpnState {
    // ---- VPN lifecycle ----
    val isRunning = MutableStateFlow(false)
    /** "dns" = DNS-only (narrow route, fast); "firewall" = DNS + Firewall (full tunnel, firestack). */
    val mode = MutableStateFlow("dns")

    // ---- DNS filtering ----
    val blockingEnabled = MutableStateFlow(true)
    val queriesTotal = MutableStateFlow(0L)
    val queriesBlocked = MutableStateFlow(0L)
    val customBlocklist = MutableStateFlow(emptySet<String>())
    val whitelist = MutableStateFlow(emptySet<String>())
    val selectedLists = MutableStateFlow(emptySet<String>())
    val dohEndpoint = MutableStateFlow("")

    // ---- Firewall (full-tunnel mode) ----
    /** Per-uid app rules: "bypass" | "block". Absent = filtered (default). */
    val appRules = MutableStateFlow(emptyMap<Int, String>())
    /** UIDs excluded from the VPN entirely (legacy key kept for bypass UI). */
    val skippedUids = MutableStateFlow(emptySet<Int>())

    // ---- Network monitor (full-tunnel mode) ----
    val connLog = MutableStateFlow(emptyList<ConnLogEntry>())
    /** uid -> [connections, blockedConnections, rx, tx] */
    val appStats = MutableStateFlow(emptyMap<Int, LongArray>())

    // ---- Ads ----
    val adFreeUntil = MutableStateFlow(0L)

    fun resetCounters() {
        queriesTotal.value = 0L
        queriesBlocked.value = 0L
    }

    fun clearMonitor() {
        connLog.value = emptyList()
        appStats.value = emptyMap()
    }
}
