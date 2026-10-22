package com.cleardns.app.vpn

import java.net.InetAddress

/**
 * IP blocking rules: exact IP or CIDR notation (both IPv4 and IPv6).
 */
object IpRules {

    fun matches(ip: String, rules: Set<String>): Boolean {
        if (rules.isEmpty()) return false
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        for (rule in rules) {
            val trimmed = rule.trim()
            if (trimmed.isEmpty()) continue
            if (matchRule(bytes, trimmed)) return true
        }
        return false
    }

    /** True for private/loopback/link-local/multicast addresses (RFC 1918 & co). */
    fun isPrivate(ip: String): Boolean {
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        return bytes[0].toInt().let { first ->
            (first == 10) ||
                (first == 127) ||
                (first == 172 && bytes.size == 4 && bytes[1].toInt() in 16..31) ||
                (first == 192 && bytes.size == 4 && bytes[1].toInt() == 168) ||
                (first == 169 && bytes.size == 4 && bytes[1].toInt() == 254) ||
                (first in 224..239) // multicast
        }
    }

    private fun matchRule(bytes: ByteArray, rule: String): Boolean {
        if (rule.contains('/')) {
            val addrPart = rule.substringBefore('/')
            val prefix = rule.substringAfter('/').toIntOrNull() ?: return false
            val ruleBytes = runCatching { InetAddress.getByName(addrPart).address }.getOrNull() ?: return false
            if (ruleBytes.size != bytes.size) return false
            val fullBytes = prefix / 8
            val remBits = prefix % 8
            for (i in 0 until fullBytes) {
                if (bytes[i] != ruleBytes[i]) return false
            }
            if (remBits > 0 && fullBytes < bytes.size) {
                val mask = ((0xFF shl (8 - remBits)) and 0xFF).toByte()
                if ((bytes[fullBytes].toInt() and mask.toInt()) !=
                    (ruleBytes[fullBytes].toInt() and mask.toInt())
                ) return false
            }
            return true
        }
        val ruleBytes = runCatching { InetAddress.getByName(rule).address }.getOrNull() ?: return false
        return ruleBytes.contentEquals(bytes)
    }
}
