package dev.curiobot

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.curiobot.log.Prefs
import dev.curiobot.log.Telemetry
import org.json.JSONObject

/** Plain dev panel: status, toggles, the last turn, and recent log lines. */
class DevActivity : ComponentActivity() {
    private val game by lazy { (application as CuriobotApp).game }
    private lateinit var status: TextView
    private lateinit var detail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(24))
            setBackgroundColor(0xFF15141A.toInt())
        }
        status = mono(12f)
        col.addView(status)
        fun button(text: String, action: () -> Unit) = col.addView(Button(this).apply {
            this.text = text; isAllCaps = false; setOnClickListener { action(); refresh() }
        })
        button("Send logs now") { Telemetry.flushSoon() }
        button("Log upload on/off") { Prefs.uploadLogs = !Prefs.uploadLogs; Telemetry.log("pref_upload_logs", JSONObject().put("on", Prefs.uploadLogs)) }
        button("Test pace on/off (hours become minutes)") { Prefs.testPace = !Prefs.testPace; Telemetry.log("pref_test_pace", JSONObject().put("on", Prefs.testPace)) }
        button("Text model: auto / gpu / cpu (next launch)") {
            Prefs.backend = when (Prefs.backend) { "auto" -> "gpu"; "gpu" -> "cpu"; else -> "auto" }
            if (Prefs.backend == "gpu") Prefs.gpuBroken = false
            Telemetry.log("pref_backend", JSONObject().put("backend", Prefs.backend))
        }
        button("Run self-test (bundled photos)") { selfTest() }
        button("View its files (journal, nest, memory)") {
            detail.text = game.filesText()
        }
        button("Show last turn") {
            detail.text = game.lastTurn?.toString(2) ?: "No turn yet."
        }
        button("Show recent log lines") {
            detail.text = Telemetry.recentLines().takeLast(120).reversed().joinToString("\n")
        }
        button("New creature (forgets the current one)") {
            AlertDialog.Builder(this).setTitle("Start over?")
                .setMessage("The current creature and everything it has seen will be forgotten.")
                .setPositiveButton("Start over") { _, _ -> game.resetCreature(); finish() }
                .setNegativeButton("Cancel", null).show()
        }
        button("Close") { finish() }
        detail = mono(10f)
        col.addView(detail)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
        if (intent?.getBooleanExtra("selftest", false) == true) selfTest()
    }

    private fun selfTest() {
        detail.text = "Running self-test..."
        Telemetry.log("selftest_start", JSONObject())
        game.runSelfTest { summary ->
            Telemetry.log("selftest_done", JSONObject().put("summary", summary))
            detail.text = summary
        }
    }

    private fun refresh() {
        status.text = game.statusText() + "\nbackend pref ${Prefs.backend}" + if (Prefs.gpuBroken) " (gpu skipped after a crash)" else ""
    }

    private fun mono(size: Float) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFE8E6EF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextIsSelectable(true)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
