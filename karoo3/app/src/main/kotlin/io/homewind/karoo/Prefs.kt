package io.homewind.karoo

import android.content.Context

/** Stored Homewind address: IP or host name, optionally with ":port". */
object Prefs {
    private const val FILE = "homewind"
    private const val KEY_HOST = "host"
    private const val KEY_NAME = "name"

    fun host(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }

    /** mDNS service name of the found device (e.g. "homewind-1a2b3c4d"), for display and re-search. */
    fun name(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_NAME, null)

    fun save(context: Context, host: String, name: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, normalize(host))
            .putString(KEY_NAME, name)
            .apply()
    }

    /** Accepts "192.168.1.20", "http://192.168.1.20/" or "homewind-x.local:80". */
    fun normalize(input: String): String =
        input.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
}
