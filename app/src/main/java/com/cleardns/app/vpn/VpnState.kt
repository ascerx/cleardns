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

    /** Set when the user explicitly asked to stop; blocks START_STICKY redelivery. */
    @Volatile
    var userStopRequested: Boolean = false

    // ---- DNS filtering ----
    val blockingEnabled = MutableStateFlow(true)
    val queriesTotal = MutableStateFlow(0L)
    val queriesBlocked = MutableStateFlow(0L)
    val customBlocklist = MutableStateFlow(emptySet<String>())
    val whitelist = MutableStateFlow(emptySet<String>())
    val selectedLists = MutableStateFlow(emptySet<String>())
    val dohEndpoint = MutableStateFlow("")

    // ---- Firewall (full-tunnel mode) ----
    /** Per-uid app rules: "bypass" | "block" | "block_wifi" | "block_mobile" | "isolate". Absent = filtered (default). */
    val appRules = MutableStateFlow(emptyMap<Int, String>())
    /** Per-uid allowed domains in isolate mode. */
    val isolateDomains = MutableStateFlow(emptyMap<Int, Set<String>>())
    /** Safety switches (unsafe traffic blocking). */
    val safetyQuic = MutableStateFlow(false)
    val safetyHttp = MutableStateFlow(false)
    val safetyPrivate = MutableStateFlow(false)
    /** UIDs excluded from the VPN entirely (legacy key kept for bypass UI). */
    val skippedUids = MutableStateFlow(emptySet<Int>())

    // ---- Network monitor (full-tunnel mode) ----
    val connLog = MutableStateFlow(emptyList<ConnLogEntry>())
    val dnsLog = MutableStateFlow(emptyList<DnsLogEntry>())
    /** User IP rules: exact IP or CIDR strings to block. */
    val ipRules = MutableStateFlow(emptySet<String>())
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
        dnsLog.value = emptyList()
        appStats.value = emptyMap()
    }
}
