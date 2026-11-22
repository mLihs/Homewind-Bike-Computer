package io.homewind.karoo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.homewind.karoo.net.HomewindHttp
import kotlin.concurrent.thread

/**
 * Setup on the Karoo: search Homewind with mDNS or type its IP address, test,
 * save. Plain views, no extra UI libraries.
 */
class SettingsActivity : Activity() {
    private lateinit var hostField: EditText
    private lateinit var status: TextView
    private lateinit var found: LinearLayout
    private lateinit var qr: ImageView
    private var discovery: Discovery? = null
    private var foundName: String? = null
    private var liveTestRunning = false
    private var searchTimeout: Runnable? = null
    private var liveTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(text("Homewind", 24f, bold = true))
        root.addView(text("Karoo and Homewind must be in the same Wi-Fi.", 14f))

        root.addView(button("Search Homewind") { search() })
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(found)

        root.addView(text("Address (IP)", 14f, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        hostField = EditText(this).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Prefs.host(this@SettingsActivity).orEmpty())
        }
        root.addView(hostField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(button("Test and save") { testAndSave() })
        root.addView(button("Live test (WebSocket, 12 s)") { liveTest() })
        status = text(statusLine(), 14f)
        root.addView(status)

        // QR code to the Homewind web UI, for changing settings on a phone.
        qr = ImageView(this)
        root.addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        showQr()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showQr() {
        val host = Prefs.host(this)
        if (host == null) {
            qr.visibility = android.view.View.GONE
        } else {
            qr.visibility = android.view.View.VISIBLE
            qr.setImageBitmap(Qr.bitmap(Qr.webUiUrl(host), dp(200)))
        }
    }

    private fun statusLine(): String {
        val host = Prefs.host(this) ?: return "Not set up."
        val name = Prefs.name(this)
        return "Saved: $host" + (name?.let { " ($it)" } ?: "")
    }

    private fun search() {
        found.removeAllViews()
        val searching = text("Searching…", 14f)
        found.addView(searching)
        var any = false
        discovery?.stop()
        discovery = Discovery(this) { name, host ->
            if (!any) {
                any = true
                found.removeView(searching)
            }
            found.addView(button("$name  ·  $host") {
                hostField.setText(host)
                foundName = name
            })
        }.also { it.start() }
        // Nothing found: the router may block multicast; the IP field stays the fallback.
        searchTimeout?.let { found.removeCallbacks(it) } // a second search restarts the 8 s
        val timeout = Runnable {
            discovery?.stop()
            if (!any) {
                found.removeView(searching)
                found.addView(text("Nothing found. Enter the IP address (Homewind web UI or your router shows it).", 14f))
            }
        }
        searchTimeout = timeout
        found.postDelayed(timeout, 8_000)
    }

    private fun testAndSave() {
        val host = Prefs.normalize(hostField.text.toString())
        if (host.isEmpty()) {
            status.text = "Enter an address."
            return
        }
        status.text = "Testing $host…"
        thread {
            val (info, error) = HomewindHttp.testConnection(host)
            runOnUiThread {
                if (info != null) {
                    Prefs.save(this, host, foundName ?: Prefs.name(this).takeIf { Prefs.host(this) == host })
                    HomewindRuntime.reconnect()
                    status.text = "Saved: $host · $info"
                    showQr()
                } else {
                    status.text = "No answer from $host.\n$error\nCheck the address and that the Karoo's Wi-Fi is on."
                }
            }
        }
    }

    /**
     * Opens the same connection the data fields use and shows what arrives, or why
     * it fails. Takes one of Homewind's two WebSocket slots for 12 s.
     */
    private fun liveTest() {
        if (Prefs.host(this) == null) {
            status.text = "Save an address first."
            return
        }
        if (liveTestRunning) return
        liveTestRunning = true
        HomewindRuntime.acquire(this, LIVE_TEST)
        val start = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val seconds = (System.currentTimeMillis() - start) / 1000
                val done = seconds >= 12
                status.text = describe(HomewindRuntime.field.value) + if (done) "\nTest finished." else "\n($seconds s)"
                if (done) {
                    HomewindRuntime.release(LIVE_TEST)
                    liveTestRunning = false
                } else {
                    status.postDelayed(this, 500)
                }
            }
        }
        liveTick = tick
        status.post(tick)
    }

    private fun describe(s: HomewindRuntime.FieldState): String {
        val hw = s.hw
        return buildString {
            append("Connection: ").append(s.link)
            s.error?.let { append("\nLast error: ").append(it) }
            append("\nMode: ").append(hw.mode?.label ?: "–")
            hw.effective?.takeIf { it != hw.mode }?.let { append(" (runs as ").append(it.label).append(")") }
            append("\nFan: ").append(if (hw.hasFanControl) "${hw.shownPct} %" else "–")
            append("\nHR range: ").append(if (hw.hrMin != null) "${hw.hrMin}–${hw.hrMax}" else "–")
            append("\nFans: ").append(if (hw.fansKnown) hw.fans.joinToString(" ") { it.name.lowercase() } else "–")
        }
    }

    override fun onDestroy() {
        if (liveTestRunning) HomewindRuntime.release(LIVE_TEST)
        searchTimeout?.let { found.removeCallbacks(it) }
        liveTick?.let { status.removeCallbacks(it) }
        discovery?.stop()
        super.onDestroy()
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val LIVE_TEST = "settings-live-test"
    }
}
