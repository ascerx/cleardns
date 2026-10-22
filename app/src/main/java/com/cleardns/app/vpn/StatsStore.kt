package com.cleardns.app.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Aggregated traffic history, persisted to a JSON file:
 *  - hourly buckets (last 48h): hourKey "yyyyMMddHH" -> uid -> [conns, blocked, rx, tx]
 *  - daily buckets (last 30d):  "yyyy-MM-dd"    -> uid -> [conns, blocked, rx, tx]
 *
 * Written in batches (at most every 20s) to avoid excessive disk churn.
 */
object StatsStore {

    private var hours = JSONObject()
    private var days = JSONObject()
    private var lastWrite = 0L
    private var loaded = false
    private var file: File? = null
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hourFmt = SimpleDateFormat("yyyyMMddHH", Locale.US)

    fun load(context: Context) {
        synchronized(this) {
            if (loaded) return
            file = File(context.filesDir, "stats.json")
            try {
                val text = file?.takeIf { it.exists() }?.readText()
                if (text != null) {
                    val root = JSONObject(text)
                    hours = root.optJSONObject("h") ?: JSONObject()
                    days = root.optJSONObject("d") ?: JSONObject()
                }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    fun recordConn(uid: Int, blocked: Boolean, at: Long = System.currentTimeMillis()) =
        record(uid, if (blocked) 1L else 0L, blocked, 0L, 0L, at)

    fun recordBytes(uid: Int, rx: Long, tx: Long, at: Long = System.currentTimeMillis()) {
        if (rx <= 0 && tx <= 0) return
        record(uid, 0L, false, rx, tx, at)
    }

    private fun record(uid: Int, extraConns: Long, wasBlocked: Boolean, rx: Long, tx: Long, at: Long) {
        synchronized(this) {
            if (!loaded) return
            val hourKey = hourFmt.format(at)
            val dayKey = dayFmt.format(at)
            for ((bucket, key) in listOf(hours to hourKey, days to dayKey)) {
                val perApp = bucket.optJSONObject(key) ?: JSONObject().also { bucket.put(key, it) }
                val entry = perApp.optJSONArray(uid.toString())
                if (entry == null) {
                    val arr = org.json.JSONArray()
                    arr.put(extraConns)
                    arr.put(if (wasBlocked) extraConns else 0L)
                    arr.put(rx)
                    arr.put(tx)
                    perApp.put(uid.toString(), arr)
                } else {
                    entry.put(0, entry.optLong(0) + extraConns)
                    if (wasBlocked) entry.put(1, entry.optLong(1) + extraConns)
                    entry.put(2, entry.optLong(2) + rx)
                    entry.put(3, entry.optLong(3) + tx)
                }
            }
            trimOld()
            if (at - lastWrite > 20_000) persist()
        }
    }

    fun persist() {
        synchronized(this) {
            try {
                val root = JSONObject().put("h", hours).put("d", days)
                file?.writeText(root.toString())
                lastWrite = System.currentTimeMillis()
            } catch (_: Exception) {
            }
        }
    }

    private fun trimOld() {
        val now = System.currentTimeMillis()
        val cutoffHour = hourFmt.format(now - TimeUnit24H)
        val cutoffDay = dayFmt.format(now - TimeUnit30D)
        try {
            hours.keys().asSequence().toList().forEach { if (it < cutoffHour) hours.remove(it) }
            days.keys().asSequence().toList().forEach { if (it < cutoffDay) days.remove(it) }
        } catch (_: Exception) {
        }
    }

    private val TimeUnit24H = 48L * 60 * 60 * 1000
    private val TimeUnit30D = 32L * 24 * 60 * 60 * 1000

    /** Aggregated per-hour stats for a given day (24 buckets of [conns, blocked, rx, tx]). */
    fun hourlyForDay(at: Long = System.currentTimeMillis()): List<LongArray> {
        synchronized(this) {
            val out = List(24) { LongArray(4) }
            val dayKey = dayFmt.format(at)
            hours.keys().asSequence().filter { it.startsWith(dayKey.take(8)) }.forEach { hourKey ->
                val perApp = hours.optJSONObject(hourKey) ?: return@forEach
                val hourIndex = hourKey.takeLast(2).toIntOrNull()?.coerceIn(0, 23) ?: return@forEach
                perApp.keys().asSequence().forEach { uidKey ->
                    val arr = perApp.optJSONArray(uidKey) ?: return@forEach
                    out[hourIndex][0] += arr.optLong(0)
                    out[hourIndex][1] += arr.optLong(1)
                    out[hourIndex][2] += arr.optLong(2)
                    out[hourIndex][3] += arr.optLong(3)
                }
            }
            return out
        }
    }

    /** Per-day totals (conns, blocked, rx, tx) for the last 7 days. */
    fun dailyLast7(at: Long = System.currentTimeMillis()): List<Pair<String, LongArray>> {
        synchronized(this) {
            val out = mutableListOf<Pair<String, LongArray>>()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            for (i in 0..6) {
                val day = dayFmt.format(at - i * 86_400_000L)
                val arr = LongArray(4)
                days.optJSONObject(day)?.let { perApp ->
                    perApp.keys().asSequence().forEach { uidKey ->
                        val e = perApp.optJSONArray(uidKey) ?: return@forEach
                        arr[0] += e.optLong(0)
                        arr[1] += e.optLong(1)
                        arr[2] += e.optLong(2)
                        arr[3] += e.optLong(3)
                    }
                }
                out.add(day to arr)
            }
            return out
        }
    }

    /** Top apps today by connections+bytes: uid -> [conns, blocked, rx, tx]. */
    fun topAppsToday(at: Long = System.currentTimeMillis()): Map<Int, LongArray> {
        synchronized(this) {
            val dayKey = dayFmt.format(at)
            val result = HashMap<Int, LongArray>()
            days.optJSONObject(dayKey)?.let { perApp ->
                perApp.keys().asSequence().forEach { uidKey ->
                    val uid = uidKey.toIntOrNull() ?: return@forEach
                    val e = perApp.optJSONArray(uidKey) ?: return@forEach
                    val arr = longArrayOf(e.optLong(0), e.optLong(1), e.optLong(2), e.optLong(3))
                    result[uid] = arr
                }
            }
            return result
        }
    }
}
