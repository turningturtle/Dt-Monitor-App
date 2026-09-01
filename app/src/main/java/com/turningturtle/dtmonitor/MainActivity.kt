package com.turningturtle.dtmonitor

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var lastCheckText: TextView
    private lateinit var resultText: TextView
    private lateinit var webhookInput: EditText
    private lateinit var targetsInput: EditText
    private lateinit var recheckButton: Button
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

        webhookInput.setText(MonitorPrefs.webhook(this))
        targetsInput.setText(MonitorPrefs.loadTargets(this).joinToString("\n") { "${it.name},${it.id}" })
        updateLastCheck()
        Scheduler.schedule(this)

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        recheckButton.setOnClickListener { runManualCheck() }
    }

    private fun saveSettings() {
        val parsed = parseTargets(targetsInput.text.toString())
        if (parsed.error != null) {
            resultText.text = parsed.error
            return
        }
        MonitorPrefs.saveTargets(this, parsed.targets)
        MonitorPrefs.saveWebhook(this, webhookInput.text.toString())
        Scheduler.schedule(this)
        resultText.text = "Saved ${parsed.targets.size} server${if (parsed.targets.size == 1) "" else "s"}. Background monitoring is enabled."
        statusText.text = "● Monitoring"
    }

    private fun runManualCheck() {
        val parsed = parseTargets(targetsInput.text.toString())
        if (parsed.error != null) { resultText.text = parsed.error; return }
        MonitorPrefs.saveTargets(this, parsed.targets)
        MonitorPrefs.saveWebhook(this, webhookInput.text.toString())
        setBusy(true)
        executor.execute {
            val outcome = try { DtMonitor.check(applicationContext) } catch (e: Exception) {
                CheckOutcome(System.currentTimeMillis(), emptyList(), parsed.targets.size, e.message ?: "Check failed.")
            }
            runOnUiThread { renderOutcome(outcome); setBusy(false) }
        }
    }

    private fun setBusy(busy: Boolean) {
        recheckButton.isEnabled = !busy
        statusText.text = if (busy) "● Checking…" else "● Monitoring"
    }

    private fun renderOutcome(outcome: CheckOutcome) {
        MonitorPrefs.setLastCheck(this, outcome.checkedAt)
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
        lastCheckText.text = if (time == 0L) "Last check: Never" else "Last check: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))}"
    }

    private fun parseTargets(text: String): ParseResult {
        val targets = mutableListOf<Target>()
        val seen = HashSet<String>()
        text.lines().mapIndexed { index, line -> index + 1 to line.trim() }.forEach { (lineNumber, line) ->
            if (line.isEmpty()) return@forEach
            val comma = line.indexOf(',')
            if (comma <= 0 || comma == line.lastIndex) return ParseResult(emptyList(), "Line $lineNumber must be: Server Name,Server ID")
            val name = line.substring(0, comma).trim()
            val id = line.substring(comma + 1).trim()
            if (name.isEmpty() || !id.matches(Regex("\\d{15,22}"))) return ParseResult(emptyList(), "Line $lineNumber has an invalid server ID.")
            if (seen.add(id)) targets.add(Target(name, id))
        }
        return ParseResult(targets, null)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class ParseResult(val targets: List<Target>, val error: String?)
}
