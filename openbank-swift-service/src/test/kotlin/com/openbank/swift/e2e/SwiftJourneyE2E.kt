// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.e2e

import com.openbank.swift.it.PostgresRedisTestResource
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
 * End-to-end journey for an outbound SWIFT MT103 customer credit transfer on this service's own
 * surface: **send it, read it back over a different code path, retry the send the way a client
 * with a lost response would, let the operator acknowledge it, and find it by status — then the
 * boundary half: a malformed BIC and a zero amount are refused before anything is persisted or
 * any idempotency key is consumed, a null body is a 400, and an operator rejection stamps its
 * reason and lands the message in the REJECTED lane.**
 *
 * Driven through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`) against
 * real Postgres (full Flyway chain) and Valkey via `PostgresRedisTestResource`, with the outbox
 * on the in-memory connector. No use case, repository or domain object is touched directly.
 *
 * ### Service boundaries — stated, not pretended
 *
 * Scheme submission is flag-gated and OFF by default (ADR-0104 D4 pilot): a sent MT103 is
 * validated, stored and stays `VALIDATED` — no pacs.008 leaves the process, no scheme verdict is
 * claimed. The gateway hop itself is covered by the simulator ITs; this journey exercises exactly
 * the surface this service owns: the REST API, domain validation, the idempotency lookup, the
 * operator ack/reject decisions and the durable rows.
 *
 * ### Why the read-back is the observable
 *
 * `POST` returns the aggregate the handler just built in memory. The `GET` is a different code
 * path over the committed row, so the field-by-field comparison distinguishes "the message was
 * accepted" from "the message was accepted and stored as sent". The list cardinality after the
 * retry distinguishes "the retry was deduplicated" from "the retry inserted a second message" —
 * and the fresh 201 under the same key after a refused request distinguishes "the refusal
 * consumed the key" from "the refusal happened before the key was looked up".
 */
@QuarkusTest
@QuarkusTestResource(SwiftJourneyE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftJourneyE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out").toMutableMap().also {
                it["quarkus.kafka.devservices.enabled"] = "false"
            }

        override fun stop() = InMemoryConnector.clear()
    }

    private data class JourneyMessage(
        val id: String,
        val idempotencyKey: String,
        val transactionReference: String,
        val payload: String,
        val submitted: Map<String, Any>,
    )

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `an MT103 is stored, replayed on retry, acknowledged by the operator, and found by status`() {
        val message = sendFreshMessage()

        // The committed row agrees with what the handler returned in memory — over a
        // different code path (GET by id), which is what makes this storage evidence.
        val stored = getById(message.id)
        for (field in STORED_FIELDS) {
            assertThat(stored[field])
                .`as`("stored message field %s must equal the submitted one", field)
                .isEqualTo(message.submitted[field])
        }

        // A client that lost the response retries with the same idempotency key: the same
        // message comes back and exactly one row carries it on the full list.
        replayDoesNotDuplicate(message)

        // The operator acknowledges the scheme answer: the decision is persisted — status,
        // ackReceivedAt over the read-back path — and the message moves out of the VALIDATED
        // lane into the ACKNOWLEDGED one.
        val acknowledged = Given {
            contentType("application/json")
            body("""{"ackRef": "ACK-${UUID.randomUUID().toString().take(8)}"}""")
        } When {
            post("$SWIFT_PATH/${message.id}/ack")
        } Then {
            statusCode(200)
        }
        assertThat(acknowledged.extractedBody()["status"] as String).isEqualTo("ACKNOWLEDGED")

        val storedAfterAck = getById(message.id)
        assertThat(storedAfterAck["status"] as String).isEqualTo("ACKNOWLEDGED")
        assertThat(storedAfterAck["ackReceivedAt"]).isNotNull()
        assertThat(idsOf(listByStatus("ACKNOWLEDGED"))).contains(message.id)
        assertThat(idsOf(listByStatus("VALIDATED"))).doesNotContain(message.id)
    }

    @Test
    @TestSecurity(user = ACTOR, roles = ["ROLE_PAYMENTS"])
    fun `a malformed message is refused before the key is consumed, a null body is a 400`() {
        // Invalid sender BIC: domain validation refuses BEFORE the idempotency lookup, the row
        // or the outbox event — so the SAME key must still accept the corrected request as a
        // fresh 201 (#11604 ordering, over the real HTTP surface).
        val key = "swift-${UUID.randomUUID()}"
        send(payload(senderBic = "SHORT", key = key)).then().statusCode(400)
        val corrected = send(payload(senderBic = VALID_SENDER_BIC, key = key)).then().statusCode(201)
            .extractedBody()
        assertThat(corrected["status"] as String).isEqualTo("VALIDATED")

        // A zero amount is not positive — the same refusal class, equally side-effect-free.
        send(payload(amountMinorUnits = "0")).then().statusCode(400)

        // A JSON `null` body deserialises to null despite the non-nullable Kotlin type and is
        // a 400, not the 500 it was before the #3038 guard.
        Given {
            contentType("application/json")
            body("null")
        } When {
            post(SWIFT_PATH)
        } Then {
            statusCode(400)
        }

        // Operator rejection: the reason is persisted on the row and the message lands in the
        // REJECTED lane — readable over both the by-id and the by-status code paths.
        val rejected = send(payload()).then().statusCode(201).extractedBody()
        val rejectedId = rejected["id"] as String
        val rejection = Given {
            contentType("application/json")
            body("""{"reason": "$REJECTION_REASON"}""")
        } When {
            post("$SWIFT_PATH/$rejectedId/reject")
        } Then {
            statusCode(200)
        }
        assertThat(rejection.extractedBody()["status"] as String).isEqualTo("REJECTED")

        val storedAfterReject = getById(rejectedId)
        assertThat(storedAfterReject["status"] as String).isEqualTo("REJECTED")
        assertThat(storedAfterReject["rejectionReason"] as String).isEqualTo(REJECTION_REASON)
        assertThat(idsOf(listByStatus("REJECTED"))).contains(rejectedId)
    }

    private fun sendFreshMessage(): JourneyMessage {
        val body = payload()
        val created = send(body).then().statusCode(201).extractedBody()
        val id = created["id"] as String
        assertThat(id).isNotBlank()
        // Arrangement assertion: with the scheme-submission pilot flag OFF, a sent MT103 is
        // validated and stored and stays VALIDATED — the state every subsequent step reads back.
        assertThat(created["status"] as String).isEqualTo("VALIDATED")
        val key = KEY_PATTERN.find(body)!!.groupValues[1]
        val reference = REFERENCE_PATTERN.find(body)!!.groupValues[1]
        return JourneyMessage(id, key, reference, body, created)
    }

    private fun replayDoesNotDuplicate(message: JourneyMessage) {
        val replay = send(message.payload).then().statusCode(201).extractedBody()
        assertThat(replay["id"] as String).isEqualTo(message.id)
        val occurrences = idsOf(listAll()).count { it == message.id }
        assertThat(occurrences)
            .describedAs("exactly one row for the retried idempotency key")
            .isEqualTo(1)
    }

    private fun send(payload: String): Response = Given {
        contentType("application/json")
        body(payload)
    } When {
        post(SWIFT_PATH)
    }

    private fun getById(id: String): Map<String, Any> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$SWIFT_PATH/$id")
        } Then {
            statusCode(200)
        }
        return response.extractedBody()
    }

    private fun listAll(): List<Map<String, Any>> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$SWIFT_PATH/messages")
        } Then {
            statusCode(200)
        }
        return response.extractList("")
    }

    private fun listByStatus(status: String): List<Map<String, Any>> {
        val response = Given {
            contentType("application/json")
        } When {
            get("$SWIFT_PATH/status/$status")
        } Then {
            statusCode(200)
        }
        return response.extractList("")
    }

    private fun payload(
        senderBic: String = VALID_SENDER_BIC,
        amountMinorUnits: String = "15000",
        key: String = "swift-${UUID.randomUUID()}",
    ): String {
        val reference = "E2E-${UUID.randomUUID().toString().take(8)}"
        return """
            {
              "idempotencyKey": "$key",
              "messageType": "MT103",
              "senderBic": "$senderBic",
              "receiverBic": "DEUTDEFF",
              "transactionReference": "$reference",
              "relatedReference": null,
              "valueDate": "20261102",
              "currency": "EUR",
              "amountMinorUnits": $amountMinorUnits,
              "orderingCustomerAccount": "CZ6508000000192000145399",
              "orderingCustomerAccountId": null,
              "orderingCustomerName": "Alice Example",
              "beneficiaryAccount": "DE89370400440532013000",
              "beneficiaryName": "Berlin Utility",
              "remittanceInfo": "swift journey",
              "chargeCode": "SHA",
              "priority": "NORMAL"
            }
        """.trimIndent()
    }

    private fun ValidatableResponse.extractedBody(): Map<String, Any> =
        extract().body().jsonPath().getMap<String, Any>("")

    private fun ValidatableResponse.extractList(root: String): List<Map<String, Any>> =
        extract().body().jsonPath().getList<Map<String, Any>>(root)

    private fun idsOf(messages: List<Map<String, Any>>): List<String> = messages.map { it["id"] as String }

    private companion object {
        const val ACTOR = "e2e-journey-operator"
        const val SWIFT_PATH = "/api/v1/swift"
        const val VALID_SENDER_BIC = "OPBKCZ22"
        const val REJECTION_REASON = "sanctions follow-up"

        val KEY_PATTERN = Regex("\"idempotencyKey\": \"([^\"]+)\"")
        val REFERENCE_PATTERN = Regex("\"transactionReference\": \"([^\"]+)\"")

        val STORED_FIELDS = listOf(
            "id",
            "idempotencyKey",
            "messageType",
            "senderBic",
            "receiverBic",
            "transactionReference",
            "valueDate",
            "currency",
            "amountMinorUnits",
            "beneficiaryAccount",
            "beneficiaryName",
            "chargeCode",
            "status",
        )
    }
}
