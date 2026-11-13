// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.ValidatableResponse
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * End-to-end journey of one applicant through the onboarding funnel, the way an operator sees it:
 * **registered, KYC opened, documents chased, reviewed, approved, party activated, device enrolled
 * and live; or rejected and blocked; or erased under GDPR.** After every event the only question
 * asked is what the cockpit's REST surface answers, over real HTTP against real Postgres.
 *
 * ### Service boundaries, stated and not pretended
 *
 * This service is a read model: it has no write endpoint, so the journey enters through the only
 * door it has, the real `@Incoming` handlers. The channels are in-memory, so the Kafka transport is
 * not claimed here. The payloads are the producers' wire shape, held by the kyc and party pacts.
 * party-, kyc- and sca-service do not run. Authentication is `@TestSecurity`. The customer's own
 * journey through those services is not this test's claim; what the operator is told about it is.
 *
 * ### Why the REST read is the observable
 *
 * The defects this read model has shipped were all invisible at the row-write level: an approved
 * customer moved backwards on the board (#8951), a status event quietly dropped (#9038), a mistyped
 * filter widening to every record (#8699). Each one is a wrong answer on this surface.
 */
@QuarkusTest
@QuarkusTestResource(OnboardingFunnelJourneyE2E.InMemoryChannels::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "onboarding-journey-e2e", roles = ["ROLE_VIEWER"])
class OnboardingFunnelJourneyE2E {

    class InMemoryChannels : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> = InMemoryConnector.switchIncomingChannelsToInMemory(PARTY, KYC, SCA)

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

    @Test
    fun `an applicant walks every board column in order and goes live`() {
        val party = UUID.randomUUID()
        val case = UUID.randomUUID()
        val activeBefore = funnel("ACTIVE")

        send(PARTY, partyCreated(party))
        record(party)
            .body("funnelStage", equalTo("REGISTERED"))
            .body("legalName", equalTo("Jana Nova"))
            .body("email", equalTo("jana@example.test"))
        assertThat(onBoard("REGISTERED")).contains(party.toString())

        send(KYC, kycEvent("KYC_CASE_OPENED", party, case))
        record(party).body("funnelStage", equalTo("KYC_OPEN")).body("kycCaseId", equalTo(case.toString()))
        assertThat(onBoard("REGISTERED")).doesNotContain(party.toString())
        assertThat(onBoard("KYC_OPEN")).contains(party.toString())

        send(KYC, kycEvent("KYC_CASE_STATUS_CHANGED", party, case, "DOCUMENTS_REQUIRED"))
        record(party).body("funnelStage", equalTo("KYC_DOCUMENTS_REQUIRED"))

        send(KYC, kycEvent("KYC_CASE_STATUS_CHANGED", party, case, "UNDER_REVIEW"))
        record(party).body("funnelStage", equalTo("KYC_UNDER_REVIEW"))

        // Approved but not yet activated stays in the KYC column: approval never moves a customer
        // backwards on the board (#8951). kyc-service approves first, party-service activates after.
        send(KYC, kycEvent("KYC_CASE_APPROVED", party, case))
        record(party).body("funnelStage", equalTo("KYC_UNDER_REVIEW")).body("kycStatus", equalTo("APPROVED"))

        send(PARTY, partyStatus(party, "ACTIVE"))
        record(party).body("funnelStage", equalTo("SCA_PENDING")).body("partyStatus", equalTo("ACTIVE"))
        assertThat(funnel("ACTIVE")).describedAs("activated without a device is not live").isEqualTo(activeBefore)

        send(SCA, deviceEnrolled(party, "cred-1"))
        record(party).body("funnelStage", equalTo("ACTIVE")).body("scaEnrolled", equalTo(true))
        assertThat(funnel("ACTIVE")).isEqualTo(activeBefore + 1)
        assertThat(onBoard("ACTIVE")).contains(party.toString())
        assertThat(onBoard("SCA_PENDING")).doesNotContain(party.toString())

        // Redelivery is normal on these topics: it changes no column and counts no second device.
        send(SCA, deviceEnrolled(party, "cred-1"))
        send(PARTY, partyCreated(party))
        record(party).body("funnelStage", equalTo("ACTIVE")).body("deviceCount", equalTo(1))
        assertThat(funnel("ACTIVE")).isEqualTo(activeBefore + 1)
    }

    @Test
    fun `a rejected applicant and a suspended customer are blocked, and arrival order does not matter`() {
        val rejected = UUID.randomUUID()
        val case = UUID.randomUUID()
        send(PARTY, partyCreated(rejected))
        send(KYC, kycEvent("KYC_CASE_OPENED", rejected, case))
        send(KYC, kycEvent("KYC_CASE_REJECTED", rejected, case))
        record(rejected).body("funnelStage", equalTo("BLOCKED")).body("kycStatus", equalTo("REJECTED"))
        assertThat(onBoard("BLOCKED")).contains(rejected.toString())

        // The three topics have no mutual ordering, so the enrolment can land first (#6248).
        val outOfOrder = UUID.randomUUID()
        send(SCA, deviceEnrolled(outOfOrder, "cred-early"))
        send(PARTY, partyStatus(outOfOrder, "ACTIVE"))
        record(outOfOrder).body("funnelStage", equalTo("ACTIVE")).body("legalName", nullValue())
        send(PARTY, partyCreated(outOfOrder))
        record(outOfOrder)
            .body("funnelStage", equalTo("ACTIVE"))
            .body("legalName", equalTo("Jana Nova"))
            .body("deviceCount", equalTo(1))

        send(PARTY, partyStatus(outOfOrder, "SUSPENDED"))
        record(outOfOrder).body("funnelStage", equalTo("BLOCKED"))
    }

    @Test
    fun `an erased party keeps its place in the funnel and loses its personal data`() {
        val party = UUID.randomUUID()
        send(PARTY, partyCreated(party))
        record(party).body("legalName", equalTo("Jana Nova"))

        send(PARTY, """{"eventType":"PARTY_ERASED","partyId":"$party","occurredAt":"${Instant.now()}"}""")

        record(party)
            .body("legalName", nullValue())
            .body("email", nullValue())
            .body("funnelStage", equalTo("REGISTERED"))
    }

    @Test
    fun `the cockpit never answers a bad question with data`() {
        // A mistyped filter must not widen to every record, personal data included (#8699).
        given().get("$RECORDS?stage=KYC_OPEEN").then().statusCode(BAD_REQUEST)
        given().get("$RECORDS/${UUID.randomUUID()}").then().statusCode(NOT_FOUND).body("code", equalTo("NOT_FOUND"))
    }

    private fun record(party: UUID): ValidatableResponse = given().get("$RECORDS/$party").then().statusCode(OK)

    private fun onBoard(stage: String): List<String> =
        given().get("$RECORDS?stage=$stage&size=100").then().statusCode(OK)
            .body("stageFilter", equalTo(stage))
            .extract().path("items.partyId")

    private fun funnel(stage: String): Int =
        given().get("/api/v1/onboarding/funnel").then().statusCode(OK).extract().path(stage)

    private fun partyCreated(party: UUID) =
        """{"eventType":"PARTY_CREATED","partyId":"$party","legalName":"Jana Nova",""" +
            """"email":"jana@example.test","occurredAt":"${Instant.now()}"}"""

    private fun partyStatus(party: UUID, status: String) =
        """{"eventType":"PARTY_STATUS_CHANGED","partyId":"$party","newStatus":"$status",""" +
            """"occurredAt":"${Instant.now()}"}"""

    private fun kycEvent(type: String, party: UUID, case: UUID, status: String? = null): String {
        val statusField = status?.let { """"status":"$it",""" }.orEmpty()
        return """{"eventType":"$type","partyId":"$party","kycCaseId":"$case",$statusField""" +
            """"occurredAt":"${Instant.now()}"}"""
    }

    private fun deviceEnrolled(party: UUID, credential: String) =
        """{"eventType":"DEVICE_ENROLLED","deviceId":"${UUID.randomUUID()}","partyId":"$party",""" +
            """"credentialId":"$credential","algorithm":"ES256","occurredAt":"${Instant.now()}",""" +
            """"sourceService":"sca-service"}"""

    /**
     * `runOnVertxContext(true)` supplies the duplicated context the real connector does. Waiting on
     * the ack is what makes the next read deterministic: it completes only after the handler ran.
     */
    private fun send(channel: String, payload: String) {
        val source: InMemorySource<Message<String>> = connector.source(channel)
        source.runOnVertxContext(true)
        val acked = CompletableFuture<Void>()
        source.send(
            Message.of(
                payload,
                Supplier<CompletionStage<Void>> {
                    acked.complete(null)
                    CompletableFuture.completedFuture(null)
                },
            ),
        )
        acked.get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val PARTY = "party-events-in"
        const val KYC = "kyc-events-in"
        const val SCA = "sca-events-in"
        const val RECORDS = "/api/v1/onboarding/records"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val ACK_TIMEOUT_SECONDS = 30L
    }
}
