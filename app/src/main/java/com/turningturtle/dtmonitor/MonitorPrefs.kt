package com.turningturtle.dtmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MonitorPrefs {
    private const val FILE = "dt_monitor"
    private const val TARGETS = "targets"
    private const val LAST_CHECK = "last_check"
    private const val LAST_RESULT = "last_result"
    private const val LAST_SOURCE = "last_source"
    private const val ALERTS = "alerts"
    private const val BACKGROUND_CHECK_COOLDOWN_MS = 90_000L

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun loadTargets(context: Context): List<Target> {
        val raw = prefs(context).getString(TARGETS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val id = item.optString("id").trim()
                    if (name.isNotEmpty() && id.matches(Regex("\\d{15,22}"))) add(Target(name, id))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveTargets(context: Context, targets: List<Target>) {
        val array = JSONArray()
        targets.forEach { target -> array.put(JSONObject().apply { put("name", target.name); put("id", target.id) }) }
        prefs(context).edit().putString(TARGETS, array.toString()).apply()
    }

    fun webhook(context: Context): String = SecretStore.get(context)
    fun saveWebhook(context: Context, value: String) = SecretStore.put(context, value.trim())

    fun setLastCheck(context: Context, millis: Long) =
        prefs(context).edit().putLong(LAST_CHECK, millis).apply()

    fun lastCheck(context: Context): Long = prefs(context).getLong(LAST_CHECK, 0L)
    fun lastResult(context: Context): String = prefs(context).getString(LAST_RESULT, "No check performed yet.") ?: "No check performed yet."
    fun lastSource(context: Context): String = prefs(context).getString(LAST_SOURCE, "") ?: ""

    /** Atomically prevents two background triggers from checking at the same time. */
    @Synchronized fun tryAcquireBackgroundCheck(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastCheck(context)
        if (previous != 0L && now - previous < BACKGROUND_CHECK_COOLDOWN_MS) return false
        setLastCheck(context, now)
        return true
    }

    fun recordOutcome(context: Context, outcome: CheckOutcome, source: String) {
        val summary = when {
            outcome.error != null -> outcome.error
            outcome.active.isEmpty() ->
                "Checked ${outcome.targetCount} server${if (outcome.targetCount == 1) "" else "s"}.\nNo active DT found. No webhook sent."
            else ->
                "${outcome.active.size} active DT server${if (outcome.active.size == 1) "" else "s"}.\nNew alerts sent: ${outcome.alertsSent}."
        }
        prefs(context).edit()
            .putLong(LAST_CHECK, outcome.checkedAt)
            .putString(LAST_RESULT, summary)
            .putString(LAST_SOURCE, source)
            .apply()
    }

    @Synchronized fun wasAlerted(context: Context, targetId: String, eventKey: String): Boolean {
        val raw = prefs(context).getString(ALERTS, "{}") ?: "{}"
        return try { JSONObject(raw).optString(targetId, "") == eventKey } catch (_: Exception) { false }
    }

    @Synchronized fun markAlerted(context: Context, eventKeyById: Map<String, String>) {
        val p = prefs(context)
        val raw = p.getString(ALERTS, "{}") ?: "{}"
        val alerts = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        eventKeyById.forEach { (id, event) -> alerts.put(id, event) }
        p.edit().putString(ALERTS, alerts.toString()).apply()
    }
}
