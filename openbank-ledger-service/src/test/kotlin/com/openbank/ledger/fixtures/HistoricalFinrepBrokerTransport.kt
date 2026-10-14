// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.fixtures

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.config.TlsConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.util.Timeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.jar.JarFile

/** Per-run transport. Credentials never enter Pact options, diagnostic messages or tracked artifacts. */
internal class HistoricalFinrepBrokerTransport(
    private val origin: URI,
    private val bearerToken: String? = null,
    private val username: String? = null,
    private val password: String? = null,
) : AutoCloseable {
    init {
        HistoricalFinrepPactArtifact.publicationUri(origin, "0".repeat(40))
        require(origin.port == -1 || origin.port in 1..65535) { "Broker port is invalid" }
        require((username == null) == (password == null) && (username == null || bearerToken == null)) {
            "Broker authentication configuration is ambiguous"
        }
        require(
            username == null ||
                (
                    origin.scheme == "https" &&
                        username.isNotBlank() &&
                        ':' !in username &&
                        username.none { it.isISOControl() } &&
                        password!!.isNotBlank() &&
                        password.none { it.isISOControl() }
                    ),
        ) { "Basic credentials require a secure broker origin and valid values" }
        require(bearerToken == null || (origin.scheme == "https" && bearerToken.none { it.isISOControl() })) {
            "Credentials require a secure broker origin"
        }
    }

    private val client = HttpClients.custom().disableRedirectHandling().disableAutomaticRetries()
        .disableCookieManagement().disableAuthCaching()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10))
                        .setSocketTimeout(Timeout.ofSeconds(10)).build(),
                )
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(Timeout.ofSeconds(10)).build())
                .build(),
        )
        .setDefaultRequestConfig(
            RequestConfig.custom().setAuthenticationEnabled(false).setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(10)).build(),
        ).build()

    fun validateLink(value: String): URI {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Broker link is malformed")
        }
        require(
            uri.scheme == origin.scheme &&
                uri.host == origin.host &&
                uri.port == origin.port &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.orEmpty().startsWith("/") &&
                !uri.rawPath.startsWith("//") &&
                '%' !in uri.rawPath &&
                '\\' !in uri.rawPath &&
                uri.normalize() == uri,
        ) { "Broker link is outside the trusted origin or is ambiguous" }
        return uri
    }

    fun get(uri: URI): ByteArray = exchange(validateLink(uri.toString()), null).second

    fun post(uri: URI, payload: String): Pair<URI, ByteArray> = exchange(validateLink(uri.toString()), payload)

    private fun exchange(uri: URI, payload: String?): Pair<URI, ByteArray> {
        ensureSafeDiagnostics()
        val request = if (payload == null) {
            HttpGet(uri)
        } else {
            HttpPost(uri).apply {
                entity = StringEntity(payload, ContentType.APPLICATION_JSON)
            }
        }
        request.setHeader("Accept", "application/hal+json, application/json")
        bearerToken?.let { request.setHeader("Authorization", "Bearer $it") }
        username?.let {
            val encoded = java.util.Base64.getEncoder().encodeToString("$it:$password".toByteArray(Charsets.UTF_8))
            request.setHeader("Authorization", "Basic $encoded")
        }
        return try {
            client.execute(request) { response ->
                require(response.code == if (payload == null) 200 else 201) { "Broker request was not accepted" }
                val location = if (payload == null) {
                    uri
                } else {
                    validateLink(
                        requireNotNull(response.getFirstHeader("Location")?.value) {
                            "Publication has no receipt link"
                        },
                    )
                }
                val bytes = requireNotNull(response.entity) { "Broker response has no body" }.content.use {
                    it.readNBytes(MAX_BYTES + 1)
                }
                require(bytes.size <= MAX_BYTES) { "Broker response is too large" }
                location to bytes
            }
        } catch (_: IOException) {
            error("Broker HTTP exchange failed")
        }
    }

    override fun close() {
        try {
            client.close()
        } catch (_: IOException) {
            error("Broker transport close failed")
        }
    }

    companion object {
        // Apache diagnostic categories include request targets and wire/header contents before throwing.
        // Refuse verbose transport diagnostics for this run; never mutate shared logging configuration.
        private val diagnosticCategories: Set<String> by lazy {
            val classes = listOf(HttpClients::class.java, ContentType::class.java)
            classes.flatMap { type ->
                JarFile(type.protectionDomain.codeSource.location.path).use { jar ->
                    jar.entries().asSequence().map { it.name }
                        .filter { it.startsWith("org/apache/hc/") && it.endsWith(".class") }
                        .map { it.removeSuffix(".class").replace('/', '.') }.toList()
                }
            }.toSet() + setOf(
                "org.apache.hc.client5.http.headers",
                "org.apache.hc.client5.http.wire",
                "org.apache.hc.core5.http.headers",
                "org.apache.hc.core5.http.wire",
            )
        }

        fun ensureSafeDiagnostics() {
            require(
                System.getProperty("javax.net.debug").isNullOrBlank() &&
                    System.getProperty("java.security.debug").isNullOrBlank(),
            ) {
                "Verbose broker TLS diagnostics are not permitted"
            }
            require(
                diagnosticCategories.none {
                    LoggerFactory.getLogger(it).let { logger ->
                        logger.isDebugEnabled ||
                            logger.isTraceEnabled
                    }
                },
            ) {
                "Verbose broker transport diagnostics are not permitted"
            }
        }

        private const val MAX_BYTES = 10 * 1024 * 1024
    }
}
