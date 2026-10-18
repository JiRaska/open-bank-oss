// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.e2e

import com.openbank.swift.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end journey of the SWIFT rail with the scheme-submission pilot flag OFF (the deployed
 * default): `POST /api/v1/swift` validates the message and persists it as `VALIDATED` locally,
 * and operator transitions (`ack` / `reject`) drive it to `ACKNOWLEDGED` / `REJECTED` — each
 * transition writing its outbox row in the same transaction (#8718).
 *
 * `authz.enforce` defaults to `false` (advisory), so no OPA mirror is needed — the `@Authorize`
 * annotations pass with a WARN.
 *
 * Money-path assertions, not happy-path smoke: idempotent replay must not double-send, the
 * status filter must track the lifecycle exactly, and the wire shape of the View is pinned
 * field by field (`currency` + `amountMinorUnits`, never a nested `amount` object — #11604).
 */
@QuarkusTest
@TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(SwiftLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class SwiftLifecycleE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("swift-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Test
    fun `a message is sent read back and acknowledged end to end`() {
        val key = "swift-e2e-${UUID.randomUUID()}"
        val ref = UUID.randomUUID().toString().take(12)

        val sent: Map<String, Any?> = Given {
            contentType("application/json")
            body(requestBody(key, ref, amount = 12500))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }

        val id = UUID.fromString(sent["id"] as String)
        assertWireShape(sent, key, ref)

        val readBack = getView(id)
        assertThat(readBack["id"]).isEqualTo(id.toString())
        assertThat(readBack["status"]).isEqualTo("VALIDATED")
        assertThat(readBack["amountMinorUnits"]).isEqualTo(sent["amountMinorUnits"])

        // The list endpoint answers in MAJOR units on a dedicated Response DTO (#11604).
        val listing: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/swift/messages")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        val listed = listing.first { it["id"] == id.toString() }
        assertThat((listed["amount"] as Number).toDouble()).isEqualTo(125.0)
        assertThat(listed["currency"]).isEqualTo("EUR")
        assertThat(listed["reference"]).isEqualTo(ref)

        val acked: Map<String, Any?> = Given {
            contentType("application/json")
            body(mapOf("ackRef" to "E2E-ACK-1"))
        } When {
            post("/api/v1/swift/$id/ack")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(acked["status"]).isEqualTo("ACKNOWLEDGED")
        assertThat(acked["ackReceivedAt"]).isNotNull()
        assertThat(acked["rejectionReason"]).isNull()

        assertThat(getView(id)["status"]).isEqualTo("ACKNOWLEDGED")
    }

    private fun assertWireShape(sent: Map<String, Any?>, key: String, ref: String) {
        // With the pilot flag off the message stays local: validated, never submitted.
        assertThat(sent["status"]).isEqualTo("VALIDATED")
        assertThat(sent["idempotencyKey"]).isEqualTo(key)
        assertThat(sent["transactionReference"]).isEqualTo(ref)
        assertThat(sent["messageType"]).isEqualTo("MT103")
        assertThat(sent["senderBic"]).isEqualTo("OPBKCZPP")
        assertThat(sent["receiverBic"]).isEqualTo("DEUTDEFF")
        assertThat((sent["amountMinorUnits"] as Number).toLong()).isEqualTo(12500)
        assertThat(sent["currency"]).isEqualTo("EUR")
        assertThat(sent).doesNotContainKey("amount")
        assertThat(sent["chargeCode"]).isEqualTo("SHA")
        assertThat(sent["priority"]).isEqualTo("NORMAL")
    }

    private fun getView(id: UUID): Map<String, Any?> = Given {
        this
    } When {
        get("/api/v1/swift/$id")
    } Then {
        statusCode(200)
    } Extract {
        jsonPath().getMap("")
    }

    @Test
    fun `an idempotent replay never double-sends`() {
        val key = "swift-e2e-idem-${UUID.randomUUID()}"
        val ref = UUID.randomUUID().toString().take(12)

        val first: Map<String, Any?> = Given {
            contentType("application/json")
            body(requestBody(key, ref, amount = 5000))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        val id = first["id"] as String

        // Same key, different amount on the retry: the replay wins over the new payload.
        val replay: Map<String, Any?> = Given {
            contentType("application/json")
            body(requestBody(key, ref, amount = 99999))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(replay["id"]).isEqualTo(id)
        assertThat((replay["amountMinorUnits"] as Number).toLong()).isEqualTo(5000)

        val listing: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/swift/messages")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(listing.filter { it["reference"] == ref }).hasSize(1)
    }

    @Test
    fun `the status filter tracks the lifecycle and reject carries the reason`() {
        val ackedId = send(keySuffix = "status-ack", amount = 1000)
        val rejectedId = send(keySuffix = "status-rej", amount = 2000)

        val rejected: Map<String, Any?> = Given {
            contentType("application/json")
            body(mapOf("reason" to "sanctions hold E2E"))
        } When {
            post("/api/v1/swift/$rejectedId/reject")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getMap("")
        }
        assertThat(rejected["status"]).isEqualTo("REJECTED")
        assertThat(rejected["rejectionReason"]).isEqualTo("sanctions hold E2E")

        Given {
            contentType("application/json")
            body(mapOf("ackRef" to "E2E-ACK-2"))
        } When {
            post("/api/v1/swift/$ackedId/ack")
        } Then {
            statusCode(200)
        }

        val acked: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/swift/status/ACKNOWLEDGED")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(acked.map { it["id"] }).contains(ackedId.toString())
        assertThat(acked.map { it["id"] }).doesNotContain(rejectedId.toString())

        val validated: List<Map<String, Any?>> = Given {
            this
        } When {
            get("/api/v1/swift/status/VALIDATED")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath().getList("")
        }
        assertThat(validated.map { it["id"] }).doesNotContain(ackedId.toString(), rejectedId.toString())

        // An unknown id is a 404 on read, not a 500.
        Given {
            this
        } When {
            get("/api/v1/swift/${UUID.randomUUID()}")
        } Then {
            statusCode(404)
        }
    }

    private fun send(keySuffix: String, amount: Int): UUID {
        val key = "swift-e2e-$keySuffix-${UUID.randomUUID()}"
        val ref = UUID.randomUUID().toString().take(12)
        val body: Map<String, Any?> = Given {
            contentType("application/json")
            body(requestBody(key, ref, amount = amount))
        } When {
            post("/api/v1/swift")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getMap("")
        }
        return UUID.fromString(body["id"] as String)
    }

    private fun requestBody(key: String, ref: String, amount: Int) =
        """
        {
          "idempotencyKey": "$key",
          "messageType": "MT103",
          "senderBic": "OPBKCZPP",
          "receiverBic": "DEUTDEFF",
          "transactionReference": "$ref",
          "relatedReference": null,
          "valueDate": "20261020",
          "currency": "EUR",
          "amountMinorUnits": $amount,
          "orderingCustomerAccount": "DE89370400440532013000",
          "orderingCustomerAccountId": null,
          "orderingCustomerName": "Alice",
          "beneficiaryAccount": "GB33BUKB20201555555555",
          "beneficiaryName": "Bob",
          "remittanceInfo": "e2e lifecycle",
          "chargeCode": "SHA",
          "priority": "NORMAL"
        }
        """.trimIndent()
}
