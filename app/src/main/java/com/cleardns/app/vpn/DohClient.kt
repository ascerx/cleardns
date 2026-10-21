package com.cleardns.app.vpn

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Minimal DNS-over-HTTPS (RFC 8484) client.
 */
class DohClient(endpoint: String = DEFAULT_ENDPOINT) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val requestUrl = endpoint

    /** Resolves a raw DNS query via DoH. Returns the wire-format response or null. */
    fun resolve(query: ByteArray): ByteArray? {
        val b64 = Base64.encodeToString(query, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val request = Request.Builder()
            .url("$requestUrl?dns=$b64")
            .header("Accept", "application/dns-message")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://dns.google/resolve"
        const val CLOUDFLARE_ENDPOINT = "https://cloudflare-dns.com/dns-query"
    }
}
