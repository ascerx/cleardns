package com.cleardns.app.vpn

/**
 * Minimal DNS wire-format helpers (RFC 1035) and IPv4/UDP packet building
 * for writing DNS responses back into the TUN interface.
 */
object DnsProtocol {

    /** Extracts the queried domain (QNAME of the first question) from a DNS query. */
    fun extractDomain(query: ByteArray): String? {
        if (query.size < 14) return null
        var offset = 12
        val sb = StringBuilder()
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) break
            if (offset + 1 + len > query.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, offset + 1, len, Charsets.US_ASCII))
            offset += 1 + len
        }
        val domain = sb.toString().lowercase()
        return if (domain.isEmpty()) null else domain
    }

    /**
     * Builds a minimal NXDOMAIN response from a query: echo header + question,
     * set QR=1 and RCODE=3, zero answer counts.
     */
    fun buildNxDomain(query: ByteArray): ByteArray? {
        if (query.size < 17) return null
        var offset = 12
        while (offset < query.size) {
            val len = query[offset].toInt() and 0xFF
            if (len == 0) break
            offset += 1 + len
        }
        offset += 5 // null label byte + QTYPE(2) + QCLASS(2)
        if (offset > query.size) return null
        val response = query.copyOf(offset)
        response[2] = (response[2].toInt() or 0x80).toByte() // QR = 1
        response[3] = ((response[3].toInt() and 0xF0) or 0x03).toByte() // RCODE = 3
        // ANCOUNT / NSCOUNT / ARCOUNT are already zero in queries.
        return response
    }

    private const val IP_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8
    private const val PROTO_UDP = 17

    /**
     * Wraps a DNS response payload into an IPv4 + UDP packet suitable for the TUN device.
     *
     * @param payload DNS response bytes
     * @param srcIp source IP as 4 bytes (our fake DNS server)
     * @param dstIp destination IP as 4 bytes (original query source)
     * @param dstPort destination port (original query source port)
     */
    fun buildUdpV4(payload: ByteArray, srcIp: ByteArray, dstIp: ByteArray, dstPort: Int): ByteArray {
        val total = IP_HEADER_LEN + UDP_HEADER_LEN + payload.size
        val out = ByteArray(total)

        // IPv4 header
        out[0] = 0x45 // version 4, IHL 5
        out[1] = 0 // DSCP/ECN
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        out[4] = 0; out[5] = 0 // identification
        out[6] = 0x40 // flags: don't fragment
        out[7] = 0
        out[8] = 64 // TTL
        out[9] = PROTO_UDP.toByte()
        out[10] = 0; out[11] = 0 // checksum placeholder
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        val cksum = checksum(out, IP_HEADER_LEN)
        out[10] = (cksum ushr 8).toByte()
        out[11] = cksum.toByte()

        // UDP header
        out[IP_HEADER_LEN] = 0
        out[IP_HEADER_LEN + 1] = 53 // source port 53
        out[IP_HEADER_LEN + 2] = (dstPort ushr 8).toByte()
        out[IP_HEADER_LEN + 3] = dstPort.toByte()
        val udpLen = UDP_HEADER_LEN + payload.size
        out[IP_HEADER_LEN + 4] = (udpLen ushr 8).toByte()
        out[IP_HEADER_LEN + 5] = udpLen.toByte()
        out[IP_HEADER_LEN + 6] = 0; out[IP_HEADER_LEN + 7] = 0 // UDP checksum 0 (allowed on IPv4)
        System.arraycopy(payload, 0, out, IP_HEADER_LEN + UDP_HEADER_LEN, payload.size)
        return out
    }

    /** Internet checksum over the first [length] bytes. */
    private fun checksum(data: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
