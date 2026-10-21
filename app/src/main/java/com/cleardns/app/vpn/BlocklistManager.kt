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
 * Downloads, merges and applies host-format blocklists.
 */
object BlocklistManager {

    data class BlockList(val id: String, val label: String, val default: Boolean, val url: String)

    val AVAILABLE: List<BlockList> = listOf(
        BlockList("oisd_small", "OISD Small — ads & trackers", true, "https://small.oisd.nl/hosts"),
        BlockList("oisd_big", "OISD Big — aggressive", false, "https://big.oisd.nl/hosts"),
        BlockList("stevenblack", "StevenBlack Unified (ads + malware)", false, "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        BlockList("hagezi_mini", "Hagezi Mini — light", false, "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/mini.txt"),
    )

    private val domains = HashSet<String>(1 shl 16)
    private val lock = Any()

    val isRefreshing = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)

    private const val FILE_NAME = "blocklist.txt"
    private const val SEED_ASSET = "blocklist.txt"

    /** Loads the merged blocklist from storage (or the bundled seed on first run). */
    fun load(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val source = if (file.exists()) file.inputStream() else context.assets.open(SEED_ASSET)
        val parsed = HashSet<String>(1 shl 16)
        source.use { stream ->
            stream.bufferedReader().forEachLine { line ->
                parseLine(line)?.let { parsed.add(it) }
            }
        }
        synchronized(lock) {
            domains.clear()
            domains.addAll(parsed)
        }
    }

    /** Checks a domain and its first two parent domains against the blocklist.
     *
     *  Order of evaluation:
     *  1. Whitelist (custom "allow" domains) always wins → not blocked.
     *  2. Custom user-blocked domains can block anything.
     *  3. Merged downloaded blocklists. */
    fun isBlocked(domain: String): Boolean {
        var candidate: String = domain.lowercase()
        val whitelist = VpnState.whitelist.value
        val custom = VpnState.customBlocklist.value
        repeat(3) {
            if (candidate.isEmpty()) return false
            if (candidate in whitelist) return false
            if (candidate in custom) return true
            if (synchronized(lock) { candidate in domains }) return true
            val dot = candidate.indexOf('.')
            if (dot == -1) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    fun size(): Int = synchronized(lock) { domains.size }

    /**
     * Downloads the selected lists, merges them (deduplicated) into one file
     * and reloads the in-memory set.
     */
    suspend fun downloadAndMerge(context: Context, ids: Set<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            isRefreshing.value = true
            lastError.value = null
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val merged = HashSet<String>(1 shl 17)
                for (list in AVAILABLE.filter { it.id in ids }) {
                    progress.value = "Downloading ${list.label}…"
                    val body = client.newCall(Request.Builder().url(list.url).build())
                        .execute().use { response ->
                            if (!response.isSuccessful) throw IllegalStateException("${list.id}: HTTP ${response.code}")
                            response.body?.string() ?: throw IllegalStateException("${list.id}: empty")
                        }
                    body.lines().forEach { line ->
                        parseLine(line)?.let { merged.add(it) }
                    }
                }
                if (merged.size < 100) throw IllegalStateException("Suspiciously small result (${merged.size} entries)")
                progress.value = "Saving…"
                File(context.filesDir, FILE_NAME).writeText(merged.joinToString("\n") { "0.0.0.0 $it" })
                load(context)
                progress.value = ""
                Result.success(size())
            } catch (e: Exception) {
                lastError.value = e.message
                progress.value = ""
                Result.failure(e)
            } finally {
                isRefreshing.value = false
            }
        }

    private fun parseLine(line: String): String? {
        val trimmed = line.substringBefore('#').trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
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
