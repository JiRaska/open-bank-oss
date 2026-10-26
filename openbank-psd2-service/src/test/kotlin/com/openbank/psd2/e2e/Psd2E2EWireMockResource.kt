// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.notFound
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serverError
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for every out-of-process hop the Berlin XS2A surface makes — the tpp-registry
 * authorization check [EidasMtlsFilter][com.openbank.psd2.infrastructure.rest.filter.EidasMtlsFilter]
 * runs per request through [TppAuthorizationGuard][com.openbank.psd2.infrastructure.client.TppAuthorizationGuard]
 * (@Retry/@CircuitBreaker), the consent-service reads/validations the AIS/PIS use cases fail closed
 * on ([ConsentServiceRestClient][com.openbank.psd2.infrastructure.client.ConsentServiceRestClient]
 * with its OIDC M2M filter), and the Keycloak token endpoint the OIDC client mints the M2M bearer
 * from. Same downstream-resource shape as vop/statement/risk-engine/kyb: the guards, their
 * fault-tolerance annotations, the OIDC filter and the fail-closed fallbacks all cross a wire they
 * actually have to.
 *
 * The request journal is the evidence: [requestsTo] counts requests since the last
 * [resetRequests], so a test can prove "the retry actually dialled the registry three times",
 * "the banned TPP never reached consent-service" or "the replay was served from the Redis
 * idempotency store without re-validating" with
 * [com.github.tomakehurst.wiremock.verification.LoggedRequest] query params and bodies as the
 * verbatim record.
 *
 * Scenario identities: `TPP-GOOD` is authorized for both roles, `TPP-BANNED` is rejected by the
 * registry, `TPP-OUTAGE` makes every registry answer 500 (the guard's @Retry fan-out), consent
 * `consent-active-e2e` is ACTIVE, `consent-pending-e2e` is PENDING_SCA, `consent-denied-e2e`
 * fails validation and `consent-outage-e2e` makes validation answer 500 (the use-case client's
 * @Retry + fail-closed fallback).
 */
class Psd2E2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val wm = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server = wm
        wm.start()
        stubTokenEndpoint(wm)
        stubTppRegistry(wm)
        stubConsentService(wm)

        val base = wm.baseUrl()
        return mapOf(
            // Build-time flag read at augmentation: pin it in this high-ordinal config map so the
            // OIDC client is active for the consent-service REST client's M2M filter regardless of
            // which profile yaml augmentation consulted (same lesson as risk-engine/kyb).
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to TOKEN_PATH,
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
            "quarkus.rest-client.tpp-registry.url" to base,
            "quarkus.rest-client.consent-service.url" to base,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    /** Keycloak token endpoint: the single `openbank-services` client gets one token shape. */
    private fun stubTokenEndpoint(wm: WireMockServer) {
        // Quarkus' oidc-client may authenticate with client_id in the form body OR with HTTP
        // Basic (client_id:secret); the form carrier is matched explicitly, Basic falls into the
        // catch-all — both get the suite's pinned token so the Authorization header the
        // OidcClientRequestReactiveFilter attaches is journal-assertable.
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(1).withRequestBody(containing("client_id=openbank-services"))
                .willReturn(okJson(TOKEN_RESPONSE)),
        )
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_RESPONSE)))
    }

    /** tpp-registry `/check`: authorized by default, banned/outage scenarios at priority. */
    private fun stubTppRegistry(wm: WireMockServer) {
        // Priority 1 so the scenario stubs win over the catch-all below regardless of
        // registration order (same-priority stubs resolve last-registered-first).
        wm.stubFor(
            get(urlPathEqualTo(CHECK_PATH)).atPriority(1).withQueryParam("tppId", equalTo("TPP-BANNED"))
                .willReturn(okJson(AUTHORIZATION_DENIED)),
        )
        wm.stubFor(
            get(urlPathEqualTo(CHECK_PATH)).atPriority(1).withQueryParam("tppId", equalTo("TPP-OUTAGE"))
                .willReturn(serverError()),
        )
        wm.stubFor(get(urlPathEqualTo(CHECK_PATH)).willReturn(okJson(AUTHORIZATION_ALLOWED)))
    }

    /** consent-service: per-consent reads, scenario validations at priority, allow by default. */
    private fun stubConsentService(wm: WireMockServer) {
        // Priority 1 on every scenario stub — the catch-alls registered below would otherwise
        // win on last-registered-first within the same default priority.
        wm.stubFor(
            get(urlEqualTo("$CONSENTS_PATH/consent-active-e2e")).atPriority(1).willReturn(okJson(CONSENT_ACTIVE)),
        )
        wm.stubFor(
            get(urlEqualTo("$CONSENTS_PATH/consent-pending-e2e")).atPriority(1).willReturn(okJson(CONSENT_PENDING)),
        )
        wm.stubFor(
            post(urlEqualTo("$CONSENTS_PATH/consent-denied-e2e/validate")).atPriority(1)
                .willReturn(okJson(VALIDATION_DENIED)),
        )
        wm.stubFor(
            post(urlEqualTo("$CONSENTS_PATH/consent-outage-e2e/validate")).atPriority(1).willReturn(serverError()),
        )
        wm.stubFor(get(urlPathMatching("$CONSENTS_PATH/[^/]+")).willReturn(notFound()))
        wm.stubFor(post(urlPathMatching("$CONSENTS_PATH/[^/]+/validate")).willReturn(okJson(VALIDATION_ALLOWED)))
    }

    companion object {
        private const val TOKEN_PATH = "/realms/openbank/protocol/openid-connect/token"
        private const val CHECK_PATH = "/api/v1/tpp-registry/check"
        private const val CONSENTS_PATH = "/api/v1/consents"

        private const val TOKEN_RESPONSE =
            """{"access_token":"psd2-e2e-token","token_type":"Bearer","expires_in":300}"""

        private const val AUTHORIZATION_ALLOWED =
            """{"tppId":"tpp","authorized":true,"roles":["AISP","PISP"],"reason":null}"""

        private const val AUTHORIZATION_DENIED =
            """{"tppId":"tpp","authorized":false,"roles":[],"reason":"certificate revoked by supervisor"}"""

        private const val CONSENT_ACTIVE =
            """{"id":"consent-active-e2e","partyId":"party-42","status":"ACTIVE"}"""

        private const val CONSENT_PENDING =
            """{"id":"consent-pending-e2e","partyId":"party-42","status":"PENDING_SCA"}"""

        private const val VALIDATION_ALLOWED = """{"valid":true,"reason":null,"code":null}"""

        private const val VALIDATION_DENIED =
            """{"valid":false,"reason":"scope not granted to this TPP","code":"SCOPE_DENIED"}"""

        private var server: WireMockServer? = null

        /** The started server, for journal assertions from the suite. */
        fun wm(): WireMockServer = server ?: error("Psd2E2EWireMockResource not started")

        /** Journal size for a matcher — the fan-out / zero-fan-out / replay proof. */
        fun requestsTo(requestPattern: RequestPatternBuilder): Int = wm().findAll(requestPattern).count()

        fun resetRequests() {
            wm().resetRequests()
        }
    }
}
