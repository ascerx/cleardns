package com.cleardns.app.vpn

/** One connection observed by the firewall (full-tunnel mode). */
data class ConnLogEntry(
    val cid: String,
    val uid: Int,
    val proto: String,        // "tcp" | "udp"
    val target: String,       // remote ip:port
    val domain: String,       // probable domain, may be ""
    val blocked: Boolean,
    val startedAt: Long,
    var rx: Long = 0L,
    var tx: Long = 0L,
    var durationMs: Long = 0L,
    var ended: Boolean = false,
)
