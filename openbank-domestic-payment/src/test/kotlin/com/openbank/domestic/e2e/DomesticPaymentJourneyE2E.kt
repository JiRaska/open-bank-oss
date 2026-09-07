// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.e2e

import com.openbank.domestic.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a Czech domestic credit transfer: **submit the payment, read it back,
 * find it on the debtor's payment list, retry the submission the way a client with a lost response
 * would, and ask for the confirmation document before the payment is eligible for one.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (full Flyway chain) and Valkey via `PostgresRedisTestResource`. No use case,
 * repository or domain object is touched directly and nothing is mocked out of the request path.
 *
 * ### Service boundaries — stated, not pretended
 *
 * Two hops leave this service and neither is travelled here.
 *
 *  * **Kafka dispatch.** Both outgoing channels are switched to the in-memory connector, so the
 *    payment's outbox row is written to this service's own database but nothing reaches a broker
 *    and no downstream consumer runs. The journey therefore stops at durable local state.
 *  * **document-service.** The confirmation endpoint renders through document-service's preview
 *    API. This journey deliberately asks for the confirmation of a payment that has NOT reached
 *    SETTLED, where the service's own status check rejects the request with 409 *before* any
 *    outbound call is made — so the assertion is about this service's rule, and the unreachable
 *    document-service is never consulted. The SETTLED path is not claimed.
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison below is what distinguishes "the
 * payment was accepted" from "the payment was accepted and stored as submitted" — the whitespace
 * on every field of the request is deliberate, because normalisation happening on the way IN but
 * not being persisted is exactly the defect a status-code assertion cannot see.
 */
@QuarkusTest
@QuarkusTestResource(DomesticPaymentJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class DomesticPaymentJourneyE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out", "notification-requests-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a submitted payment is stored normalised, listed for its debtor, and replays on retry`() {
        val key = "e2e-journey-${UUID.randomUUID()}"
        val debtorAccountId = UUID.randomUUID()
        val endToEndId = "E2E-JOURNEY-${UUID.randomUUID().toString().take(8)}"

        // 1. Submit.
        val created = post(key, requestBody(debtorAccountId, AMOUNT, endToEndId))
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/domestic-payments: %s", created.body.asString())
            .isEqualTo(201)
        assertThat(created.header("X-Idempotency-Replayed")).isNull()
        val paymentId = created.jsonPath().getString("id")
        assertThat(paymentId).isNotNull()

        // 2. Read it back over the committed row and check what was actually stored — the
        //    request's padded fields must have been trimmed and the currency upper-cased.
        val stored = RestAssured.given().get("/api/v1/domestic-payments/$paymentId")
        assertThat(stored.statusCode)
            .describedAs("GET /api/v1/domestic-payments/%s: %s", paymentId, stored.body.asString())
            .isEqualTo(200)
        val path = stored.jsonPath()
        assertThat(path.getString("id")).isEqualTo(paymentId)
        assertThat(path.getString("creditorAccountNumber")).isEqualTo("9876543210")
        assertThat(path.getString("creditorBankCode")).isEqualTo("0100")
        assertThat(path.getString("creditorName")).isEqualTo("Brno Utility")
        assertThat(path.getString("currency")).isEqualTo("CZK")
        assertThat(BigDecimal(path.getString("amount"))).isEqualByComparingTo(AMOUNT)
        assertThat(path.getString("status"))
            .describedAs("a freshly submitted payment is RECEIVED, not settled")
            .isEqualTo("RECEIVED")

        // 3. Discoverable on the debtor's own list — the query path, not the by-id path.
        val list = RestAssured.given()
            .queryParam("debtorAccountId", debtorAccountId.toString())
            .get("/api/v1/domestic-payments")
        assertThat(list.statusCode)
            .describedAs("GET list: %s", list.body.asString())
            .isEqualTo(200)
        assertThat(list.body.asString())
            .describedAs("the submitted payment must appear on its debtor's list")
            .contains(paymentId)

        // 4. The client's response was lost and it retries with the same key: one payment, not two.
        val replay = post(key, requestBody(debtorAccountId, AMOUNT, endToEndId))
        assertThat(replay.statusCode).isEqualTo(201)
        assertThat(replay.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replay.jsonPath().getString("id")).isEqualTo(paymentId)

        // 5. The same key with a DIFFERENT command is a client bug and must fail loudly rather
        //    than silently paying either amount.
        val changed = post(key, requestBody(debtorAccountId, AMOUNT.add(BigDecimal("0.01")), endToEndId))
        assertThat(changed.statusCode).isEqualTo(409)
        assertThat(changed.contentType).startsWith("application/problem+json")
        assertThat(changed.jsonPath().getString("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED")

        // 6. The stored payment is untouched by either follow-up call.
        val afterRetries = RestAssured.given().get("/api/v1/domestic-payments/$paymentId")
        assertThat(afterRetries.statusCode).isEqualTo(200)
        assertThat(BigDecimal(afterRetries.jsonPath().getString("amount"))).isEqualByComparingTo(AMOUNT)
        assertThat(afterRetries.jsonPath().getString("status")).isEqualTo("RECEIVED")

        // 7. A confirmation document is only available for a SETTLED payment. This one is
        //    RECEIVED, so the request is refused by this service's own rule and document-service
        //    (which is not running) is never called — see the class KDoc.
        val confirmation = RestAssured.given().get("/api/v1/domestic-payments/$paymentId/confirmation")
        assertThat(confirmation.statusCode)
            .describedAs("confirmation of a non-SETTLED payment: %s", confirmation.body.asString())
            .isEqualTo(409)
    }

    /**
     * Known-negative control for the read-back observable: an id that was never submitted must be
     * a 404, otherwise every `GET` assertion above could be passing against a stub response.
     */
    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `the payment observable answers 404 for a payment that was never submitted`() {
        assertThat(
            RestAssured.given().get("/api/v1/domestic-payments/${UUID.randomUUID()}").statusCode,
        ).isEqualTo(404)
    }

    private fun post(key: String, body: String): Response = RestAssured.given()
        .contentType("application/json")
        .header("Idempotency-Key", key)
        .body(body)
        .post("/api/v1/domestic-payments")

    /** Deliberately padded and lower-cased — step 2 asserts the stored row is normalised. */
    private fun requestBody(debtorAccountId: UUID, amount: BigDecimal, endToEndId: String): String =
        """
        {
          "debtorAccountId": "$debtorAccountId",
          "debtorAccountNumber": " 1234567890 ",
          "debtorBankCode": " 0800 ",
          "debtorName": " Alice Example ",
          "creditorAccountNumber": " 9876543210 ",
          "creditorBankCode": " 0100 ",
          "creditorName": " Brno Utility ",
          "amount": $amount,
          "currency": " czk ",
          "variableSymbol": " 2026001 ",
          "specificSymbol": null,
          "constantSymbol": " 0308 ",
          "messageForPayee": " Utility bill ",
          "priority": "STANDARD",
          "transferScope": null,
          "technicalAccountCode": null,
          "statementLabel": " Monthly settlement ",
          "endToEndId": "$endToEndId"
        }
        """.trimIndent()

    private companion object {
        const val ACTOR = "00000000-0000-0000-0000-000000000099"
        val AMOUNT: BigDecimal = BigDecimal("1500.00")
    }
}
