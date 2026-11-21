// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.e2e

import com.openbank.libs.testing.containers.PostgresRedisTestResource
import com.openbank.psd2.integration.PisIdempotencyFingerprintIT
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The TPP payment journey over the real HTTP surface, on both API flavours the gateway serves:
 * initiate → status, and an idempotent replay. Ordered because the status read names the payment
 * the initiate created.
 *
 * The PIS use case is the recording fake and the TPP registry an allow-all guard — the same
 * standing the module's other integration tests use, because psd2 is the edge: its value under
 * test is everything BETWEEN those ports (JAX-RS binding of both API flavours, the TPP
 * authorization guard wiring, the Redis idempotency store, the response envelopes). A wiring
 * regression that deregisters a route (#3371), drops a required header check, or answers the
 * wrong envelope for a flavour fails here even when the use case itself is untouched.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestProfile(Psd2PaymentJourneyE2E.Profile::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_psd2_it")],
)
class Psd2PaymentJourneyE2E {

    class Profile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
            PisIdempotencyFingerprintIT.TrustXTppIdFilter.SWITCH to "true",
        )
        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(
            PisIdempotencyFingerprintIT.AllowAllTppGuard::class.java,
            PisIdempotencyFingerprintIT.RecordingPis::class.java,
        )
    }

    private val tpp = "PSDCZ-CNB-E2E-TPP"

    @Test
    @Order(1)
    fun `1 - a TPP initiates a SEPA payment over the bespoke flavour and the replay returns the same payment`() {
        val key = UUID.randomUUID().toString()
        val body = sepa()
        val first = given()
            .contentType("application/json")
            .header("X-TPP-ID", tpp)
            .header("Consent-ID", "consent-e2e-1")
            .header("Idempotency-Key", key)
            .body(body)
            .post("/open-banking/v2/payments/sepa-credit-transfers")
            .then()
            .statusCode(201)
            .body("paymentId", notNullValue())
            .body("transactionStatus", equalTo("RCVD"))
            .body("links.self", notNullValue())
            .extract()
            .path<String>("paymentId")
        bespokePaymentId = first

        given()
            .contentType("application/json")
            .header("X-TPP-ID", tpp)
            .header("Consent-ID", "consent-e2e-1")
            .header("Idempotency-Key", key)
            .body(body)
            .post("/open-banking/v2/payments/sepa-credit-transfers")
            .then()
            .statusCode(201)
            .body("paymentId", equalTo(first))
    }

    @Test
    @Order(2)
    fun `2 - the payment status is readable on both flavours`() {
        given()
            .header("X-TPP-ID", tpp)
            .header("Content-Type", "application/json")
            .get("/open-banking/v2/payments/sepa-credit-transfers/{paymentId}/status", bespokePaymentId)
            .then()
            .statusCode(200)
            .body("transactionStatus", equalTo("RCVD"))

        val berlin = given()
            .contentType("application/json")
            .header("X-TPP-ID", tpp)
            .header("Consent-ID", "consent-e2e-1")
            .header("X-Request-ID", UUID.randomUUID().toString())
            .body(sepa())
            .post("/v1/payments/sepa-credit-transfers")
            .then()
            .statusCode(201)
            .body("paymentId", notNullValue())
            .body("transactionStatus", equalTo("RCVD"))
            .extract()
            .path<String>("paymentId")

        given()
            .header("X-TPP-ID", tpp)
            .header("Content-Type", "application/json")
            .get("/v1/payments/sepa-credit-transfers/{paymentId}/status", berlin)
            .then()
            .statusCode(200)
            .body("transactionStatus", equalTo("RCVD"))
    }

    @Test
    @Order(3)
    fun `3 - the Berlin flavour replays an initiate on the same request id`() {
        val requestId = UUID.randomUUID().toString()
        val body = sepa()
        val first = given()
            .contentType("application/json")
            .header("X-TPP-ID", tpp)
            .header("Consent-ID", "consent-e2e-1")
            .header("X-Request-ID", requestId)
            .body(body)
            .post("/v1/payments/sepa-credit-transfers")
            .then()
            .statusCode(201)
            .extract()
            .path<String>("paymentId")

        given()
            .contentType("application/json")
            .header("X-TPP-ID", tpp)
            .header("Consent-ID", "consent-e2e-1")
            .header("X-Request-ID", requestId)
            .body(body)
            .post("/v1/payments/sepa-credit-transfers")
            .then()
            .statusCode(201)
            .body("paymentId", equalTo(first))
    }

    private fun sepa() = """
        {"endToEndIdentification":"e2e-${UUID.randomUUID()}",
         "debtorAccount":{"iban":"CZ6508000000192000145399","currency":"CZK"},
         "instructedAmount":{"currency":"CZK","amount":10.50},
         "creditorAccount":{"iban":"CZ1234567890123456789012","currency":"CZK"},
         "creditorName":"E2E Creditor"}
    """.trimIndent()

    private companion object {
        lateinit var bespokePaymentId: String
    }
}
