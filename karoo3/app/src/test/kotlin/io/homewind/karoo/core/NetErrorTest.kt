package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NetErrorTest {
    private val okhttp = "java.net.ConnectException: Failed to connect to /192.168.178.40:80"

    @Test fun errnoFromCause() {
        val m = listOf(okhttp, "java.net.ConnectException: failed to connect to /192.168.178.40 (port 80) from /192.168.178.57 (port 40112) after 3000ms: isConnected failed: EHOSTUNREACH (No route to host)")
        assertEquals("EHOSTUNREACH", NetError.errno(m))
        assertEquals(NetError.HOST_UNREACHABLE, NetError.classify(m, wifiUp = true))
    }

    @Test fun netUnreachable() {
        val m = listOf(okhttp, "connect failed: ENETUNREACH (Network is unreachable)")
        assertEquals(NetError.WIFI_DOWN, NetError.classify(m, wifiUp = true))
    }

    @Test fun refused() {
        val m = listOf(okhttp, "connect failed: ECONNREFUSED (Connection refused)")
        assertEquals(NetError.REFUSED, NetError.classify(m, wifiUp = true))
    }

    @Test fun timeout() {
        assertEquals(NetError.TIMEOUT, NetError.classify(listOf("java.net.SocketTimeoutException: connect timed out"), wifiUp = true))
        assertEquals(NetError.TIMEOUT, NetError.classify(listOf("connect failed: ETIMEDOUT (Connection timed out)"), wifiUp = true))
    }

    @Test fun wifiStateWins() {
        assertEquals(NetError.WIFI_DOWN, NetError.classify(listOf(okhttp), wifiUp = false))
    }

    @Test fun busyWins() {
        assertEquals(NetError.BUSY, NetError.classify(listOf("Expected HTTP 101 response but was '503 Service Unavailable'"), wifiUp = true, httpStatus = 503))
    }

    @Test fun unknown() {
        assertEquals(NetError.OTHER, NetError.classify(listOf(okhttp), wifiUp = true))
    }
}
