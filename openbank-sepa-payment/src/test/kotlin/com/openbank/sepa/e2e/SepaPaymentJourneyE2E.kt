// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sepa.integration.DocumentServiceWireMockResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journey for a SEPA credit transfer: **submit the payment, read it back, find it on
 * the debtor's payment list, replay the submission the way a client with a lost response would,
 * refuse a key reuse with a different body, walk the status chain to COMPLETED, and download the
 * confirmation document.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against a
 * real Postgres (full Flyway chain) and Valkey via `PostgresRedisTestResource`. No use case,
 * repository or domain object is touched directly and nothing is mocked out of the request path.
 *
 * ### Service boundaries — stated, not pretended
 *
 *  * **Kafka dispatch.** The outgoing `events-out` channel is switched to the in-memory connector,
 *    so the payment's outbox row is written to this service's own database but nothing reaches a
 *    broker and no downstream consumer runs. The journey stops at durable local state.
 *  * **document-service.** The confirmation endpoint renders through document-service's preview
 *    API, stubbed by [DocumentServiceWireMockResource] — the one boundary that leaves this service,
 *    and it is asserted on both sides (the 200 carries real rendered bytes, and the request the
 *    mock received contains the payment's terminal status).
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison below is what distinguishes "the
 * payment was accepted" from "the payment was accepted and stored as submitted". The idempotency
 * replay step then proves the STORED response is what a lost-response client receives, not a
 * second insert: same id, and the debtor's list still holds exactly one payment.
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sepa.it.PostgresRedisTestResource::class)
@QuarkusTestResource(DocumentServiceWireMockResource::class)
class SepaPaymentJourneyE2E {

    private val mapper = jacksonObjectMapper()

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchOutgoingChannelsToInMemory("events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a submitted sepa payment is stored, replayed, transitioned, and confirmed`() {
        val key = "e2e-journey-${UUID.randomUUID()}"
        val debtorAccountId = UUID.randomUUID()

        // 1. Submit — SCT is euro-only, so a euro payment is the load-bearing happy path.
        val created = post(key, requestBody(debtorAccountId))
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/sepa-payments: %s", created.body.asString())
            .isEqualTo(201)
        assertThat(created.header("X-Idempotency-Replayed")).isNull()
        val paymentId = created.jsonPath().getString("id")
        assertThat(paymentId).isNotNull()
        assertThat(created.jsonPath().getString("status")).isEqualTo("RECEIVED")
        assertThat(created.jsonPath().getString("currency")).isEqualTo("EUR")
        assertThat(created.jsonPath().getDouble("amount")).isEqualTo(250.00)

        // 2. Read back over the committed row — same fields, same values, no normalisation drift.
        val readBack = get("/$paymentId")
        assertThat(readBack.statusCode)
            .describedAs("GET /api/v1/sepa-payments/{id}: %s", readBack.body.asString())
            .isEqualTo(200)
        assertThat(readBack.jsonPath().getString("id")).isEqualTo(paymentId)
        assertThat(readBack.jsonPath().getString("status")).isEqualTo("RECEIVED")
        assertThat(readBack.jsonPath().getString("debtorAccountId")).isEqualTo(debtorAccountId.toString())
        assertThat(readBack.jsonPath().getString("debtorIban")).isEqualTo(DEBTOR_IBAN)
        assertThat(readBack.jsonPath().getString("creditorIban")).isEqualTo(CREDITOR_IBAN)
        assertThat(readBack.jsonPath().getDouble("amount")).isEqualTo(250.00)
        assertThat(readBack.jsonPath().getString("currency")).isEqualTo("EUR")
        assertThat(readBack.jsonPath().getString("endToEndId")).isEqualTo(END_TO_END_ID)

        // 3. Listed for its debtor — the endpoint answers a BARE array, so read the root node.
        val listed = get("?debtorAccountId=$debtorAccountId")
        assertThat(listed.statusCode).isEqualTo(200)
        val listedIds = mapper.readTree(listed.body.asString()).map { it.get("id").asText() }
        assertThat(listedIds).containsExactly(paymentId)

        // 4. Replay — a client that lost the response resubmits the same key and body: the STORED
        // answer comes back, marked replayed, and no second payment exists.
        val replayed = post(key, requestBody(debtorAccountId))
        assertThat(replayed.statusCode).isEqualTo(201)
        assertThat(replayed.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(replayed.jsonPath().getString("id")).isEqualTo(paymentId)
        val afterReplay = get("?debtorAccountId=$debtorAccountId")
        val afterReplayIds = mapper.readTree(afterReplay.body.asString()).map { it.get("id").asText() }
        assertThat(afterReplayIds).containsExactly(paymentId)

        // 5. Key reuse with a DIFFERENT body is a 409, not a silent replay of the original.
        val mismatched = post(key, requestBody(debtorAccountId, amount = "300.00"))
        assertThat(mismatched.statusCode).isEqualTo(409)

        // 6. Confirmation before COMPLETED is refused by THIS service's rule (409), before any
        // document-service call — the journey asserts the seam, not the stub.
        val earlyConfirmation = get("/$paymentId/confirmation")
        assertThat(earlyConfirmation.statusCode).isEqualTo(409)

        // 7. Walk the status chain the operator serves: RECEIVED -> VALIDATED -> PROCESSING ->
        // COMPLETED, reading the row back at the end of the chain.
        assertThat(patchStatus(paymentId, "VALIDATED").statusCode).isEqualTo(200)
        assertThat(patchStatus(paymentId, "PROCESSING").statusCode).isEqualTo(200)
        val completed = patchStatus(paymentId, "COMPLETED")
        assertThat(completed.statusCode).isEqualTo(200)
        assertThat(completed.jsonPath().getString("status")).isEqualTo("COMPLETED")
        assertThat(get("/$paymentId").jsonPath().getString("status")).isEqualTo("COMPLETED")

        // 8. The COMPLETED payment downloads a real rendered confirmation across the one boundary
        // that leaves this service — document-service preview, stubbed but asserted both ways.
        DocumentServiceWireMockResource.stubPublishedTemplate()
        val confirmation = get("/$paymentId/confirmation")
        assertThat(confirmation.statusCode)
            .describedAs("GET confirmation: %s", confirmation.body.asString())
            .isEqualTo(200)
        assertThat(confirmation.header("Content-Disposition")).contains("attachment")
        assertThat(confirmation.body.asByteArray()).isNotEmpty()
        DocumentServiceWireMockResource.verifyPreviewRequestContained("COMPLETED")
    }

    private fun requestBody(debtorAccountId: UUID, amount: String = "250.00"): String =
        """
        {
          "type": "SCT",
          "debtorAccountId": "$debtorAccountId",
          "debtorIban": "$DEBTOR_IBAN",
          "debtorName": "E2E Debtor s.r.o.",
          "creditorIban": "$CREDITOR_IBAN",
          "creditorName": "E2E Creditor GmbH",
          "creditorBic": null,
          "amount": $amount,
          "currency": "EUR",
          "remittanceInfo": "E2E journey remittance",
          "endToEndId": "$END_TO_END_ID"
        }
        """.trimIndent()

    private fun post(idempotencyKey: String, body: String): Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(body)
        .post("/api/v1/sepa-payments")
        .then()
        .extract()
        .response()

    private fun get(path: String): Response = RestAssured.given()
        .get("/api/v1/sepa-payments$path")
        .then()
        .extract()
        .response()

    private fun patchStatus(paymentId: String, targetStatus: String): Response = RestAssured.given()
        .contentType(ContentType.JSON)
        .body("""{"targetStatus": "$targetStatus"}""")
        .patch("/api/v1/sepa-payments/$paymentId/status")
        .then()
        .extract()
        .response()

    private companion object {
        private const val ACTOR = "e2e-operator-1"
        private const val DEBTOR_IBAN = "DE89370400440532013000"
        private const val CREDITOR_IBAN = "FR1420041010050500013M02606"
        private const val END_TO_END_ID = "E2E-JOURNEY-SCT-0001"
    }
}
