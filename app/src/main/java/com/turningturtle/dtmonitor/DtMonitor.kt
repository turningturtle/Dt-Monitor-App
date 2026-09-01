package com.turningturtle.dtmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

object DtMonitor {
    private const val STATUS_URL = "https://owobot.com/api/status"
    private const val DT_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val SHARD_STALE_MS = 150L * 1000L
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 10_000
    private val digits = Regex("\\d{15,22}")
    private val lock = Any()

    fun check(context: Context): CheckOutcome = synchronized(lock) {
        val started = System.currentTimeMillis()
        val targets = MonitorPrefs.loadTargets(context)
        if (targets.isEmpty()) return@synchronized CheckOutcome(started, emptyList(), 0, "No servers configured.")
        if (MonitorPrefs.webhook(context).isBlank()) return@synchronized CheckOutcome(started, emptyList(), targets.size, "Add a Discord webhook before checking.")

        val status = fetchStatus()
        val active = targets.mapNotNull { target ->
            val result = guildStatus(target.id, status, started)
            if (result.state == DtResult.State.ACTIVE) ActiveTarget(target, result) else null
        }

        val newAlerts = active.filterNot { item ->
            val event = eventKey(item.result)
            MonitorPrefs.wasAlerted(context, item.target.id, event)
        }

        if (newAlerts.isNotEmpty()) {
            postWebhook(MonitorPrefs.webhook(context), newAlerts, started)
            MonitorPrefs.markAlerted(context, newAlerts.associate { it.target.id to eventKey(it.result) })
        }

        CheckOutcome(started, active, targets.size, null, newAlerts.size)
    }

    private fun eventKey(result: DtResult): String = "${result.shardId}:${result.startedAt}"

    private fun fetchStatus(): JSONArray {
        val connection = (URL(STATUS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            useCaches = false
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw MonitorException("OwO status API returned HTTP $code.")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONArray(body)
        } finally { connection.disconnect() }
    }

    private fun owoServers(status: JSONArray): List<JSONObject> = buildList {
        for (i in 0 until status.length()) {
            val item = status.optJSONObject(i) ?: continue
            if (item.optString("type") == "owo" && item.has("min") && item.has("max")) add(item)
        }
    }

    private fun totalShards(status: JSONArray): Int = owoServers(status).maxOfOrNull { it.optInt("max", -1) + 1 } ?: 0

    private fun shardId(guildId: String, total: Int): Int? {
        if (total <= 0 || !digits.matches(guildId)) return null
        return try { BigInteger(guildId).shiftRight(22).mod(BigInteger.valueOf(total.toLong())).toInt() } catch (_: Exception) { null }
    }

    private fun shardRecord(status: JSONArray, id: Int): JSONObject? {
        for (server in owoServers(status)) {
            val min = server.optInt("min", Int.MAX_VALUE)
            val max = server.optInt("max", Int.MIN_VALUE)
            if (id !in min..max) continue
            val shards = server.optJSONArray("shards") ?: continue
            for (i in 0 until shards.length()) {
                val shard = shards.optJSONObject(i) ?: continue
                if (shard.optString("shard") == id.toString() || shard.optString("id") == id.toString()) return shard.put("__serverName", server.optString("name", "OwO"))
            }
        }
        return null
    }

    private fun parseTime(value: Any?): Long? {
        if (value is Number) return value.toLong().takeIf { it >= 0L }
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.matches(Regex("\\d+(\\.\\d+)?"))) return text.toDoubleOrNull()?.toLong()
        return try { Instant.parse(text).toEpochMilli() } catch (_: DateTimeParseException) { null }
    }

    private fun guildStatus(guildId: String, status: JSONArray, now: Long): DtResult {
        val total = totalShards(status)
        val id = shardId(guildId, total) ?: return DtResult(DtResult.State.UNAVAILABLE, reason = "Invalid Discord server ID.")
        val shard = shardRecord(status, id) ?: return DtResult(DtResult.State.UNAVAILABLE, id, reason = "OwO has no data for shard $id.")
        val updated = parseTime(shard.opt("updatedOn")) ?: return DtResult(DtResult.State.UNAVAILABLE, id, reason = "OwO shard timestamp is invalid.")
        if (now - updated > SHARD_STALE_MS) return DtResult(DtResult.State.UNAVAILABLE, id, reason = "OwO shard data is stale.")
        val rawStart = shard.opt("start")
        val start = parseTime(rawStart) ?: return DtResult(DtResult.State.UNAVAILABLE, id, reason = "OwO shard start data is invalid.")
        val numeric = rawStart is Number || rawStart is String && rawStart.trim().matches(Regex("\\d+(\\.\\d+)?"))
        val age = if (numeric) maxOf(0L, start + maxOf(0L, now - updated)) else maxOf(0L, now - start)
        val startedAt = now - age
        if (age >= DT_WINDOW_MS) return DtResult(DtResult.State.INACTIVE, id, startedAt, 0L)
        return DtResult(DtResult.State.ACTIVE, id, startedAt, DT_WINDOW_MS - age)
    }

    private fun postWebhook(webhook: String, active: List<ActiveTarget>, now: Long) {
        val root = JSONObject()
        val description = buildString {
            active.sortedBy { it.result.remainingMs }.take(25).forEach { item ->
                val result = item.result
                val expires = (now + result.remainingMs) / 1000L
                append("• **${escapeMarkdown(item.target.name).take(80)}** — Shard ${result.shardId} • restarted <t:${result.startedAt!! / 1000}:R> • expires <t:$expires:R>\n")
            }
            if (active.size > 25) append("• …and ${active.size - 25} more.\n")
        }.trimEnd()
        val embed = JSONObject().apply {
            put("title", "🎴 OwO Distorted Time Available")
            put("description", description)
            put("footer", JSONObject().put("text", "Found ${active.size} new DT server${if (active.size == 1) "" else "s"}."))
            put("timestamp", Instant.ofEpochMilli(now).toString())
        }
        root.put("embeds", JSONArray().put(embed))

        val connection = (URL(webhook).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "DT-Monitor/1.0")
        }
        try {
            connection.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw MonitorException("Discord webhook returned HTTP $code.")
        } finally { connection.disconnect() }
    }

    private fun escapeMarkdown(text: String): String = text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
}

data class CheckOutcome(
    val checkedAt: Long,
    val active: List<ActiveTarget>,
    val targetCount: Int,
    val error: String? = null,
    val alertsSent: Int = 0
)

class MonitorException(message: String) : Exception(message)
