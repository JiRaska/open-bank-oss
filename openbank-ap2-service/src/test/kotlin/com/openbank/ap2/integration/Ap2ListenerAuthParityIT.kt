// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ap2.integration

import com.openbank.libs.testing.mtls.ListenerMaterial
import com.openbank.libs.testing.mtls.ProductionListenerProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
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
 * #12511 for ap2: the production listener shape (`client-auth: required` plus the bearer-only
 * permission on the `/ap2/` prefix) boots, refuses a client without a certificate, and serves
 * `POST /ap2/verify` identically with and without a client certificate. Phase 1 ap2 has no OIDC tenant
 * (`tenant-enabled: false`, kept here as in production) and takes the agent id from a header, so a
 * certificate identity changes nothing it does today; the permission is what keeps a future
 * authenticated route from accepting a bare fleet-CA certificate. ListenerAuthParityConformance's
 * 401 cases do not apply until phase 2 adds bearer authentication. Measured: this class passes
 * with the permission removed too, so the mtls-bearer-only gate, not this test, holds ap2 to it.
 */
@QuarkusTest
@TestProfile(Ap2ListenerAuthParityIT.Profile::class)
@Suppress("FunctionNaming")
class Ap2ListenerAuthParityIT {

    private fun port(name: String) = ConfigProvider.getConfig().getValue(name, Int::class.java)

    private fun verify(scheme: String, port: Int) =
        HttpRequest.newBuilder(URI.create("$scheme://localhost:$port/ap2/verify"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()

    private fun mtls(withClientCertificate: Boolean) = HttpClient.newBuilder().sslContext(
        ListenerMaterial.sslContext(withClientCertificate),
    ).connectTimeout(TIMEOUT).build()

    @Test
    fun `the mTLS listener refuses a client that presents no certificate`() {
        assertThatThrownBy {
            mtls(
                false,
            ).send(verify("https", port("quarkus.http.test-ssl-port")), HttpResponse.BodyHandlers.discarding())
        }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a client certificate changes nothing about how verify is served`() {
        val overMtls = mtls(
            true,
        ).send(verify("https", port("quarkus.http.test-ssl-port")), HttpResponse.BodyHandlers.ofString())
        val plain = HttpClient.newHttpClient().send(
            verify("http", port("quarkus.http.test-port")),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(overMtls.statusCode()).isEqualTo(plain.statusCode())
    }

    class Profile : ProductionListenerProfile() {
        // As in production: phase 1 runs no OIDC tenant (the kit switches one on for bearer tests).
        override fun extraOverrides() = mapOf("quarkus.oidc.tenant-enabled" to "false")
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
