package com.cleardns.app.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads, stores and refreshes the domain blocklist (hosts-file format).
 */
object BlocklistManager {

    private val domains = HashSet<String>(1 shl 16)
    private val lock = Any()

    val isRefreshing = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    private const val DEFAULT_URL = "https://small.oisd.nl/hosts"
    private const val FILE_NAME = "blocklist.txt"
    private const val SEED_ASSET = "blocklist.txt"

    /** Loads the blocklist from storage (or the bundled seed on first run). */
    fun load(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val source = if (file.exists()) file.inputStream() else context.assets.open(SEED_ASSET)
        val parsed = HashSet<String>(1 shl 16)
        source.use { stream ->
            stream.bufferedReader().forEachLine { line ->
                val domain = parseLine(line) ?: return@forEachLine
                parsed.add(domain)
            }
        }
        synchronized(lock) {
            domains.clear()
            domains.addAll(parsed)
        }
    }

    /** Checks a domain and its first two parent domains against the blocklist. */
    fun isBlocked(domain: String): Boolean {
        var candidate: String = domain.lowercase()
        repeat(3) {
            if (candidate.isEmpty()) return false
            if (synchronized(lock) { candidate in domains }) return true
            val dot = candidate.indexOf('.')
            if (dot == -1) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    fun size(): Int = synchronized(lock) { domains.size }

    /** Downloads a fresh blocklist and reloads it. */
    suspend fun refresh(context: Context, url: String = DEFAULT_URL): Result<Int> =
        withContext(Dispatchers.IO) {
            isRefreshing.value = true
            lastError.value = null
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    response.body?.string() ?: throw IllegalStateException("Empty response")
                }
                val parsed = body.lines().mapNotNull { parseLine(it) }.filter { it.length >= 4 }
                if (parsed.size < 100) throw IllegalStateException("Suspiciously small list (${parsed.size} entries)")
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(body)
                load(context)
                Result.success(size())
            } catch (e: Exception) {
                lastError.value = e.message
                Result.failure(e)
            } finally {
                isRefreshing.value = false
            }
        }

    private fun parseLine(line: String): String? {
        val trimmed = line.substringBefore('#').trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        // "0.0.0.0 ads.example.com" (hosts format) or plain domain format.
        return when (parts.size) {
            1 -> parts[0].takeIf { isDomainLike(it) }
            2 -> parts[1].takeIf { isDomainLike(it) }
            else -> null
        }
    }

    private fun isDomainLike(s: String): Boolean =
        s.contains('.') &&
            s.none { !it.isLetterOrDigit() && it != '.' && it != '-' } &&
            s.first().isLetter()
}
