// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.integration

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.psd2.application.port.`in`.GetPaymentStatusQuery
import com.openbank.psd2.application.port.`in`.InitiatePaymentCommand
import com.openbank.psd2.application.port.`in`.PaymentInitiationUseCase
import com.openbank.psd2.domain.model.DomesticCzPayment
import com.openbank.psd2.domain.model.ObLinks
import com.openbank.psd2.domain.model.PaymentInitiationResponse
import com.openbank.psd2.domain.model.PaymentProduct
import com.openbank.psd2.domain.model.PaymentStatus
import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import com.openbank.psd2.infrastructure.client.TppAuthorizationResponse
import com.openbank.psd2.infrastructure.client.TppRegistryRestClient
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * #10916 on the PSD2 PIS path, over REAL HTTP against a real Redis (Valkey) idempotency store:
 * an idempotency key replays only the request it was first used for.
 *
 * The use case is a recording fake (so "executed twice" is countable) and the TPP registry is an
 * allow-all guard; everything between them — JAX-RS binding, [com.openbank.libs.idempotency
 * .RequestFingerprints], `RedisIdempotencyStore.reserve/save/release` and the psd2 conflict
 * envelope — is the production code.
 *
 * psd2 persists no payment initiation itself (no table, no unique key — V5 dropped the last
 * table), so there is no durable second dedupe layer here to test; Redis is the only one.
 */
@QuarkusTest
@TestProfile(PisIdempotencyFingerprintIT.Profile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class PisIdempotencyFingerprintIT {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("authz.enforce" to "false", TrustXTppIdFilter.SWITCH to "true")
        override fun getEnabledAlternatives(): MutableSet<Class<*>> =
            mutableSetOf(AllowAllTppGuard::class.java, RecordingPis::class.java)
    }

    /**
     * Stands in for [com.openbank.psd2.infrastructure.rest.filter.EidasMtlsFilter], which under a
     * running Quarkus app never sets `tppId`: its gate compares `uriInfo.path` against
     * `open-banking/` / `v1/` with no leading slash, while RESTEasy Reactive reports the path WITH
     * one (measured by this IT: a request carrying `X-TPP-ID` and an allow-all guard still reached
     * the resource with no `tppId` and got the resource's own 401). That is a separate defect,
     * reported outside this PR; this filter is inert unless this profile's switch is on.
     */
    @Provider
    @Priority(Priorities.AUTHENTICATION + 1)
    class TrustXTppIdFilter : ContainerRequestFilter {
        override fun filter(ctx: ContainerRequestContext) {
            val on = ConfigProvider.getConfig().getOptionalValue(SWITCH, Boolean::class.java).orElse(false)
            if (on &&
                ctx.getProperty("tppId") == null
            ) {
                ctx.getHeaderString("X-TPP-ID")?.let { ctx.setProperty("tppId", it) }
            }
        }

        companion object {
            const val SWITCH = "openbank.psd2.it.trust-x-tpp-id"
        }
    }

    @Alternative
    @ApplicationScoped
    class AllowAllTppGuard :
        TppAuthorizationGuard(
            object : TppRegistryRestClient {
                override fun checkAuthorization(tppId: String, role: String) =
                    TppAuthorizationResponse(tppId, true, setOf(role), null)
            },
        ) {
        override fun requireAuthorized(tppId: String, role: String) =
            TppAuthorizationResponse(tppId, true, setOf(role), null)
    }

    @Alternative
    @ApplicationScoped
    class RecordingPis : PaymentInitiationUseCase {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val domesticCurrencies = ConcurrentHashMap<String, String>()
        val failuresFor = ConcurrentHashMap.newKeySet<String>()

        override suspend fun initiatePayment(command: InitiatePaymentCommand): PaymentInitiationResponse {
            calls.computeIfAbsent(command.idempotencyKey) { AtomicInteger() }.incrementAndGet()
            if (command.product == PaymentProduct.DOMESTIC_CZ) {
                domesticCurrencies[command.idempotencyKey] =
                    (command.payment as DomesticCzPayment).instructedAmount.currency
            }
            if (failuresFor.remove(command.idempotencyKey)) error("downstream failure")
            val id = UUID.randomUUID().toString()
            return PaymentInitiationResponse(id, PaymentStatus.RCVD, "received", ObLinks(self = "/p/$id"))
        }

        override suspend fun getPaymentStatus(query: GetPaymentStatusQuery) = PaymentStatus.RCVD

        fun count(key: String) = calls[key]?.get() ?: 0
    }

    @Inject
    lateinit var pis: RecordingPis

    private val tpp = "PSDCZ-CNB-IT-TPP"

    private fun sepa(amount: String = "10.50", creditor: String = "Acme") = """
        {"endToEndIdentification":"e2e-1",
         "debtorAccount":{"iban":"CZ6508000000192000145399","currency":"CZK"},
         "instructedAmount":{"currency":"CZK","amount":$amount},
         "creditorAccount":{"iban":"CZ1234567890123456789012","currency":"CZK"},
         "creditorName":"$creditor"}
    """.trimIndent()

    private fun domestic(currency: String) = """
        {"endToEndIdentification":"e2e-cz",
         "debtorAccount":{"iban":"CZ6508000000192000145399"},
         "instructedAmount":{"currency":"$currency","amount":10.00},
         "creditorAccount":{"iban":"CZ1234567890123456789012"},
         "creditorName":"Acme CZ","variableSymbol":"123"}
    """.trimIndent()

    private fun bespoke(key: String, body: String, consent: String = "consent-1") = given()
        .contentType("application/json")
        .header("X-TPP-ID", tpp)
        .header("Consent-ID", consent)
        .header("Idempotency-Key", key)
        .body(body)
        .post("/open-banking/v2/payments/sepa-credit-transfers")

    private fun berlin(requestId: String, body: String, consent: String = "consent-1") = given()
        .contentType("application/json")
        .header("X-TPP-ID", tpp)
        .header("Consent-ID", consent)
        .header("X-Request-ID", requestId)
        .body(body)
        .post("/v1/payments/sepa-credit-transfers")

    private fun bespokeDomestic(key: String, body: String) = given()
        .contentType("application/json")
        .header("X-TPP-ID", tpp)
        .header("Consent-ID", "consent-1")
        .header("Idempotency-Key", key)
        .body(body)
        .post("/open-banking/v2/payments/domestic-cz")

    private fun berlinDomestic(requestId: String, body: String) = given()
        .contentType("application/json")
        .header("X-TPP-ID", tpp)
        .header("Consent-ID", "consent-1")
        .header("X-Request-ID", requestId)
        .body(body)
        .post("/v1/payments/domestic-cz")

    @Test
    fun `bespoke domestic rejects EUR without reserving key and accepts corrected CZK retry`() {
        val key = UUID.randomUUID().toString()
        bespokeDomestic(key, domestic("EUR")).then().statusCode(400)
            .body("tppMessages[0].code", equalTo("FORMAT_ERROR"))
        assertThat(pis.count(key)).isZero()

        bespokeDomestic(key, domestic(" czk ")).then().statusCode(201)
        assertThat(pis.count(key)).isEqualTo(1)
        assertThat(pis.domesticCurrencies[key]).isEqualTo("CZK")
    }

    @Test
    fun `Berlin domestic rejects EUR without reserving request ID and accepts corrected CZK retry`() {
        val requestId = UUID.randomUUID().toString()
        berlinDomestic(requestId, domestic("EUR")).then().statusCode(400)
            .body("tppMessages[0].code", equalTo("FORMAT_ERROR"))
        assertThat(pis.count(requestId)).isZero()

        berlinDomestic(requestId, domestic(" czk ")).then().statusCode(201)
        assertThat(pis.count(requestId)).isEqualTo(1)
        assertThat(pis.domesticCurrencies[requestId]).isEqualTo("CZK")
    }

    @Test
    fun `same key and same body replays the first response and executes once`() {
        val key = UUID.randomUUID().toString()
        val first = bespoke(key, sepa()).then().statusCode(201).extract().path<String>("paymentId")
        bespoke(key, sepa()).then().statusCode(201)
            .header("X-Idempotency-Replayed", "true")
            .body("paymentId", equalTo(first))
        assertThat(pis.count(key)).isEqualTo(1)
    }

    @Test
    fun `same key with a different amount is 409 IDEMPOTENCY_KEY_REUSED and executes nothing`() {
        val key = UUID.randomUUID().toString()
        bespoke(key, sepa(amount = "10.50")).then().statusCode(201)
        bespoke(key, sepa(amount = "9999.00")).then().statusCode(409)
            .body("tppMessages[0].code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        assertThat(pis.count(key)).isEqualTo(1)
    }

    @Test
    fun `same key with a different Consent-ID is 409`() {
        val key = UUID.randomUUID().toString()
        bespoke(key, sepa(), consent = "consent-1").then().statusCode(201)
        bespoke(key, sepa(), consent = "consent-2").then().statusCode(409)
            .body("tppMessages[0].code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        assertThat(pis.count(key)).isEqualTo(1)
    }

    @Test
    fun `reordered keys, whitespace and 10_5 vs 10_50 are the same request`() {
        val key = UUID.randomUUID().toString()
        bespoke(key, sepa(amount = "10.50")).then().statusCode(201)
        val reordered = """
            {  "creditorName" : "Acme", "instructedAmount":{"amount":10.5,"currency":"CZK"},
               "creditorAccount":{"currency":"CZK","iban":"CZ1234567890123456789012"},
               "debtorAccount":{"currency":"CZK","iban":"CZ6508000000192000145399"},
               "endToEndIdentification":"e2e-1" }
        """.trimIndent()
        bespoke(key, reordered).then().statusCode(201).header("X-Idempotency-Replayed", "true")
        assertThat(pis.count(key)).isEqualTo(1)
    }

    @Test
    fun `a failed initiation releases the key so the same request can be retried`() {
        val key = UUID.randomUUID().toString()
        pis.failuresFor.add(key)
        bespoke(key, sepa()).then().statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(400))
        bespoke(key, sepa()).then().statusCode(201).header("X-Idempotency-Replayed", org.hamcrest.Matchers.nullValue())
        assertThat(pis.count(key)).isEqualTo(2)
    }

    @Test
    fun `Berlin Group X-Request-ID reused for a different creditor is 409 and echoes X-Request-ID`() {
        val requestId = UUID.randomUUID().toString()
        berlin(requestId, sepa()).then().statusCode(201)
        berlin(requestId, sepa(creditor = "Mallory")).then().statusCode(409)
            .header("X-Request-ID", requestId)
            .body("tppMessages[0].code", equalTo("IDEMPOTENCY_KEY_REUSED"))
        berlin(requestId, sepa()).then().statusCode(201).header("X-Idempotency-Replayed", "true")
        assertThat(pis.count(requestId)).isEqualTo(1)
    }
}
