package io.homewind.karoo.net

import android.net.Network
import android.util.Log
import io.homewind.karoo.core.NetError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP and WebSocket client for Homewind, plus failure classification and logging
 * (docs/HOMEWIND_DISCOVERY.md, "Runtime").
 */
object HomewindHttp {
    private const val TAG = "HomewindHttp"

    /** One base client; Wi-Fi-bound variants share its connection pool and dispatcher. */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // detects a dead WebSocket; AsyncWebSocket answers pings
        .retryOnConnectionFailure(false)
        .build()

    /** Current client, bound to the Wi-Fi network when one is known (insurance; the Karoo 3 has no cellular). */
    @Volatile var client: OkHttpClient = base
        private set

    fun bind(network: Network?) {
        client = if (network == null) base else base.newBuilder().socketFactory(network.socketFactory).build()
    }

    /** POST with the CSRF header Homewind requires; returns (status, body) or null on a network error. */
    fun post(host: String, path: String, vararg query: Pair<String, String>): Pair<Int, String>? {
        val url = buildString {
            append("http://").append(host).append(path)
            query.forEachIndexed { i, (k, v) ->
                append(if (i == 0) '?' else '&').append(k).append('=')
                append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("X-Homewind", "1")
            .post(ByteArray(0).toRequestBody())
            .build()
        return try {
            client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            logFailure(e, null, host, "post $path")
            null
        }
    }

    /** Setup screen: GET /api/v1/system/info. Returns (info, null) or (null, error text). Blocking. */
    fun testConnection(host: String): Pair<String?, String?> {
        val request = Request.Builder().url("http://$host/api/v1/system/info").get().build()
        return try {
            client.newCall(request).execute().use { r ->
                if (r.code == 503) return null to NetError.BUSY.text
                if (!r.isSuccessful) return null to "HTTP ${r.code} ${r.body?.string().orEmpty().take(60)}".trim()
                val body = r.body?.string().orEmpty()
                val name = Regex("^name=(.*)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
                val version = Regex("^version=(.*)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
                listOfNotNull(name, version).joinToString(" ").ifEmpty { "OK" } to null
            }
        } catch (e: Exception) {
            val error = logFailure(e, null, host, "setup-test")
            val errno = NetError.errno(chain(e))
            null to error.text + if (errno != null && errno !in error.text) " ($errno)" else ""
        }
    }

    /** Class names and messages of [t], its causes and suppressed exceptions. */
    fun chain(t: Throwable): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        fun walk(e: Throwable?) {
            if (e == null || !seen.add(e)) return
            out += "${e.javaClass.name}: ${e.message ?: ""}"
            e.suppressed.forEach { walk(it) }
            walk(e.cause)
        }
        walk(t)
        return out
    }

    /** Classifies and logs a failure: exception type, errno, Wi-Fi state, IP tried, step. */
    fun logFailure(t: Throwable, httpStatus: Int?, host: String, step: String): NetError {
        val messages = chain(t)
        val wifi = WifiWatcher.up.value
        val error = NetError.classify(messages, wifi, httpStatus)
        Log.w(
            TAG,
            "connect failed: kind=$error errno=${NetError.errno(messages) ?: "-"} wifi=$wifi ip=$host " +
                "step=$step http=${httpStatus ?: "-"} chain=${messages.joinToString(" <- ")}",
        )
        return error
    }
}
