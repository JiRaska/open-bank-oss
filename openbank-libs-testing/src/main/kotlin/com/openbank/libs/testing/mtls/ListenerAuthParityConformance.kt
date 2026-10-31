// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.mtls

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Listener-auth parity for a service whose `%prod` profile sets `quarkus.http.ssl.client-auth`
 * (#12511). With client-auth on, Quarkus also registers its mTLS authentication MECHANISM, which
 * turns any certificate the trust store accepts into an authenticated identity. Without a
 * bearer-only permission on the API, a caller holding a fleet-CA workload certificate and no
 * token is "logged in" on 8443 (treasury measured 403 from authz instead of 401, #12506).
 *
 * Over real HTTP, on both listeners, with the production listener shape ([ProductionListenerProfile]):
 *
 *  1. 8443 refuses the TLS handshake to a client presenting no certificate;
 *  2. 8443 with a trusted client certificate and NO bearer is 401, like the plain port;
 *  3. 8443 with the certificate AND a valid bearer is served ([authenticatedStatus]), exactly as
 *     the plain port serves the same bearer: the certificate changes nothing.
 *
 * Subclass with `@QuarkusTest @TestProfile(<a ProductionListenerProfile>)` plus the service's own
 * test resources, and name one authenticated GET route in [path].
 */
@Suppress("FunctionNaming") // one @Test per conformance case
abstract class ListenerAuthParityConformance {

    /** An authenticated GET route under the API (path + query), e.g. `/api/v1/things/x`. */
    abstract val path: String

    /** What [path] answers an authenticated operator over either port (authz runs advisory in tests). */
    open val authenticatedStatus: Int = OK

    private fun port(name: String) = ConfigProvider.getConfig().getValue(name, Int::class.java)

    private fun request(scheme: String, port: Int, bearer: String?) =
        HttpRequest.newBuilder(URI.create("$scheme://localhost:$port$path"))
            .timeout(TIMEOUT)
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .GET()
            .build()

    private fun mtls(withClientCertificate: Boolean) = HttpClient.newBuilder().sslContext(
        ListenerMaterial.sslContext(withClientCertificate),
    ).connectTimeout(TIMEOUT).build()

    private fun viaMtls(bearer: String?) = mtls(
        true,
    ).send(request("https", port("quarkus.http.test-ssl-port"), bearer), HttpResponse.BodyHandlers.discarding())

    private fun viaPlain(bearer: String?) = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
        .send(request("http", port("quarkus.http.test-port"), bearer), HttpResponse.BodyHandlers.discarding())

    @Test
    fun `the mTLS listener refuses a client that presents no certificate`() {
        assertThatThrownBy {
            mtls(
                false,
            ).send(request("https", port("quarkus.http.test-ssl-port"), null), HttpResponse.BodyHandlers.discarding())
        }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a trusted client certificate without a bearer is 401 on the mTLS listener`() {
        assertThat(
            viaMtls(null).statusCode(),
        ).`as`("8443, client certificate, no bearer: $path").isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `the plain HTTP listener refuses the same route without a bearer`() {
        assertThat(viaPlain(null).statusCode()).`as`("plain port, no bearer: $path").isEqualTo(UNAUTHORIZED)
    }

    @Test
    fun `a valid bearer with the certificate is served exactly as on the plain port`() {
        val bearer = ListenerMaterial.bearer()
        assertThat(
            viaMtls(bearer).statusCode(),
        ).`as`("8443, client certificate + valid bearer: $path").isEqualTo(authenticatedStatus)
        assertThat(viaPlain(bearer).statusCode()).`as`("plain port, same bearer: $path").isEqualTo(authenticatedStatus)
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
