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
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

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

        /**
         * An [SSLContext] that trusts exactly the PEM certificate(s) in [caFile] — the private CA
         * that issued OpenBao's TLS listener (ADR-0352). Deliberately NOT merged with the JVM's
         * public roots: the only peer this context ever talks to is OpenBao, and a public CA has no
         * business vouching for it. Fails at construction (boot) when the file is missing or holds
         * no certificate, never at the first Transit call.
         */
        fun pemTrustSslContext(caFile: Path): SSLContext {
            val factory = CertificateFactory.getInstance("X.509")
            val certs = Files.newInputStream(caFile).use { factory.generateCertificates(it) }
            require(certs.isNotEmpty()) { "OpenBao CA file holds no certificate" }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, cert -> setCertificateEntry("openbao-ca-$i", cert) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }

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
