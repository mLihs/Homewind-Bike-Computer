package io.homewind.karoo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen QR code with the Homewind web UI address, opened by tapping the
 * "Homewind apps" field (like the Settings button on the Touch). A tap anywhere closes it.
 */
class QrActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = Prefs.host(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = if (host != null) "Scan to open Homewind" else "Homewind is not set up"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (host != null) {
            val size = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            root.addView(
                ImageView(this).apply { setImageBitmap(Qr.bitmap(Qr.webUiUrl(host), size)) },
                LinearLayout.LayoutParams(size, size),
            )
            root.addView(TextView(this).apply {
                text = "$host\n\nTap to close"
                textSize = 14f
                setTextColor(0xB3FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
