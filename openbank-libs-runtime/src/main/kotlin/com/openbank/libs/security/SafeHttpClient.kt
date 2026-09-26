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
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
 * config, e.g. `SafeHttpClient(EgressPolicy.fromConfig(allowedHosts))`. Trust is always the JVM
 * default; there is no public way to supply an `SSLContext` (so no caller can pass a trust-all one).
 *
 * Redirects are never followed: a 3xx is returned to the caller as-is, whose `Location` must go
 * back through [send] — and therefore through the policy — if the caller chooses to follow it.
 * One request per connection (`Connection: close`); no pooling, so no pooled socket can outlive
 * the check that admitted it.
 */
// A self-contained wire client: splitting the parser out would widen its visibility for no gain.
@Suppress("TooManyFunctions")
class SafeHttpClient private constructor(
    private val policy: EgressPolicy,
    /** Null means the JVM default trust. Only [withTrustForTesting] can set it — see there. */
    private val sslContextOverride: SSLContext?,
    private val resolver: EgressResolver = EgressResolver.SYSTEM,
    private val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_S),
    private val readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_S),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val connector: EgressConnector = EgressConnector.PLAIN,
    private val callTimeout: Duration = Duration.ofSeconds(DEFAULT_CALL_TIMEOUT_S),
    private val dnsTimeout: Duration = Duration.ofSeconds(DEFAULT_DNS_TIMEOUT_S),
) {
    /**
     * @param readTimeout bound on any single socket read.
     * @param callTimeout bound on the WHOLE call — DNS, connect, handshake, request and response —
     *   so a server trickling one byte per [readTimeout] cannot hold the caller indefinitely.
     * @param dnsTimeout bound on name resolution (further capped by what is left of [callTimeout]).
     */
    constructor(
        policy: EgressPolicy,
        resolver: EgressResolver = EgressResolver.SYSTEM,
        connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_S),
        readTimeout: Duration = Duration.ofSeconds(DEFAULT_READ_TIMEOUT_S),
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        connector: EgressConnector = EgressConnector.PLAIN,
        callTimeout: Duration = Duration.ofSeconds(DEFAULT_CALL_TIMEOUT_S),
        dnsTimeout: Duration = Duration.ofSeconds(DEFAULT_DNS_TIMEOUT_S),
    ) : this(
        policy, null, resolver, connectTimeout, readTimeout, maxResponseBytes, connector, callTimeout, dnsTimeout,
    )

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(!callTimeout.isNegative && !callTimeout.isZero) { "callTimeout must be positive" }
    }

    /** @throws EgressDeniedException when the policy refuses; [IOException] on transport failure. */
    fun send(request: EgressRequest): EgressResponse {
        val deadline = System.nanoTime() + callTimeout.toNanos()
        val target = decide(policy.checkUrl(request.url))
        // Validate the whole request before any network activity.
        val head = requestHead(target, request)
        val addresses = resolve(target, deadline)
        decide(policy.checkResolved(target, addresses))
        val pinned = addresses.first()
        val raw =
            connector.connect(
                InetSocketAddress(pinned, target.port),
                Duration.ofMillis(minOf(connectTimeout.toMillis(), remainingMs(deadline))),
            )
        var socket: Socket = raw
        try {
            raw.soTimeout = boundedTimeoutMs(deadline)
            if (target.scheme == "https") socket = wrapTls(raw, target)
            check(socket.inetAddress == pinned) { "connected address differs from the vetted one" }
            val out = socket.getOutputStream()
            out.write(head)
            request.body?.let(out::write)
            out.flush()
            val input = BufferedInputStream(DeadlineInputStream(socket, deadline, readTimeout.toMillis()))
            return readResponse(input, request.method.uppercase())
        } finally {
            // Every path — handshake failure, timeout, parse error — releases the raw socket too.
            runCatching { socket.close() }
            if (socket !== raw) runCatching { raw.close() }
        }
    }

    private fun decide(decision: EgressDecision): EgressTarget = when (decision) {
        is EgressDecision.Allowed -> decision.target
        is EgressDecision.Denied -> throw EgressDeniedException(decision)
    }

    // ExecutionException is only a wrapper; its cause is what is rethrown, unwrapped.
    @Suppress("ThrowsCount", "SwallowedException")
    private fun resolve(target: EgressTarget, deadline: Long): List<InetAddress> {
        val budgetMs = minOf(dnsTimeout.toMillis(), remainingMs(deadline))
        fun unresolvable(why: String?, cause: Throwable?) = EgressDeniedException(
            EgressDecision.Denied(EgressDenialReason.UNRESOLVABLE, "${target.host}: $why"),
            cause,
        )
        if (budgetMs <= 0) throw unresolvable("call deadline exceeded before resolution", null)
        // The ABSOLUTE name: no search-domain expansion can substitute an in-cluster Service.
        val future =
            try {
                CompletableFuture.supplyAsync({ resolver.resolve(target.absoluteName) }, DNS_EXECUTOR)
            } catch (e: RejectedExecutionException) {
                throw unresolvable("resolver saturated", e)
            }
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw unresolvable("resolution timed out after ${budgetMs}ms", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IOException) throw unresolvable(cause.message, cause)
            throw cause ?: e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw unresolvable("interrupted", e)
        }
    }

    private fun wrapTls(raw: Socket, target: EgressTarget): Socket {
        val ctx = sslContextOverride ?: SSLContext.getDefault()
        // host here is the ORIGINAL name (no trailing dot): it becomes the SSLSession peer host, so
        // the default trust manager's HTTPS identity check runs against the name, never the pinned IP.
        val tls = ctx.socketFactory.createSocket(raw, target.host, target.port, true) as SSLSocket
        val params = tls.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        params.serverNames = listOf(SNIHostName(target.host))
        params.protocols = params.protocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        tls.sslParameters = params
        try {
            tls.startHandshake()
        } catch (e: IOException) {
            runCatching { tls.close() }
            throw e
        }
        return tls
    }

    private fun requestHead(target: EgressTarget, request: EgressRequest): ByteArray {
        require(request.method.isNotEmpty() && request.method.all(::isTokenChar)) { "bad method" }
        val path = (target.uri.rawPath?.ifEmpty { "/" } ?: "/") + (target.uri.rawQuery?.let { "?$it" } ?: "")
        val defaultPort = if (target.scheme == "https") HTTPS_PORT else HTTP_PORT
        val hostHeader = if (target.port == defaultPort) target.host else "${target.host}:${target.port}"
        val sb = StringBuilder()
        sb.append(request.method.uppercase()).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(hostHeader).append("\r\n")
        request.headers.forEach { (k, v) ->
            // Strict syntax FIRST, so the managed-name comparison below sees exactly what goes on
            // the wire ("Host " cannot pass as a different header).
            require(k.isNotEmpty() && k.all(::isTokenChar)) { "header name '$k' is not an RFC 9110 token" }
            require(v.all(::isFieldValueChar)) { "header '$k' value contains a control or non-Latin-1 character" }
            require(k.lowercase() !in MANAGED_HEADERS) { "header '$k' is managed by SafeHttpClient" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        request.body?.let { sb.append("Content-Length: ").append(it.size).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private class Head(val status: Int, val headers: Map<String, List<String>>)

    private fun readResponse(input: InputStream, method: String): EgressResponse {
        var head = readHead(input)
        var interim = 0
        while (head.status in INFORMATIONAL) {
            // 101 would hand the connection to another protocol; we never ask for one.
            if (head.status == SWITCHING_PROTOCOLS) throw IOException("unexpected 101 Switching Protocols")
            if (++interim > MAX_INTERIM_RESPONSES) throw IOException("too many interim responses")
            head = readHead(input)
        }
        val body =
            if (method == "HEAD" || head.status == NO_CONTENT || head.status == NOT_MODIFIED) {
                ByteArray(0)
            } else {
                readBody(input, head.headers)
            }
        return EgressResponse(head.status, head.headers, body)
    }

    // A wire parser: one throw per malformed-input case is clearer than folding them together.
    @Suppress("ThrowsCount")
    private fun readHead(input: InputStream): Head {
        val statusLine = readLine(input) ?: throw IOException("empty response")
        val m = STATUS_LINE.matchEntire(statusLine) ?: throw IOException("bad status line")
        val status = m.groupValues[1].toInt()
        if (status !in MIN_STATUS..MAX_STATUS) throw IOException("bad status code $status")
        val headers = linkedMapOf<String, MutableList<String>>()
        readFields(input) { name, value -> headers.getOrPut(name) { mutableListOf() }.add(value) }
        return Head(status, headers)
    }

    /** Header or trailer section, up to the empty line. Bounded in bytes and field count. */
    @Suppress("ThrowsCount")
    private fun readFields(input: InputStream, sink: (String, String) -> Unit) {
        var bytes = 0
        var count = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated headers")
            if (line.isEmpty()) return
            if (line[0] == ' ' || line[0] == '\t') throw IOException("obsolete line folding")
            bytes += line.length
            if (bytes > MAX_HEADER_BYTES) throw IOException("response headers too large")
            if (++count > MAX_HEADER_COUNT) throw IOException("too many response headers")
            val idx = line.indexOf(':')
            if (idx <= 0) throw IOException("bad header line")
            val name = line.substring(0, idx)
            if (!name.all(::isTokenChar)) throw IOException("bad header name")
            sink(name.lowercase(), line.substring(idx + 1).trim(' ', '\t'))
        }
    }

    // RFC 9112 §6.3 framing: any ambiguity is an attack surface, so refuse rather than guess.
    @Suppress("ThrowsCount")
    private fun readBody(input: InputStream, headers: Map<String, List<String>>): ByteArray {
        val te = headers["transfer-encoding"]
        val cl = headers["content-length"]
        return when {
            te != null && cl != null -> throw IOException("both Transfer-Encoding and Content-Length")
            te != null -> {
                val codings = te.flatMap { it.split(',') }.map { it.trim(' ', '\t').lowercase() }
                if (codings != listOf("chunked")) throw IOException("unsupported transfer-encoding $codings")
                readChunked(input)
            }
            cl != null -> {
                if (cl.size != 1) throw IOException("duplicate content-length")
                val raw = cl.single()
                if (!DECIMAL.matches(raw)) throw IOException("bad content-length")
                val len = raw.toLong()
                if (len > maxResponseBytes) throw IOException("response body exceeds $maxResponseBytes bytes")
                val body = input.readNBytes(len.toInt())
                if (body.size.toLong() != len) throw IOException("truncated body")
                body
            }
            else -> readBounded(input)
        }
    }

    @Suppress("ThrowsCount")
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("truncated chunk")
            // chunk-ext (after ';') is ignored; the size itself must be bare hex, bounded in length.
            val sizeField = sizeLine.substringBefore(';').trimEnd(' ', '\t')
            if (!CHUNK_SIZE.matches(sizeField)) throw IOException("bad chunk size")
            val size = sizeField.toLong(HEX)
            if (size == 0L) {
                readFields(input) { _, _ -> } // trailers: bounded, then discarded
                return out.toByteArray()
            }
            if (size > maxResponseBytes.toLong() - out.size()) {
                throw IOException("response body exceeds $maxResponseBytes bytes")
            }
            val chunk = input.readNBytes(size.toInt())
            if (chunk.size.toLong() != size) throw IOException("truncated chunk")
            out.write(chunk)
            if (readLine(input) != "") throw IOException("missing CRLF after chunk")
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = input.readNBytes(maxResponseBytes + 1)
        if (bytes.size > maxResponseBytes) throw IOException("response body exceeds $maxResponseBytes bytes")
        return bytes
    }

    /** One CRLF-terminated line. Null on EOF before any byte; bare CR or bare LF is an error. */
    @Suppress("ThrowsCount")
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) {
                if (buf.size() == 0) return null
                throw IOException("truncated line")
            }
            if (c == '\n'.code) throw IOException("bare LF line terminator")
            if (c == '\r'.code) {
                if (input.read() != '\n'.code) throw IOException("bare CR in line")
                return buf.toString(Charsets.ISO_8859_1)
            }
            buf.write(c)
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("line too long")
        }
    }

    /** Arms each read with min(readTimeout, time left on the call deadline). */
    private class DeadlineInputStream(
        private val socket: Socket,
        private val deadline: Long,
        private val readTimeoutMs: Long,
    ) : InputStream() {
        private val inner = socket.getInputStream()

        private fun arm() {
            socket.soTimeout = minOf(readTimeoutMs, boundedTimeoutMs(deadline).toLong()).toInt()
        }

        override fun read(): Int {
            arm()
            return inner.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            arm()
            return inner.read(b, off, len)
        }

        override fun close() = inner.close()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_S = 5L
        const val DEFAULT_READ_TIMEOUT_S = 15L
        const val DEFAULT_CALL_TIMEOUT_S = 30L
        const val DEFAULT_DNS_TIMEOUT_S = 5L
        const val DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_HEADER_COUNT = 100
        private const val MAX_INTERIM_RESPONSES = 8
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
        private const val HEX = 16
        private const val MIN_STATUS = 100
        private const val MAX_STATUS = 599
        private const val SWITCHING_PROTOCOLS = 101
        private const val NO_CONTENT = 204
        private const val NOT_MODIFIED = 304
        private const val DNS_POOL_MAX = 16
        private const val DNS_POOL_KEEPALIVE_S = 30L
        private const val DEL = 0x7f
        private const val VCHAR_MIN = 0x21
        private const val OBS_TEXT_MIN = 0x80
        private const val LATIN1_MAX = 0xff
        private val INFORMATIONAL = 100..199
        private val STATUS_LINE = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: [^\\x00-\\x08\\x0a-\\x1f\\x7f]*)?$")
        private val CHUNK_SIZE = Regex("^[0-9A-Fa-f]{1,8}$")
        private val DECIMAL = Regex("^[0-9]{1,18}$")
        private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
        private val MANAGED_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")

        /** Bounded pool: a resolver that never answers can pin at most [DNS_POOL_MAX] threads. */
        private val DNS_EXECUTOR =
            ThreadPoolExecutor(0, DNS_POOL_MAX, DNS_POOL_KEEPALIVE_S, TimeUnit.SECONDS, SynchronousQueue()) { r ->
                Thread(r, "safe-http-dns").apply { isDaemon = true }
            }

        /**
         * TEST-ONLY: a client that trusts [sslContext] instead of the JVM default. `internal` so no
         * production module can hand SafeHttpClient a trust-all context; there is deliberately no
         * public way to change trust.
         */
        internal fun withTrustForTesting(
            sslContext: SSLContext,
            policy: EgressPolicy,
            resolver: EgressResolver = EgressResolver.SYSTEM,
        ): SafeHttpClient = SafeHttpClient(policy, sslContext, resolver)

        private fun remainingMs(deadline: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())

        /** Time left as a socket timeout; never 0 (which would mean "infinite"). */
        private fun boundedTimeoutMs(deadline: Long): Int {
            val left = remainingMs(deadline)
            if (left <= 0) throw SocketTimeoutException("call deadline exceeded")
            return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun isTokenChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TOKEN_SYMBOLS

        /** field-value octets: VCHAR, SP, HTAB, obs-text (0x80-0xFF). No CTL, nothing beyond Latin-1. */
        private fun isFieldValueChar(c: Char): Boolean =
            c == ' ' || c == '\t' || (c.code in VCHAR_MIN until DEL) || (c.code in OBS_TEXT_MIN..LATIN1_MAX)
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
                val socket = Socket()
                try {
                    socket.connect(address, timeout.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                } catch (e: IOException) {
                    socket.close()
                    throw e
                }
                socket
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
