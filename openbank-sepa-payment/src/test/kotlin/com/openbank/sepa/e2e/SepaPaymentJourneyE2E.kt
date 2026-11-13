// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.e2e

import com.openbank.sepa.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.restassured.response.Response
import io.restassured.response.ValidatableResponse
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journey for a SEPA credit transfer on this service's own surface: **create the
 * payment, read it back over a different code path, find it on the debtor's list, retry the
 * submission the way a client with a lost response would, collide on the idempotency key on
 * purpose, then walk the operator lane of the state machine to COMPLETED and watch the terminal
 * rule refuse any further move.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against
 * real Postgres (full Flyway chain) and Valkey via `PostgresRedisTestResource`, with the outbox
 * on the in-memory connector. No use case, repository or domain object is touched directly.
 *
 * ### Service boundaries — stated, not pretended
 *
 * Orchestration is Temporal's since ADR-0120 Phase 6: creation fires the
 * `SepaPaymentWorkflow` (screening → validation → shadow fraud scoring → scheme submission →
 * settle). In `@QuarkusTest` the workflow client resolves to an in-process
 * `TestWorkflowEnvironment` running a no-op workflow, so the payment stays RECEIVED and the
 * screening/fraud/scheme hops never leave the process — the journey below exercises exactly the
 * surface this service owns: the REST API, the idempotency reservation, the state machine and
 * the durable rows. The workflow's own hops are covered by `SchemeGatewaySimulatorIT` (real
 * adapter over stubbed scheme) and the workflow unit tests; they are not claimed here.
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison distinguishes "accepted" from
 * "accepted and stored as submitted". The debtor-list cardinality after the retry distinguishes
 * "the retry was deduplicated" from "the retry inserted a second payment" — and the 409 on a
 * same-key-different-body distinguishes "the key is a fingerprint" from "the key is a nonce".
 */
@QuarkusTest
@QuarkusTestResource(SepaPaymentJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class SepaPaymentJourneyE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    private data class JourneyPayment(
        val paymentId: String,
        val debtorAccountId: UUID,
        val payload: String,
        val submitted: Map<String, Any>,
        val idempotencyKey: String,
    )

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a submitted SCT is stored, replayed on retry, walks the operator lane to COMPLETED and stops there`() {
        val payment = submitFreshPayment()

        // The committed row agrees with what the handler returned in memory — over a
        // different code path (GET by id), which is what makes this storage evidence.
        val stored = getById(payment.paymentId)
        for (field in STORED_FIELDS) {
            assertThat(stored[field])
                .`as`("stored payment field %s must equal the submitted one", field)
                .isEqualTo(payment.submitted[field])
        }

        // The payment is on its debtor's list, exactly once.
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)

        replayDoesNotDuplicate(payment)
        sameKeyDifferentBodyIsRefused(payment)

        // Operator lane of the state machine, each hop over HTTP with a read-back: the domain
        // table allows RECEIVED -> VALIDATED -> PROCESSING -> COMPLETED and nothing else.
        transitionIs(payment.paymentId, "VALIDATED")
        transitionIs(payment.paymentId, "PROCESSING")
        val completed = transitionIs(payment.paymentId, "COMPLETED")
        assertThat(completed["completedAt"]).isNotNull()
        assertThat(completed["submittedAt"]).isNotNull()

        // Terminal rule: COMPLETED admits only a RETURNED carrying a proven return intent, so a
        // plain operator move to REJECTED must be refused as a conflict, not applied.
        transitionIsRefused(payment.paymentId, "REJECTED")
        assertThat((getById(payment.paymentId)["status"] as String)).isEqualTo("COMPLETED")
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `the key is a fingerprint, the amount scale is enforced before reservation, CANCELLED is terminal`() {
        // An absent Idempotency-Key header is a 400, not a 500 — the exact case the #3104
        // nullable-param guard exists for.
        val debtor = UUID.randomUUID()
        Given {
            contentType("application/json")
            body(payload(debtor, AMOUNT))
        } When {
            post(PAYMENTS_PATH)
        } Then {
            statusCode(400)
        }
        assertThat(debtorList(debtor)).isEmpty()

        // An amount the money kernel cannot hold is refused BEFORE the key is reserved (#11642):
        // the 400 leaves nothing behind and the SAME key remains free for the corrected request.
        val key = "sct-scale-${UUID.randomUUID()}"
        submit(payload(debtor, UNSCALED_AMOUNT), key).then().statusCode(400)
        assertThat(debtorList(debtor)).isEmpty()
        val corrected = submit(payload(debtor, AMOUNT), key).then().statusCode(201).extractedBody()
        assertThat(corrected["id"]).isNotNull()
        assertThat(corrected["status"] as String).isEqualTo("RECEIVED")
        assertThat(debtorList(debtor)).containsExactly(corrected["id"] as String)

        // Direct RECEIVED -> COMPLETED is not on the operator lane; the attempt changes nothing.
        val direct = submit(payload(UUID.randomUUID(), AMOUNT), "sct-direct-${UUID.randomUUID()}")
            .then().statusCode(201).extractedBody()
        transitionIsRefused(direct["id"] as String, "COMPLETED")
        assertThat((getById(direct["id"] as String)["status"] as String)).isEqualTo("RECEIVED")

        // Cancellation is an allowed hop and it is terminal: completedAt is stamped and any
        // further move is a conflict.
        val cancelled = transitionIs(direct["id"] as String, "CANCELLED")
        assertThat(cancelled["completedAt"]).isNotNull()
        transitionIsRefused(direct["id"] as String, "VALIDATED")
        assertThat((getById(direct["id"] as String)["status"] as String)).isEqualTo("CANCELLED")
    }

    private fun submitFreshPayment(): JourneyPayment {
        val idempotencyKey = "sct-${UUID.randomUUID()}"
        val debtorAccountId = UUID.randomUUID()
        val endToEndId = "E2E-SCT-${UUID.randomUUID().toString().take(8)}"
        val body = payload(debtorAccountId, AMOUNT, endToEndId)

        val created = submit(body, idempotencyKey).then().statusCode(201).extractedBody()
        val paymentId = created["id"] as String
        assertThat(paymentId).isNotBlank()
        // Arrangement assertion: the Temporal no-op workflow leaves the payment RECEIVED, which
        // is the state every transition below starts from. A change that wires real
        // orchestration into @QuarkusTest would move this — loudly.
        assertThat(created["status"] as String).isEqualTo("RECEIVED")
        assertThat(created["endToEndId"] as String).isEqualTo(endToEndId)
        return JourneyPayment(paymentId, debtorAccountId, body, created, idempotencyKey)
    }

    private fun replayDoesNotDuplicate(payment: JourneyPayment) {
        val replay = submit(payment.payload, payment.idempotencyKey).then().statusCode(201)
        assertThat(replay.extract().header("X-Idempotency-Replayed")).isEqualTo("true")
        val replayed = replay.extractedBody()
        assertThat(replayed["id"] as String).isEqualTo(payment.paymentId)
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)
    }

    private fun sameKeyDifferentBodyIsRefused(payment: JourneyPayment) {
        // Same key, different fingerprint: 409 IDEMPOTENCY_KEY_REUSED (#10916) and still one row.
        submit(payload(payment.debtorAccountId, DIFFERENT_AMOUNT), payment.idempotencyKey)
            .then().statusCode(409)
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)
    }

    private fun transitionIs(paymentId: String, target: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
            body("""{"targetStatus": "$target"}""")
        } When {
            patch("$PAYMENTS_PATH/$paymentId/status")
        } Then {
            statusCode(200)
        }
        val body = response.extractedBody()
        assertThat(body["status"] as String)
            .describedAs("transition target must be applied")
            .isEqualTo(target)
        return body
    }

    private fun transitionIsRefused(paymentId: String, target: String) {
        Given {
            contentType("application/json")
            body("""{"targetStatus": "$target"}""")
        } When {
            patch("$PAYMENTS_PATH/$paymentId/status")
        } Then {
            statusCode(409)
        }
    }

    private fun submit(payload: String, key: String?): Response {
        val request = Given {
            contentType("application/json")
            body(payload)
        }
        if (key != null) {
            request.header("Idempotency-Key", key)
        }
        return request.When {
            post(PAYMENTS_PATH)
        }
    }

    private fun getById(paymentId: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$PAYMENTS_PATH/$paymentId")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun debtorList(debtorAccountId: UUID): List<String> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$PAYMENTS_PATH?debtorAccountId=$debtorAccountId")
        } Then {
            statusCode(200)
        }
        return response.extractList("id")
    }

    private fun payload(debtor: UUID, amount: String, endToEndId: String? = null): String = """
        {
          "type": "SCT",
          "debtorAccountId": "$debtor",
          "debtorIban": "CZ6508000000192000145399",
          "debtorName": "Alice Example",
          "creditorIban": "DE89370400440532013000",
          "creditorName": "Berlin Utility",
          "creditorBic": "COBADEFFXXX",
          "amount": $amount,
          "currency": "EUR",
          "remittanceInfo": "Utility bill",
          "endToEndId": ${endToEndId?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()

    private fun ValidatableResponse.extractedBody(): Map<String, Any> =
        extract().body().jsonPath().getMap<String, Any>("")

    private fun ValidatableResponse.extractList(field: String): List<String> =
        extract().body().jsonPath().getList<String>(field)

    private companion object {
        const val ACTOR = "e2e-journey-operator"
        const val PAYMENTS_PATH = "/api/v1/sepa-payments"
        const val AMOUNT = "10.00"
        const val DIFFERENT_AMOUNT = "11.00"
        const val UNSCALED_AMOUNT = "10.999"

        val STORED_FIELDS = listOf(
            "id",
            "type",
            "status",
            "debtorAccountId",
            "debtorIban",
            "creditorIban",
            "creditorName",
            "amount",
            "currency",
            "endToEndId",
        )
    }
}
