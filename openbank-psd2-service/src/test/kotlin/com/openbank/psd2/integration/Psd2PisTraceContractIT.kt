// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.libs.testing.trace.RecordingSpanExporter
import com.openbank.libs.testing.trace.TraceContractProfile
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID

/**
 * The observable distributed shape of a PSD2 payment initiation.
 *
 * psd2 is a gateway: V5 dropped its last own table (`psd2_outbox`), so unlike the other money-path
 * services there is no local INSERT to put in the same trace as the request. Its money-path write
 * is delegated — `PaymentInitiationService.initiatePayment` must first pass the consent gate
 * (`ResilientConsentServiceClient` → `RestConsentServiceClient` → real HTTP `POST
 * /api/v1/consents/{id}/validate` on consent-service, M2M bearer from `OidcClientRequestReactiveFilter`)
 * before `TransactionServiceClient.initiatePayment` hands the instruction downstream. The
 * load-bearing evidence here is therefore *cross-service context propagation*: the consent
 * validation call the request's security depends on must happen INSIDE the request's own trace. A
 * validation that escaped the request context — a reactive hop that dropped the OTel context, an
 * executor boundary — would still return 201, still validate the consent, and land in a trace no
 * operator can correlate with the payment instruction that depended on it.
 *
 * Both legs of that hop run against a zero-dependency JDK [HttpServer] stub (this class's
 * [ConsentAndTokenStubResource]): consent-service's validate endpoint answers `{"valid":true}` and
 * the oidc-client token endpoint answers a client-credentials token, so the production client
 * stack — MP REST client, oidc filter, fault-tolerance wrapper — is exercised unmodified. Nothing
 * asserted here is created by the test, so every assertion can fail.
 *
 * This module had zero trace evidence before this test (the evidence matrix lists trace as a gap
 * on every money-path service without one); payment initiation is this service's most important
 * gate, so it is the right first contract here.
 */
@QuarkusTest
@TestProfile(Psd2PisTraceContractIT.Profile::class)
@QuarkusTestResource(
    PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_trace_it")],
)
@QuarkusTestResource(Psd2PisTraceContractIT.ConsentAndTokenStubResource::class)
class Psd2PisTraceContractIT {

    /**
     * The libs baseline gives this class its own boot (own recorder). The additions are
     * psd2-specific:
     *
     *  - `authz.enforce=false`: the `@Authorize` annotations on [PisResource] skip the OPA call.
     *  - the trust-x-tpp-id switch: the production `EidasMtlsFilter` never sets the `tppId`
     *    request property in tests (separate defect documented on
     *    [PisIdempotencyFingerprintIT.TrustXTppIdFilter]), so the test filter stands in for it;
     *    without a `tppId` the resource itself answers 401 before the use case runs.
     *  - port 0: this profile's own boot re-binds the fixed default test port 8081 before the
     *    previous instance has released it (`QuarkusBindException` — measured in billing and
     *    sepa-payment's trace contracts); port 0 lets Quarkus pick a free one and rewrites the
     *    property so RestAssured still finds it.
     */
    class Profile : TraceContractProfile() {
        override fun extraConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
            PisIdempotencyFingerprintIT.TrustXTppIdFilter.SWITCH to "true",
            "quarkus.http.test-port" to "0",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> =
            mutableSetOf(PisIdempotencyFingerprintIT.AllowAllTppGuard::class.java)
    }

    /**
     * Stands in for consent-service (validate endpoint) and Keycloak (client-credentials token
     * endpoint) on one loopback port. Both endpoints answer 200 immediately: the
     * `ResilientConsentServiceClient` retry/circuit-breaker then takes a single happy path, and
     * `hasNoErrorSpan()` asserts about the real flow rather than about fallback noise. The URLs
     * are wired through the same config keys the deployed pod uses, so no production code path is
     * bypassed.
     */
    class ConsentAndTokenStubResource : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            http.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val base = "http://localhost:${http.address.port}"
                val (status, json) = when {
                    path.endsWith("/.well-known/openid-configuration") ->
                        HTTP_OK to (
                            """{"issuer":"$base/realms/openbank",""" +
                                """"token_endpoint":"$base/realms/openbank/protocol/openid-connect/token"}"""
                            )
                    path.endsWith("/protocol/openid-connect/token") ->
                        HTTP_OK to """{"access_token":"stub-m2m-token","token_type":"Bearer","expires_in":300}"""
                    path.contains("/api/v1/consents/") && path.endsWith("/validate") ->
                        HTTP_OK to """{"valid":true,"reason":null,"code":null}"""
                    else -> HTTP_NOT_FOUND to """{"error":"unexpected","path":"$path"}"""
                }
                val bytes = json.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            http.start()
            server = http
            val base = "http://localhost:${http.address.port}"
            return mapOf(
                "quarkus.rest-client.consent-service.url" to base,
                "quarkus.oidc-client.auth-server-url" to "$base/realms/openbank",
            )
        }

        override fun stop() {
            server?.stop(0)
        }

        private companion object {
            const val HTTP_OK = 200
            const val HTTP_NOT_FOUND = 404
        }
    }

    @Inject
    lateinit var exporter: RecordingSpanExporter

    @Test
    fun `domestic payment initiation validates consent inside the request trace`() {
        given()
            .contentType("application/json")
            .header("X-TPP-ID", "PSDCZ-CNB-IT-TPP")
            .header("Consent-ID", UUID.randomUUID().toString())
            .header("Idempotency-Key", "trace-it-${UUID.randomUUID()}")
            .body(
                """
                {"endToEndIdentification":"e2e-trace",
                 "debtorAccount":{"iban":"CZ6508000000192000145399"},
                 "instructedAmount":{"currency":"CZK","amount":10.00},
                 "creditorAccount":{"iban":"CZ1234567890123456789012"},
                 "creditorName":"Acme CZ","variableSymbol":"123"}
                """.trimIndent(),
            )
            .post("/open-banking/v2/payments/domestic-cz")
            .then()
            .statusCode(201)
            .body("paymentId", notNullValue())

        exporter.contract()
            .requiresSpan("POST /open-banking/v2/payments/domestic-cz")
            .requiresAttribute("POST /open-banking/v2/payments/domestic-cz", "http.response.status_code")
            .requiresSpan("POST /api/v1/consents/{id}/validate")
            .requiresAttribute("POST /api/v1/consents/{id}/validate", "http.response.status_code")
            .requiresSameTrace(
                "POST /open-banking/v2/payments/domestic-cz",
                "POST /api/v1/consents/{id}/validate",
            )
            .hasNoErrorSpan()
            .verifiedAs("psd2-pis-consent-validate")
    }
}
