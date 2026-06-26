// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.integration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.openbank.libs.iso20022.Pacs002Builder
import com.openbank.libs.iso20022.PaymentStatus
import com.openbank.libs.iso20022.PaymentStatusReport
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Stands in for the `openbank-clearing-simulator` over real HTTP (ADR-0104 D3) so the scheme
 * gateway adapter's REST-client + XML content negotiation + oidc-client token attachment run for
 * real — the path [com.openbank.sepa.infrastructure.client.SchemeGatewayAdapterTest] mocks away.
 *
 * The stubbed response is a genuine `pacs.002` built by the shared [Pacs002Builder], so the
 * adapter's [com.openbank.libs.iso20022.Pacs002Reader] parses exactly what the real simulator
 * would emit. The default stub settles (`ACSC`); [stubReject] re-stubs an `RJCT` for the
 * reject-path test.
 */
class ClearingSimulatorWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        server = WireMockServer(options().dynamicPort())
        server.start()

        // The scheme gateway client carries an OIDC bearer (service-to-service); stub the token.
        server.stubFor(
            post(urlEqualTo("/token")).willReturn(
                okJson("""{"access_token":"test-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
        stubSettle()

        val base = server.baseUrl()
        return mapOf(
            "quarkus.rest-client.clearing-simulator.url" to base,
            // The adapter's client uses OidcClientRequestReactiveFilter, so oidc-client must be
            // ON here (the %test profile disables it) and pointed at the stubbed token endpoint.
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
        )
    }

    override fun stop() {
        if (isStarted()) server.stop()
    }

    companion object {
        lateinit var server: WireMockServer

        fun isStarted() = ::server.isInitialized

        const val CREDIT_TRANSFERS_PATH = "/api/v1/clearing/credit-transfers"

        /** Default: the scheme settles the transfer (`ACSC`). */
        fun stubSettle() = stubPacs002(PaymentStatus.ACSC, reasonCode = null)

        /** Re-stub so the scheme rejects with the given ISO 20022 reason code (e.g. `AC04`). */
        fun stubReject(reasonCode: String) = stubPacs002(PaymentStatus.RJCT, reasonCode)

        private fun stubPacs002(status: PaymentStatus, reasonCode: String?) {
            val xml = Pacs002Builder().build(
                PaymentStatusReport(
                    messageId = "SIM-STS-IT",
                    creationDateTime = OffsetDateTime.of(2026, 6, 22, 10, 0, 0, 0, ZoneOffset.UTC),
                    originalEndToEndId = "E2E-IT-0001",
                    originalTransactionId = null,
                    status = status,
                    reasonCode = reasonCode,
                    additionalInfo = reasonCode?.let { "scheme reject $it" },
                ),
            )
            server.stubFor(
                post(urlEqualTo(CREDIT_TRANSFERS_PATH)).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(xml),
                ),
            )
        }
    }
}
