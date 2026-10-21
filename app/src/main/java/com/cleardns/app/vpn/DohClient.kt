package com.cleardns.app.vpn

import android.util.Base64
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Minimal DNS-over-HTTPS (RFC 8484) client.
 *
 * Two critical pieces for running inside our own VPN:
 *  1. A [SocketFactory] that calls [protect] (VpnService.protect) so our own
 *     DoH traffic bypasses the VPN and can't loop back into the TUN.
 *  2. A fixed [Dns] mapping (no hostname resolution) so we never need a DNS
 *     lookup to reach the DoH server — that lookup would go through the VPN
 *     itself and deadlock.
 */
class DohClient(
    endpoint: String = DEFAULT_ENDPOINT,
    private val protect: (Socket) -> Boolean = { true },
) {

    private val fixedDns: List<InetAddress> = when {
        endpoint.contains("cloudflare") -> listOf(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)),
        )
        else -> listOf(
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
            InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4)),
        )
    }

    private val endpoints = if (endpoint.contains("cloudflare")) {
        listOf(endpoint, DEFAULT_ENDPOINT)
    } else {
        listOf(endpoint, CLOUDFLARE_ENDPOINT)
    }

    private val client = OkHttpClient.Builder()
        .socketFactory(object : SocketFactory() {
            private val delegate = SocketFactory.getDefault()
            override fun createSocket(): Socket = delegate.createSocket().apply { protect(this) }
            override fun createSocket(host: String, port: Int): Socket =
                delegate.createSocket(host, port).apply { protect(this) }
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).apply { protect(this) }
            override fun createSocket(host: InetAddress, port: Int): Socket =
                delegate.createSocket(host, port).apply { protect(this) }
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).apply { protect(this) }
        })
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = fixedDns
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val requestUrl = endpoint

    /** Resolves a raw DNS query via DoH. Tries the primary endpoint, then the fallback. */
    fun resolve(query: ByteArray): ByteArray? {
        val b64 = Base64.encodeToString(query, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        for (endpoint in endpoints) {
            val request = Request.Builder()
                .url("$endpoint?dns=$b64")
                .header("Accept", "application/dns-message")
                .build()
            val result = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w(TAG, "DoH HTTP ${response.code} ($endpoint)")
                        null
                    } else {
                        response.body?.bytes()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "DoH error: ${e.javaClass.simpleName}: ${e.message} ($endpoint)")
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "DohClient"
        const val DEFAULT_ENDPOINT = "https://dns.google/dns-query"
        const val CLOUDFLARE_ENDPOINT = "https://cloudflare-dns.com/dns-query"
    }
}
