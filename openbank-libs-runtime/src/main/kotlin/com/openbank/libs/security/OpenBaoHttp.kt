// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLContext

/**
 * Transport rules shared by [OpenBaoTransitFieldProtector] and [OpenBaoKubernetesLogin]
 * (ADR-0320 P3 security review):
 *
 * - **https only.** A token and plaintext travel in every request, so `http://` is refused unless
 *   the caller passes [OpenBaoTransport.allowInsecureHttpForTests] — a switch named for the one
 *   place it belongs.
 * - **Path prefix preserved.** `https://proxy/bao/` + `v1/transit/...` resolves to
 *   `https://proxy/bao/v1/transit/...`; an absolute `/v1/...` would silently drop `/bao`.
 * - **Bounded body.** Responses above [MAX_RESPONSE_BYTES] fail closed instead of being buffered.
 * - **TLS trust** is the caller's [SSLContext] (e.g. built from the OpenBao CA bundle) or the JVM
 *   default.
 */
class OpenBaoTransport(
    baoAddr: URI,
    sslContext: SSLContext? = null,
    val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS),
    allowInsecureHttpForTests: Boolean = false,
    httpClient: HttpClient? = null,
) {
    val base: URI
    val httpClient: HttpClient = httpClient ?: defaultHttpClient(sslContext)

    init {
        val scheme = baoAddr.scheme?.lowercase()
        require(scheme == "https" || (scheme == "http" && allowInsecureHttpForTests)) {
            "OpenBao address must use https (http is allowed only with allowInsecureHttpForTests)"
        }
        require(baoAddr.host != null) { "OpenBao address must carry a host" }
        require(baoAddr.rawQuery == null && baoAddr.rawFragment == null) { "OpenBao address must not carry a query" }
        val path = baoAddr.rawPath.orEmpty()
        base = baoAddr.resolve(if (path.endsWith("/")) path else "$path/")
    }

    /** Resolves [relative] (no leading slash) under the configured base, keeping its path prefix. */
    fun uri(relative: String): URI {
        require(!relative.startsWith("/")) { "relative OpenBao path expected" }
        return base.resolve(relative)
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 10L
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L

        /** Largest response body read from OpenBao; a field-level reply is a few hundred bytes. */
        const val MAX_RESPONSE_BYTES = 64 * 1024

        fun defaultHttpClient(sslContext: SSLContext? = null): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .apply { if (sslContext != null) sslContext(sslContext) }
            .build()

        /** A body handler that fails with [IOException] once more than [limit] bytes arrive. */
        fun boundedBody(limit: Int = MAX_RESPONSE_BYTES): HttpResponse.BodyHandler<String> =
            HttpResponse.BodyHandler { _ ->
                HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream()) { stream ->
                    stream.use { readBounded(it, limit) }
                }
            }

        internal fun readBounded(stream: InputStream, limit: Int): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (out.size() + n > limit) throw ResponseTooLargeException(limit)
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8)
        }

        private const val BUFFER_BYTES = 8192
    }
}

/** Raised (inside the body subscriber) when OpenBao answers with more than the allowed bytes. */
class ResponseTooLargeException(limit: Int) : IOException("OpenBao response exceeded $limit bytes")
