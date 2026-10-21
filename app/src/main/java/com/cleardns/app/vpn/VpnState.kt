package com.cleardns.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared in-memory state between the VPN service, the UI and the quick-settings tile.
 */
object VpnState {
    val isRunning = MutableStateFlow(false)
    val blockingEnabled = MutableStateFlow(true)
    val queriesTotal = MutableStateFlow(0L)
    val queriesBlocked = MutableStateFlow(0L)
    val adFreeUntil = MutableStateFlow(0L)

    fun resetCounters() {
        queriesTotal.value = 0L
        queriesBlocked.value = 0L
    }
}
