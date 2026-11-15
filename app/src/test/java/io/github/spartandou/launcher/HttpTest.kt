package io.github.spartandou.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class HttpTest {

    /** Sends [raw] to a local socket and returns what [Http.read] makes of it. */
    private fun read(raw: String, maxBodyBytes: Int = 1024): Http.Request? =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                client.getOutputStream().apply { write(raw.toByteArray(Charsets.UTF_8)); flush() }
                client.shutdownOutput()
                server.accept().use { Http.read(it, maxBodyBytes) }
            }
        }

    /** What a client receives from [Http.respond]. */
    private fun respond(status: Int, body: String, contentType: String): String =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { Http.respond(it, status, body, contentType) }
                client.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
        }

    @Test
    fun readsMethodPathHeadersAndBody() {
        val request = read("PUT /api/settings?x=1 HTTP/1.1\r\nAuthorization: Bearer ABCD2345\r\nContent-Length: 17\r\n\r\n{\"paused\": true}\n")!!

        assertEquals("PUT", request.method)
        assertEquals("/api/settings", request.path)
        assertEquals("Bearer ABCD2345", request.headers["authorization"])
        assertArrayEquals("{\"paused\": true}\n".toByteArray(), request.body)
    }

    @Test
    fun requestWithoutBodyHasAnEmptyOne() {
        assertEquals(0, read("GET /api/state HTTP/1.1\r\nHost: tv\r\n\r\n")!!.body!!.size)
    }

    @Test
    fun bodyLargerThanAllowedIsNotRead() {
        val request = read("POST /x HTTP/1.1\r\nContent-Length: 2000\r\n\r\n" + "a".repeat(2000))!!

        assertNull(request.body)
    }

    @Test
    fun nothingSentIsNoRequest() {
        assertNull(read(""))
    }

    @Test
    fun respondsWithLengthAndType() {
        val answer = respond(200, "{\"é\":1}", "application/json")

        assertTrue(answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer.contains("Content-Type: application/json\r\n"))
        // Bytes, not characters: "é" takes two
        assertTrue(answer.contains("Content-Length: 8\r\n"))
        assertTrue(answer.endsWith("\r\n\r\n{\"é\":1}"))
    }

    @Test
    fun noContentHasNoType() {
        val answer = respond(204, "", "application/json")

        assertTrue(answer.startsWith("HTTP/1.1 204 No Content\r\n"))
        assertFalse(answer.contains("Content-Type"))
    }

    @Test
    fun privateAddressesAndLocalNamesAreLocal() {
        listOf(
            "http://192.168.1.20:2283", "http://10.0.0.5", "http://172.16.4.1/", "http://172.31.255.255",
            "http://127.0.0.1:2283", "http://nas:2283", "http://immich.local", "http://photos.home.lan/api",
        ).forEach { assertTrue(it, Http.isLocal(it)) }
    }

    @Test
    fun publicAddressesAndNamesAreNotLocal() {
        listOf(
            "http://immich.example.com", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.1.1",
            "http://192.168.1.20.example.com", "http://",
        ).forEach { assertFalse(it, Http.isLocal(it)) }
    }
}
