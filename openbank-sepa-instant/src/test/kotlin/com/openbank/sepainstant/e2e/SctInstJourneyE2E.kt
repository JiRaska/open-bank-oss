// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.common.ResourceArg
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
 * End-to-end journey for an SCT Inst (instant euro credit transfer): **submit the payment,
 * read it back over a different code path, find it on the debtor's payment list, retry the
 * submission the way a client with a lost response would, and try to recall it before it has
 * settled — which the service's own rule must refuse.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres (full Flyway chain), Redpanda and Valkey via
 * `PostgresRedpandaRedisTestResource`. No use case, repository or domain object is touched
 * directly and nothing is mocked out of the request path.
 *
 * ### Service boundaries — stated, not pretended
 *
 * Three hops leave this service and none of them is travelled here.
 *
 *  * **Kafka dispatch.** The outbox events channel is switched to the in-memory connector, so
 *    the payment's outbox row is written to this service's own database but nothing reaches a
 *    broker and no downstream consumer runs. The journey therefore stops at durable local state.
 *  * **Screening and downstream rails.** The sanctions/AML/fraud ports and the scheme gateway
 *    and settlement ports resolve to the service's test stubs (CLEAR screening, accepted scheme
 *    submission), because no sandbox counterpart runs in a `@QuarkusTest`. The instant scheme
 *    submission itself is flag-gated and OFF by default, so a CLEAR payment lands in PROCESSING
 *    and stays there — which is exactly what makes the recall rule observable below.
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison below is what distinguishes
 * "the payment was accepted" from "the payment was accepted and stored as submitted" — and the
 * debtor-list cardinality after the retry is what distinguishes "the client retry was
 * deduplicated" from "the retry inserted a second payment".
 */
@QuarkusTest
@QuarkusTestResource(SctInstJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedpandaRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_sepa_instant_e2e")],
)
class SctInstJourneyE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("sct-inst-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    private data class JourneyPayment(
        val paymentId: String,
        val debtorAccountId: UUID,
        val payload: String,
        val submitted: Map<String, Any>,
    )

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_OPERATOR"])
    fun `a submitted instant payment is stored, listed, replays on retry, and refuses recall before settlement`() {
        val payment = submitFreshPayment()

        // The committed row agrees with what the handler returned in memory — over a
        // different code path (GET by id), which is what makes this storage evidence.
        val stored = getById(payment.paymentId)
        for (field in STORED_FIELDS) {
            assertThat(stored[field])
                .`as`("stored payment field %s must equal the submitted one", field)
                .isEqualTo(payment.submitted[field])
        }
        assertThat(stored["executionTimeoutAt"]).isNotNull()

        // The payment is on its debtor's list, exactly once.
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)

        // A client that lost the response retries with the same Idempotency-Key: same
        // payment comes back, and no second row exists for the debtor.
        replayDoesNotDuplicate(payment)

        // The service's own rule: only SETTLED payments can be recalled. This payment never
        // reaches the scheme (submission flag off), so the recall must be refused with 400 —
        // the assertion is about this service's rule, and the unreachable scheme path is
        // never claimed.
        recallBeforeSettlementIsRefused(payment.paymentId)

        // The euro-only scheme rule refuses a non-EUR payment BEFORE anything is persisted:
        // a 400 and the debtor list is unchanged — no row, no event, no screening behind.
        nonEuroPaymentLeavesNothingBehind(payment)
    }

    private fun submitFreshPayment(): JourneyPayment {
        val idempotencyKey = UUID.randomUUID().toString()
        val debtorAccountId = UUID.randomUUID()
        val endToEndId = "E2E-SCTINST-${UUID.randomUUID().toString().take(8)}"
        val payload = """
            {
              "idempotencyKey": "$idempotencyKey",
              "debtorAccountId": "$debtorAccountId",
              "debtorIban": "CZ6508000000192000145399",
              "debtorName": "Journey Debtor",
              "creditorIban": "DE89370400440532013000",
              "creditorName": "Journey Creditor",
              "creditorBic": "COBADEFFXXX",
              "amount": 99.99,
              "currency": "EUR",
              "remittanceInfo": "SCT Inst journey",
              "endToEndId": "$endToEndId"
            }
        """.trimIndent()

        val created = submit(payload).then().statusCode(201).extractedBody()
        val paymentId = created["paymentId"] as String
        assertThat(paymentId).isNotBlank()
        assertThat(created["status"] as String).isEqualTo("PROCESSING")
        assertThat(created["endToEndId"] as String).isEqualTo(endToEndId)
        return JourneyPayment(paymentId, debtorAccountId, payload, created)
    }

    private fun replayDoesNotDuplicate(payment: JourneyPayment) {
        val replay = submit(payment.payload).then().statusCode(201).extractedBody()
        assertThat(replay["paymentId"] as String).isEqualTo(payment.paymentId)
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)
    }

    private fun recallBeforeSettlementIsRefused(paymentId: String) {
        Given {
            contentType("application/json")
            body("""{"reason": "journey probe"}""")
        } When {
            post("/api/v1/sepa-instant/$paymentId/recall")
        } Then {
            statusCode(400)
        }
    }

    private fun nonEuroPaymentLeavesNothingBehind(payment: JourneyPayment) {
        val foreignPayload = payment.payload.replace("\"currency\": \"EUR\"", "\"currency\": \"CZK\"")
        submit(foreignPayload, UUID.randomUUID().toString()).then().statusCode(400)
        assertThat(debtorList(payment.debtorAccountId)).containsExactly(payment.paymentId)
    }

    private fun submit(payload: String, key: String? = null): Response {
        val idempotencyKey = key ?: payload.substringAfter("\"idempotencyKey\": \"").substringBefore("\"")
        return Given {
            contentType("application/json")
            header("Idempotency-Key", idempotencyKey)
            body(payload)
        } When {
            post("/api/v1/sepa-instant")
        }
    }

    private fun getById(paymentId: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant/$paymentId")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun debtorList(debtorAccountId: UUID): List<String> {
        val response = Given {
            contentType("application/json")
        } When {
            get("/api/v1/sepa-instant/debtor/$debtorAccountId")
        } Then {
            statusCode(200)
        }
        return response.extractList("paymentId")
    }

    private fun ValidatableResponse.extractedBody(): Map<String, Any> =
        extract().body().jsonPath().getMap<String, Any>("")

    private fun ValidatableResponse.extractList(field: String): List<String> =
        extract().body().jsonPath().getList<String>(field)

    private companion object {
        const val ACTOR = "e2e-journey-operator"
        val STORED_FIELDS = listOf(
            "paymentId",
            "status",
            "debtorIban",
            "creditorIban",
            "amount",
            "currency",
            "endToEndId",
        )
    }
}
