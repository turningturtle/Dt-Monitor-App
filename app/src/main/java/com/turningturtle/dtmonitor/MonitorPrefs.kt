package com.turningturtle.dtmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MonitorPrefs {
    private const val FILE = "dt_monitor"
    private const val TARGETS = "targets"
    private const val LAST_CHECK = "last_check"
    private const val ALERTS = "alerts"

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
        targets.forEach { target ->
            array.put(JSONObject().apply { put("name", target.name); put("id", target.id) })
        }
        prefs(context).edit().putString(TARGETS, array.toString()).apply()
    }

    fun webhook(context: Context): String = SecretStore.get(context)

    fun saveWebhook(context: Context, value: String) = SecretStore.put(context, value.trim())

    fun setLastCheck(context: Context, millis: Long) = prefs(context).edit().putLong(LAST_CHECK, millis).apply()

    fun lastCheck(context: Context): Long = prefs(context).getLong(LAST_CHECK, 0L)

    /** Returns true only if this target has not already been alerted for this exact shard restart. */
    @Synchronized fun claimAlert(context: Context, targetId: String, eventKey: String): Boolean {
        val p = prefs(context)
        val raw = p.getString(ALERTS, "{}") ?: "{}"
        val alerts = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        val previous = alerts.optString(targetId, "")
        if (previous == eventKey) return false
        alerts.put(targetId, eventKey)
        p.edit().putString(ALERTS, alerts.toString()).apply()
        return true
    }
}
