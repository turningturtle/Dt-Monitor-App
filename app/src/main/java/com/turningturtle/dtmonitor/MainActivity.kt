package com.turningturtle.dtmonitor

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var lastCheckText: TextView
    private lateinit var resultText: TextView
    private lateinit var webhookInput: EditText
    private lateinit var targetsInput: EditText
    private lateinit var recheckButton: Button
    private lateinit var editWebhookButton: Button
    private lateinit var saveWebhookButton: Button
    private lateinit var editTargetsButton: Button
    private lateinit var saveTargetsButton: Button
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        lastCheckText = findViewById(R.id.lastCheckText)
        resultText = findViewById(R.id.resultText)
        webhookInput = findViewById(R.id.webhookInput)
        targetsInput = findViewById(R.id.targetsInput)
        recheckButton = findViewById(R.id.recheckButton)
        editWebhookButton = findViewById(R.id.editWebhookButton)
        saveWebhookButton = findViewById(R.id.saveWebhookButton)
        editTargetsButton = findViewById(R.id.editTargetsButton)
        saveTargetsButton = findViewById(R.id.saveTargetsButton)

        loadSettings()
        setEditing(webhookInput, editWebhookButton, saveWebhookButton, false)
        setEditing(targetsInput, editTargetsButton, saveTargetsButton, false)
        Scheduler.schedule(this)

        editWebhookButton.setOnClickListener { setEditing(webhookInput, editWebhookButton, saveWebhookButton, true) }
        saveWebhookButton.setOnClickListener { saveWebhook() }
        editTargetsButton.setOnClickListener { setEditing(targetsInput, editTargetsButton, saveTargetsButton, true) }
        saveTargetsButton.setOnClickListener { saveTargets() }
        recheckButton.setOnClickListener { runManualCheck() }
        refreshLastState()
    }

    override fun onResume() {
        super.onResume()
        refreshLastState()
        Scheduler.schedule(this)
    }

    private fun loadSettings() {
        webhookInput.setText(MonitorPrefs.webhook(this))
        targetsInput.setText(MonitorPrefs.loadTargets(this).joinToString("\n") { "${it.name},${it.id}" })
    }

    private fun refreshLastState() {
        statusText.text = "● Monitoring"
        updateLastCheck()
        resultText.text = MonitorPrefs.lastResult(this)
    }

    private fun setEditing(input: EditText, edit: Button, save: Button, editing: Boolean) {
        input.isEnabled = editing
        edit.visibility = if (editing) View.GONE else View.VISIBLE
        save.visibility = if (editing) View.VISIBLE else View.GONE
    }

    private fun saveWebhook() {
        val webhook = webhookInput.text.toString().trim()
        if (webhook.isNotEmpty() && !webhook.startsWith("https://discord.com/api/webhooks/") && !webhook.startsWith("https://discordapp.com/api/webhooks/")) {
            resultText.text = "Webhook must be a Discord webhook URL."
            return
        }
        MonitorPrefs.saveWebhook(this, webhook)
        resultText.text = "Webhook saved."
        setEditing(webhookInput, editWebhookButton, saveWebhookButton, false)
    }

    private fun saveTargets() {
        val parsed = parseTargets(targetsInput.text.toString())
        if (parsed.error != null) { resultText.text = parsed.error; return }
        MonitorPrefs.saveTargets(this, parsed.targets)
        Scheduler.schedule(this)
        resultText.text = "Saved ${parsed.targets.size} server${if (parsed.targets.size == 1) "" else "s"}. Background monitoring is enabled."
        setEditing(targetsInput, editTargetsButton, saveTargetsButton, false)
    }

    private fun runManualCheck() {
        setBusy(true)
        executor.execute {
            val outcome = try { DtMonitor.check(applicationContext) } catch (e: Exception) {
                CheckOutcome(
                    System.currentTimeMillis(),
                    emptyList(),
                    MonitorPrefs.loadTargets(applicationContext).size,
                    e.message ?: "Check failed."
                )
            }
            MonitorPrefs.recordOutcome(applicationContext, outcome, "manual")
            Scheduler.scheduleNext(applicationContext)
            Scheduler.ensureNetworkWatcher(applicationContext)
            runOnUiThread { renderOutcome(outcome); setBusy(false) }
        }
    }

    private fun setBusy(busy: Boolean) {
        recheckButton.isEnabled = !busy
        editWebhookButton.isEnabled = !busy
        saveWebhookButton.isEnabled = !busy
        editTargetsButton.isEnabled = !busy
        saveTargetsButton.isEnabled = !busy
        statusText.text = if (busy) "● Checking…" else "● Monitoring"
    }

    private fun renderOutcome(outcome: CheckOutcome) {
        updateLastCheck()
        if (outcome.error != null) {
            statusText.text = "● Check needs attention"
            resultText.text = outcome.error
            return
        }
        statusText.text = "● Monitoring"
        if (outcome.active.isEmpty()) {
            resultText.text = "Checked ${outcome.targetCount} server${if (outcome.targetCount == 1) "" else "s"}.\nNo active DT found. No webhook sent."
            return
        }
        val lines = outcome.active.sortedBy { it.result.remainingMs }.joinToString("\n") { item ->
            val minutes = item.result.remainingMs / 60_000L
            "• ${item.target.name} — Shard ${item.result.shardId}, about ${minutes}m remaining"
        }
        resultText.text = "${outcome.active.size} active DT server${if (outcome.active.size == 1) "" else "s"}.\n$lines\n\nNew alerts sent: ${outcome.alertsSent}."
    }

    private fun updateLastCheck() {
        val time = MonitorPrefs.lastCheck(this)
        val source = MonitorPrefs.lastSource(this)
        lastCheckText.text = if (time == 0L) {
            "Last check: Never"
        } else {
            val suffix = if (source.isBlank()) "" else " • $source"
            "Last check: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))}$suffix"
        }
    }

    private fun parseTargets(text: String): ParseResult {
        val targets = mutableListOf<Target>()
        val seen = HashSet<String>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val comma = line.indexOf(',')
            if (comma <= 0 || comma == line.lastIndex) return ParseResult(emptyList(), "Line ${index + 1} must be: Server Name,Server ID")
            val name = line.substring(0, comma).trim()
            val id = line.substring(comma + 1).trim()
            if (name.isEmpty() || !id.matches(Regex("\\d{15,22}"))) return ParseResult(emptyList(), "Line ${index + 1} has an invalid server ID.")
            if (seen.add(id)) targets.add(Target(name, id))
        }
        return ParseResult(targets, null)
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private data class ParseResult(val targets: List<Target>, val error: String?)
}
