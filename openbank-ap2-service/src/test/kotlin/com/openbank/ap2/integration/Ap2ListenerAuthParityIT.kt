// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// See LICENSE in this module for details.

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
 * permission on the `/api/v2/ap2/` prefix) boots, refuses a client without a certificate, and serves
 * `POST /api/v2/ap2/verify` identically with and without a client certificate. The OIDC bearer is the
 * only source of agent identity; a certificate or X-Agent-Id header alone cannot authenticate.
 */
@QuarkusTest
@TestProfile(Ap2ListenerAuthParityIT.Profile::class)
@Suppress("FunctionNaming")
class Ap2ListenerAuthParityIT {

    private fun port(name: String) = ConfigProvider.getConfig().getValue(name, Int::class.java)

    private fun verify(scheme: String, port: Int, bearer: String? = null, assertedAgent: String? = null) =
        HttpRequest.newBuilder(URI.create("$scheme://localhost:$port/api/v2/ap2/verify"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .apply { assertedAgent?.let { header("X-Agent-Id", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(VERIFY_BODY))
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
    fun `certificate and asserted agent header without bearer are both rejected`() {
        assertThat(overMtls(null, "agent:ap2-anonymous")).isEqualTo(401)
        assertThat(overPlain(null, "agent:ap2-anonymous")).isEqualTo(401)
    }

    @Test
    fun `invalid and wrong-audience bearers are rejected on both listeners`() {
        assertThat(overMtls("invalid")).isEqualTo(401)
        assertThat(overPlain("invalid")).isEqualTo(401)
        val wrongAudience = ListenerMaterial.bearer(subject = "agent:test", audience = "another-service")
        assertThat(overMtls(wrongAudience)).isEqualTo(401)
        assertThat(overPlain(wrongAudience)).isEqualTo(401)
    }

    @Test
    fun `verified non-agent subject cannot borrow asserted agent header`() {
        val nonAgent = ListenerMaterial.bearer(subject = "service-account-shared", audience = AUDIENCE)
        assertThat(overMtls(nonAgent, "agent:ap2-anonymous")).isEqualTo(403)
        assertThat(overPlain(nonAgent, "agent:ap2-anonymous")).isEqualTo(403)
    }

    @Test
    fun `verified agent bearer reaches the same endpoint on both listeners`() {
        val agent = ListenerMaterial.bearer(subject = "agent:test", audience = AUDIENCE)
        assertThat(overMtls(agent)).isEqualTo(200)
        assertThat(overPlain(agent)).isEqualTo(200)
    }

    private fun overMtls(bearer: String?, assertedAgent: String? = null) = mtls(true).send(
        verify("https", port("quarkus.http.test-ssl-port"), bearer, assertedAgent),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private fun overPlain(bearer: String?, assertedAgent: String? = null) = HttpClient.newHttpClient().send(
        verify("http", port("quarkus.http.test-port"), bearer, assertedAgent),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    class Profile : ProductionListenerProfile()

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val AUDIENCE = "openbank-ap2-service"
        val VERIFY_BODY = """
            {
              "mandate": {
                "kind": "PAYMENT", "issuer": "test", "subject": "test",
                "constraints": {
                  "payee": "test", "amountCapMinor": 100, "currency": "CZK",
                  "expiresAt": "2099-01-01T00:00:00Z", "singleUse": false
                },
                "signingInput": "x", "signatureB64": "eA", "algorithm": "ED25519"
              },
              "payment": {
                "payee": "test", "amountMinor": 50, "currency": "CZK", "at": "2026-01-01T00:00:00Z"
              }
            }
        """.trimIndent()
    }
}
