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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Setup on the Karoo: search Homewind with mDNS or type its IP address, test,
 * save. Plain views, no extra UI libraries.
 */
class SettingsActivity : Activity() {
    private lateinit var hostField: EditText
    private lateinit var status: TextView
    private lateinit var found: LinearLayout
    private var discovery: Discovery? = null
    private var foundName: String? = null

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
        status = text(statusLine(), 14f)
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
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
        found.postDelayed({
            discovery?.stop()
            if (!any) {
                found.removeView(searching)
                found.addView(text("Nothing found. Enter the IP address (Homewind web UI or your router shows it).", 14f))
            }
        }, 8_000)
    }

    private fun testAndSave() {
        val host = Prefs.normalize(hostField.text.toString())
        if (host.isEmpty()) {
            status.text = "Enter an address."
            return
        }
        status.text = "Testing $host…"
        thread {
            val info = HomewindRuntime.testConnection(host)
            runOnUiThread {
                if (info != null) {
                    Prefs.save(this, host, foundName ?: Prefs.name(this).takeIf { Prefs.host(this) == host })
                    HomewindRuntime.reconnect()
                    status.text = "Saved: $host · $info"
                } else {
                    status.text = "No answer from $host. Check the address and the Wi-Fi."
                }
            }
        }
    }

    override fun onDestroy() {
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
}
