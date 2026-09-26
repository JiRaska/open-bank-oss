// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Egress-allowlisted HTTP/1.1 client — ADR-0320 P1 (SSRF control).
 *
 * Why not `java.net.http.HttpClient` underneath: it resolves the host name itself at connect time
 * and offers no per-client resolver, so a check-then-connect wrapper around it is exactly the
 * DNS-rebinding TOCTOU this class exists to close (the vetted answer and the connected answer are
 * two lookups). Here the name is resolved ONCE, every returned address is vetted by
 * [EgressPolicy.checkResolved], and the socket is opened to that vetted [InetAddress]. TLS is then
 * layered on with the ORIGINAL host as SNI and as the peer host, with `HTTPS` endpoint
 * identification, so the certificate must match the name — not the IP — and the `Host` header
 * carries the name as well. JDK only, no new dependency.
 *
 * Deliberately NOT a CDI bean (ADR-0320 amendment rule 1): services construct it from their own
 * config, e.g. `SafeHttpClient(EgressPolicy.fromConfig(allowedHosts))`.
 *
 * Redirects are never followed: a 3xx is returned to the caller as-is, whose `Location` must go
 * back through [send] — and therefore through the policy — if the caller chooses to follow it.
 * One request per connection (`Connection: close`); no pooling, so no pooled socket can outlive
 * the check that admitted it.
 */
class SafeHttpClient(
    private val policy: EgressPolicy,
    private val resolver: EgressResolver = EgressResolver.SYSTEM,
    private val sslContext: SSLContext = SSLContext.getDefault(),
    private val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_S),
    private val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_S),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val connector: EgressConnector = EgressConnector.PLAIN,
) {
    /** @throws EgressDeniedException when the policy refuses; [IOException] on transport failure. */
    fun send(request: EgressRequest): EgressResponse {
        val target = decide(policy.checkUrl(request.url))
        val addresses =
            try {
                resolver.resolve(target.host)
            } catch (e: IOException) {
                throw EgressDeniedException(
                    EgressDecision.Denied(EgressDenialReason.UNRESOLVABLE, "${target.host}: ${e.message}"),
                    e,
                )
            }
        decide(policy.checkResolved(target, addresses))
        val head = requestHead(target, request)
        val pinned = addresses.first()
        val raw = connector.connect(InetSocketAddress(pinned, target.port), connectTimeout)
        raw.soTimeout = readTimeout.toMillis().toInt()
        val socket = if (target.scheme == "https") wrapTls(raw, target) else raw
        socket.use { s ->
            check(s.inetAddress == pinned) { "connected address differs from the vetted one" }
            val out = s.getOutputStream()
            out.write(head)
            request.body?.let(out::write)
            out.flush()
            return readResponse(BufferedInputStream(s.getInputStream()))
        }
    }

    private fun decide(decision: EgressDecision): EgressTarget = when (decision) {
        is EgressDecision.Allowed -> decision.target
        is EgressDecision.Denied -> throw EgressDeniedException(decision)
    }

    private fun wrapTls(raw: Socket, target: EgressTarget): Socket {
        // host here is the ORIGINAL name: it becomes the SSLSession peer host, so the default
        // trust manager's HTTPS identity check runs against the name, never the pinned IP.
        val tls = sslContext.socketFactory.createSocket(raw, target.host, target.port, true) as SSLSocket
        val params = tls.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        params.serverNames = listOf(SNIHostName(target.host))
        params.protocols = params.protocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        tls.sslParameters = params
        tls.startHandshake()
        return tls
    }

    private fun requestHead(target: EgressTarget, request: EgressRequest): ByteArray {
        require(request.method.isNotEmpty() && request.method.all { it.isLetter() }) { "bad method" }
        val path = (target.uri.rawPath?.ifEmpty { "/" } ?: "/") + (target.uri.rawQuery?.let { "?$it" } ?: "")
        val defaultPort = if (target.scheme == "https") HTTPS_PORT else HTTP_PORT
        val hostHeader = if (target.port == defaultPort) target.host else "${target.host}:${target.port}"
        val sb = StringBuilder()
        sb.append(request.method.uppercase()).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(hostHeader).append("\r\n")
        request.headers.forEach { (k, v) ->
            require(k.lowercase() !in MANAGED_HEADERS) { "header '$k' is managed by SafeHttpClient" }
            require(
                k.isNotEmpty() &&
                    k.none { it == '\r' || it == '\n' || it == ':' } &&
                    v.none { it == '\r' || it == '\n' },
            ) {
                "header '$k' contains CR/LF"
            }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        request.body?.let { sb.append("Content-Length: ").append(it.size).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    // A wire parser: one throw per malformed-input case is clearer than folding them together.
    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    private fun readResponse(input: InputStream): EgressResponse {
        val statusLine = readLine(input) ?: throw IOException("empty response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("bad status line")
        val status = parts[1].toIntOrNull() ?: throw IOException("bad status code")
        val headers = linkedMapOf<String, MutableList<String>>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) throw IOException("response headers too large")
            val idx = line.indexOf(':')
            if (idx <= 0) throw IOException("bad header line")
            headers.getOrPut(line.substring(0, idx).trim().lowercase()) {
                mutableListOf()
            }.add(line.substring(idx + 1).trim())
        }
        val body =
            when {
                headers["transfer-encoding"]?.any { it.lowercase().contains("chunked") } == true -> readChunked(input)
                headers["content-length"] != null -> {
                    val len =
                        headers["content-length"]!!.first().toLongOrNull() ?: throw IOException("bad content-length")
                    if (len > maxResponseBytes) throw IOException("response body exceeds $maxResponseBytes bytes")
                    input.readNBytes(len.toInt()).also {
                        if (it.size.toLong() !=
                            len
                        ) {
                            throw IOException("truncated body")
                        }
                    }
                }
                else -> readBounded(input)
            }
        return EgressResponse(status, headers, body)
    }

    @Suppress("ThrowsCount")
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("truncated chunk")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(HEX) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) {
                    // discard trailer headers
                }
                return out.toByteArray()
            }
            if (out.size() + size > maxResponseBytes) throw IOException("response body exceeds $maxResponseBytes bytes")
            val chunk = input.readNBytes(size)
            if (chunk.size != size) throw IOException("truncated chunk")
            out.write(chunk)
            readLine(input)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes(maxResponseBytes + 1)
        if (bytes.size > maxResponseBytes) throw IOException("response body exceeds $maxResponseBytes bytes")
        return bytes
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1)
            if (c == '\n'.code) break
            if (c != '\r'.code) buf.write(c)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("line too long")
        }
        return buf.toString(Charsets.ISO_8859_1)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_S = 5L
        const val DEFAULT_READ_TIMEOUT_S = 15L
        const val DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
        private const val HEX = 16
        private val MANAGED_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")
    }
}

/** Name resolution, injectable so tests can script rebinding answers. */
fun interface EgressResolver {
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM = EgressResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/** Opens the TCP connection to an already-vetted address. Injectable for tests. */
fun interface EgressConnector {
    fun connect(address: InetSocketAddress, timeout: Duration): Socket

    companion object {
        val PLAIN =
            EgressConnector { address, timeout ->
                Socket().apply { connect(address, timeout.toMillis().toInt()) }
            }
    }
}

data class EgressRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class EgressResponse(
    val status: Int,
    /** Lower-cased header names. */
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    fun bodyAsString(): String = body.toString(Charsets.UTF_8)
}

class EgressDeniedException(val decision: EgressDecision.Denied, cause: Throwable? = null) :
    IOException("egress denied: ${decision.reason} (${decision.detail})", cause)
