package com.cleardns.app.vpn

/** One DNS transaction observed by the engine (firewall mode). */
data class DnsLogEntry(
    val id: String,
    val uid: Int,
    val domain: String,
    val rcode: Int,
    val cached: Boolean,
    val latencyMs: Long,
    val timestamp: Long,
)
