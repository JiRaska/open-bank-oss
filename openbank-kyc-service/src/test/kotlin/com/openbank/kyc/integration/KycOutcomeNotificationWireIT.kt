// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.kafka.Record
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySink
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Issue #8432: `KYC_APPROVED` and `KYC_REJECTED` were declared, rendered and SECURITY-classified in
 * notification-service from the day it was written, and **nothing in the fleet ever emitted
 * either** — a customer was never told how their own identity verification ended.
 *
 * This is the test that can see that, and the mocked-port tests in `KycServiceTest` are the reason
 * it has to exist separately. Those assert that `KycService` called a `CustomerNotificationPort`;
 * they are structurally blind to all three ways this feature can be present in the source and
 * absent on the wire:
 *
 *  1. **An unwired channel.** `@Channel("notification-requests-out")` resolves against
 *     `application.yaml`. Delete or misname that channel and CDI cannot satisfy the emitter, so the
 *     service does not boot — and a service that cannot boot reports its tests as **SKIPPED**,
 *     which scans as a pass. Booting a real `@QuarkusTest` is what converts that into a failure.
 *  2. **A payload notification-service would reject.** `NotificationConsumer` parses into
 *     `NotificationRequest` and then applies `NotificationTemplate.unknownVariables`, which
 *     **rejects and ACKS** a request carrying any key the template does not declare. That failure
 *     mode is silent by construction: the producer sees a successful send, the consumer logs and
 *     acks, and the customer is simply never told. `KYC_APPROVED` declares NO variables and
 *     `KYC_REJECTED` declares exactly `reason`, so those two facts are asserted here as literals.
 *  3. **A wrong field name.** The envelope is a hand-built `mapOf`, so no compiler checks it
 *     against notification-service's data class. Only the JSON itself can.
 *
 * The expectations below are deliberately written as LITERALS rather than derived from the
 * publisher. Deriving them would move expectation and payload together and the test would stay
 * green while the wire format drifted — the same asymmetry the Pact consumer-test rule turns on.
 *
 * Drives the real HTTP endpoints for the reason `KycOutboxWriteIT` documents: a
 * `Panache.withTransaction` reactive repo called from a bare `@QuarkusTest` thread throws
 * "No current Vertx context found", so only a real request exercises the approve/reject path.
 */
@QuarkusTest
@QuarkusTestResource(KycOutcomeNotificationWireIT.InMemoryNotificationSinkResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class KycOutcomeNotificationWireIT {

    /**
     * Swaps the outgoing notification channel for an in-memory sink, so the test reads the actual
     * serialized payload with no broker. The outbox dispatcher is off (its own topic is not the
     * subject here) and `authz.enforce` is off because there is no OPA sidecar in a test JVM and
     * the interceptor correctly fails CLOSED without one — `KycSecurityTest` owns that decision.
     */
    class InMemoryNotificationSinkResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory(CHANNEL).toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            props["authz.enforce"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var objectMapper: ObjectMapper

    private lateinit var sink: InMemorySink<Record<String, String>>

    @BeforeEach
    fun drainSink() {
        sink = connector.sink(CHANNEL)
        sink.clear()
    }

    /** The records this channel actually emitted, decoded from the JSON that went on the wire. */
    private fun emitted(): List<Pair<String, Map<String, Any?>>> = sink.received().map { message ->
        val record = message.payload

        @Suppress("UNCHECKED_CAST")
        val decoded = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
        record.key() to decoded
    }

    private fun openCase(partyId: UUID): UUID {
        val id = Given {
            contentType("application/json")
            body("""{"partyId":"$partyId"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        return UUID.fromString(id)
    }

    private fun driveToUnderReview(caseId: UUID) {
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach { checkType ->
            Given {
                contentType("application/json")
                body("""{"status":"PASSED","result":"notification-wire-it"}""")
            } When {
                put("/api/v1/kyc/cases/$caseId/checks/$checkType")
            } Then {
                statusCode(200)
            }
        }
    }

    @Test
    @TestSecurity(user = "notif-wire-it", roles = ["ROLE_ADMIN", "ROLE_KYC_REVIEWER"])
    fun `approving a case puts a KYC_APPROVED notification request on the wire`() {
        val partyId = UUID.randomUUID()
        val caseId = openCase(partyId)
        driveToUnderReview(caseId)

        Given {
            contentType("application/json")
            body("""{"reason":"All documents verified by the notification wire IT"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(200)
        }

        val records = emitted()
        assertThat(records).describedAs("records on %s after an approval", CHANNEL).hasSize(1)
        val (key, payload) = records.single()

        // Keyed by party so one customer's notifications keep their order on a single partition.
        assertThat(key).describedAs("record key").isEqualTo(partyId.toString())

        // Exactly the five fields NotificationRequest requires — asserted as literals, because a
        // key notification-service does not know is dropped by Jackson and a key it requires but
        // never receives is a parse failure the producer cannot see.
        assertThat(payload).containsOnlyKeys("partyId", "channel", "template", "recipient", "variables")
        assertThat(payload["partyId"]).isEqualTo(partyId.toString())
        assertThat(payload["channel"]).isEqualTo("PUSH")
        assertThat(payload["template"]).isEqualTo("KYC_APPROVED")
        assertThat(payload["recipient"]).isEqualTo(partyId.toString())

        // The load-bearing assertion. NotificationTemplate.KYC_APPROVED declares NO variables, and
        // NotificationConsumer REJECTS (and acks) a request carrying any undeclared key. A single
        // stray variable here is silent non-delivery — no error at either end.
        assertThat(
            payload["variables"],
        ).describedAs("KYC_APPROVED declares no variables").isEqualTo(emptyMap<String, String>())
    }

    @Test
    @TestSecurity(user = "notif-wire-it", roles = ["ROLE_ADMIN", "ROLE_KYC_REVIEWER"])
    fun `rejecting a case puts a KYC_REJECTED notification request on the wire, carrying the reason`() {
        val partyId = UUID.randomUUID()
        val caseId = openCase(partyId)
        driveToUnderReview(caseId)

        Given {
            contentType("application/json")
            body("""{"reason":"Identity document expired"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/reject")
        } Then {
            statusCode(200)
        }

        val records = emitted()
        assertThat(records).describedAs("records on %s after a rejection", CHANNEL).hasSize(1)
        val (key, payload) = records.single()

        assertThat(key).describedAs("record key").isEqualTo(partyId.toString())
        assertThat(payload).containsOnlyKeys("partyId", "channel", "template", "recipient", "variables")
        assertThat(payload["partyId"]).isEqualTo(partyId.toString())
        assertThat(payload["channel"]).isEqualTo("PUSH")
        assertThat(payload["template"]).isEqualTo("KYC_REJECTED")

        // KYC_REJECTED declares exactly `reason` — no more (rejected outright) and no fewer (the
        // template renders an empty placeholder to the customer, who then has nothing to act on).
        assertThat(payload["variables"]).describedAs("KYC_REJECTED declares exactly `reason`")
            .isEqualTo(mapOf("reason" to "Identity document expired"))
    }

    /**
     * The control that keeps the two tests above honest. If merely opening a case emitted
     * something, `hasSize(1)` there would be satisfied by traffic that has nothing to do with the
     * verdict — and an assertion that cannot distinguish the two is not measuring the fix.
     */
    @Test
    @TestSecurity(user = "notif-wire-it", roles = ["ROLE_ADMIN", "ROLE_KYC_REVIEWER"])
    fun `opening a case notifies nobody — only a verdict does`() {
        val caseId = openCase(UUID.randomUUID())
        driveToUnderReview(caseId)

        assertThat(emitted())
            .describedAs("a case that has only been opened and checked has no verdict to announce")
            .isEmpty()
    }

    private companion object {
        const val CHANNEL = "notification-requests-out"
    }
}
