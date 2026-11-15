package io.github.spartandou.launcher

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/** The little HTTP the two local servers need: one request per connection, small bodies. */
object Http {
    private const val MAX_LINE_BYTES = 8 * 1024
    private val LOCAL_SUFFIXES = setOf("local", "lan", "home", "internal", "localdomain")
    private val STATUS_TEXT = mapOf(
        200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
        405 to "Method Not Allowed", 413 to "Payload Too Large", 429 to "Too Many Requests",
    )

    /** [body] is null when the announced body is larger than allowed. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray?)

    /** Null when the client sent nothing readable. */
    fun read(client: Socket, maxBodyBytes: Int): Request? {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return null
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 } }
            .associate { (name, value) -> name.trim().lowercase() to value.trim() }
        val parts = requestLine.split(" ")
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length in 0..maxBodyBytes) readBytes(input, length) else null
        return Request(parts.getOrNull(0).orEmpty(), parts.getOrNull(1).orEmpty().substringBefore('?'), headers, body)
    }

    /** [download]: the name of the file the browser saves the body as, instead of showing it. */
    fun respond(
        client: Socket,
        status: Int,
        body: String = "",
        contentType: String = "text/html; charset=utf-8",
        download: String? = null,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${STATUS_TEXT[status] ?: "OK"}\r\n" +
            (if (status == 204) "" else "Content-Type: $contentType\r\n") +
            (download?.let { "Content-Disposition: attachment; filename=\"$it\"\r\n" } ?: "") +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    /** Ethernet or Wi-Fi address another device can reach on the local network. */
    fun localIpv4Address(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress

    /** True for an address of the local network: a private IPv4 address or a local name. */
    fun isLocal(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val parts = host.split('.')
        val numbers = parts.map { it.toIntOrNull() }
        if (parts.size == 4 && numbers.all { it != null && it in 0..255 }) {
            val (a, b) = numbers.map { it!! }
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return host.isNotEmpty() && (parts.size == 1 || parts.last() in LOCAL_SUFFIXES)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == '\n'.code) return buffer.toString("ISO-8859-1").trimEnd('\r')
            if (buffer.size() >= MAX_LINE_BYTES) return null
            buffer.write(byte)
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count == -1) break
            read += count
        }
        return bytes.copyOf(read)
    }
}
