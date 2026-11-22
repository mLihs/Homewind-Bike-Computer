package io.homewind.karoo.core

/**
 * Classifies connection failures as in docs/HOMEWIND_DISCOVERY.md ("Runtime"), from the
 * exception messages of the whole cause chain. Android puts the errno name into the
 * message of the underlying socket exception, e.g.
 * "failed to connect to /192.168.178.40 (port 80) … : connect failed: EHOSTUNREACH (No route to host)";
 * OkHttp wraps it in "Failed to connect to /192.168.178.40:80".
 */
enum class NetError(val text: String) {
    WIFI_DOWN("Karoo not connected to Wi-Fi"),
    HOST_UNREACHABLE("Homewind not reachable (EHOSTUNREACH)"),
    REFUSED("Connection refused (ECONNREFUSED) – IP may belong to another device"),
    TIMEOUT("Homewind not answering (timeout)"),
    BUSY("Homewind busy (update or maintenance)"),
    OTHER("Connection failed");

    companion object {
        private val ERRNO = Regex("\\b(ENETUNREACH|EHOSTUNREACH|ECONNREFUSED|ETIMEDOUT|ECONNRESET|EHOSTDOWN|ENONET)\\b")

        /** The errno name found in [messages], if any (for the log). */
        fun errno(messages: List<String>): String? = messages.firstNotNullOfOrNull { ERRNO.find(it)?.value }

        /**
         * [messages]: messages and class names of the exception, its causes and suppressed
         * exceptions. [wifiUp]: Wi-Fi state at the moment of the failure. [httpStatus]: status
         * of a failed WebSocket handshake or HTTP request, if there was a response.
         */
        fun classify(messages: List<String>, wifiUp: Boolean, httpStatus: Int? = null): NetError {
            if (httpStatus == 503) return BUSY
            if (!wifiUp) return WIFI_DOWN
            return when (errno(messages)) {
                "ENETUNREACH", "ENONET" -> WIFI_DOWN
                "EHOSTUNREACH", "EHOSTDOWN" -> HOST_UNREACHABLE
                "ECONNREFUSED" -> REFUSED
                "ETIMEDOUT" -> TIMEOUT
                else -> when {
                    messages.any { it.contains("SocketTimeoutException") || it.contains("timeout", ignoreCase = true) } -> TIMEOUT
                    messages.any { it.contains("Connection refused", ignoreCase = true) } -> REFUSED
                    messages.any { it.contains("Network is unreachable", ignoreCase = true) } -> WIFI_DOWN
                    messages.any { it.contains("No route to host", ignoreCase = true) } -> HOST_UNREACHABLE
                    else -> OTHER
                }
            }
        }
    }
}
