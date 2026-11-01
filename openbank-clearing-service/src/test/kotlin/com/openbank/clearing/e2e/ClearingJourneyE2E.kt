// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a SEPA credit transfer through clearing: **submit the payment, replay
 * the submission the way a client with a lost response would, watch it enter a triggered cycle,
 * read the batch back over a different code path, settle it, and find the settlement position.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against
 * a real Postgres (full Flyway chain), Redpanda and Valkey via
 * `PostgresRedpandaRedisTestResource`. No use case, repository or domain object is touched
 * directly and nothing is mocked out of the request path.
 *
 * ### Service boundaries — stated, not pretended
 *
 * One hop leaves this service: the settlement journal post is issued by the settlement port,
 * which resolves to the service's test stub because no ledger counterpart runs in a
 * `@QuarkusTest`. The journey therefore asserts everything up to the outbound port invocation
 * and the durable local state it leaves behind.
 *
 * ### Why the read-back is the observable
 *
 * `POST /submit` returns the item the handler just built in memory. The `GET` paths
 * (`/batches/{id}`, `/batches/{id}/items`, `/items/by-payment/{paymentId}`) are different code
 * paths over the committed rows, so the assertions below are what distinguish "the payment was
 * accepted" from "the payment was accepted and stored as submitted" — and the item cardinality
 * after the idempotent retry is what distinguishes "the client retry was deduplicated" from
 * "the retry stacked a second PENDING row the cycle would have settled twice".
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_clearing_e2e")],
)
class ClearingJourneyE2E {

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `a submitted payment is replayed on retry, cycled, settled, and positioned`() {
        val paymentId = UUID.randomUUID()
        val submitted = submit(paymentId, "100.00", "EUR", 201)
        val itemId = submitted.path("id").asText()

        // ADR-0298 idempotent replay: a retried POST with the same paymentId must resolve to the
        // SAME item, not stack a second PENDING row.
        val replay = submit(paymentId, "100.00", "EUR", 201)
        assertThat(replay.path("id").asText()).isEqualTo(itemId)

        val cycle = triggerCycle()
        val cycleId = cycle.path("cycleId").asText()
        val batchId = cycle.path("batches").first().path("id").asText()

        assertBatchReadBack(batchId, cycleId)
        assertItemInBatch(batchId, paymentId, itemId)
        assertSingleItemForPayment(paymentId, itemId)

        settle(batchId)
        assertSettledReadBack(batchId)
        assertPositionsEndpointAnswers(cycleId)
    }

    @Test
    @TestSecurity(user = USER, roles = ["ROLE_PAYMENTS"])
    fun `a payment Money cannot hold is rejected without leaving a clearing item`() {
        val paymentId = UUID.randomUUID()
        submit(paymentId, "100.999", "EUR", 400)

        // #11604: the invalid amount is a boundary 400 BEFORE any row is written, so the
        // paymentId stays free — and no phantom item appears for it afterwards.
        val items = get("/api/v1/clearing/items/by-payment/$paymentId", 200)
        assertThat(items).isEmpty()
    }

    private fun assertBatchReadBack(batchId: String, cycleId: String) {
        val batch = getOne("/api/v1/clearing/batches/$batchId", 200)
        // A freshly cycled batch reads back IN_CLEARING (open, awaiting settlement) — the cycle
        // moved the item out of PENDING the moment it entered the batch.
        assertThat(batch.path("status").asText()).isEqualTo("IN_CLEARING")
        assertThat(batch.path("cycleId").asText()).isEqualTo(cycleId)
        assertThat(batch.path("currency").asText()).isEqualTo("EUR")
        assertThat(batch.path("totalDebit").decimalValue()).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(batch.path("itemCount").asInt()).isEqualTo(1)
    }

    private fun assertItemInBatch(batchId: String, paymentId: UUID, itemId: String) {
        val items = get("/api/v1/clearing/batches/$batchId/items", 200)
        assertThat(items).hasSize(1)
        val item = items.first()
        assertThat(item.path("id").asText()).isEqualTo(itemId)
        assertThat(item.path("paymentId").asText()).isEqualTo(paymentId.toString())
        assertThat(item.path("amount").decimalValue()).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(item.path("status").asText()).isEqualTo("IN_CLEARING")
    }

    private fun assertSingleItemForPayment(paymentId: UUID, itemId: String) {
        val items = get("/api/v1/clearing/items/by-payment/$paymentId", 200)
        assertThat(items.map { it.path("id").asText() })
            .describedAs("the idempotent retry must not stack a second item for the same payment")
            .containsExactly(itemId)
    }

    private fun assertSettledReadBack(batchId: String) {
        val batch = getOne("/api/v1/clearing/batches/$batchId", 200)
        assertThat(batch.path("status").asText()).isEqualTo("SETTLED")
        assertThat(batch.path("settledAt").isTextual).isTrue()
    }

    private fun assertPositionsEndpointAnswers(cycleId: String) {
        // Per-participant position netting is NOT wired into the cycle yet (documented in
        // ClearingApiIT: the endpoint must answer 200 with an empty list, never an error). The
        // journey pins that contract: the read path exists for the cycle id the trigger returned.
        val positions = get("/api/v1/clearing/positions/$cycleId", 200)
        assertThat(positions).isEmpty()
    }

    private fun submit(paymentId: UUID, amount: String, currency: String, expectedStatus: Int): JsonNode {
        val response: Response = Given {
            contentType("application/json")
            body(
                """
                {
                  "paymentId": "$paymentId",
                  "paymentReference": "PAY-E2E-${paymentId.toString().take(8)}",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIban": "DE89370400440532013000",
                  "amount": "$amount",
                  "currency": "$currency",
                  "rail": "SEPA_SCT"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/clearing/submit")
        }
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        return mapper.readTree(response.body.asString())
    }

    private fun triggerCycle(): JsonNode = post("/api/v1/clearing/cycle/trigger?rail=SEPA_SCT", 200)

    private fun settle(batchId: String) {
        post("/api/v1/clearing/batches/$batchId/settle", 200)
    }

    private fun post(path: String, expectedStatus: Int): JsonNode {
        val response: Response = Given { contentType("application/json") } When {
            post(path)
        }
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        return mapper.readTree(response.body.asString())
    }

    private fun get(path: String, expectedStatus: Int): List<JsonNode> {
        val response: Response = Given { contentType("application/json") } When {
            get(path)
        }
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        return mapper.readTree(response.body.asString()).toList()
    }

    private fun getOne(path: String, expectedStatus: Int): JsonNode {
        val response: Response = Given { contentType("application/json") } When {
            get(path)
        }
        assertThat(response.statusCode()).isEqualTo(expectedStatus)
        return mapper.readTree(response.body.asString())
    }

    private companion object {
        const val USER = "00000000-0000-0000-0000-000000000099"
    }
}
