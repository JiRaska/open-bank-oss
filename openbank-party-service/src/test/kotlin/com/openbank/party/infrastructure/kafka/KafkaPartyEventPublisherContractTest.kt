// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Producer side of the party-events wire contract (topic `openbank.party.events`).
 *
 * This is a consumer-driven contract test, lite: it pins the EXACT JSON envelope that
 * downstream services parse. The matching consumer side lives in
 * `openbank-account-service` PartyEventConsumerTest (PARTY_CREATED → open account) and in
 * aml-/kyc-/onboarding-service consumers. If anyone renames or restructures a field here,
 * THIS test breaks first — forcing the consumers (which cannot import this module) to be
 * updated in lockstep instead of silently no-opping at runtime.
 *
 * History: account-service drifted to a different (pid-service nested) envelope and the
 * party→account onboarding flow silently died for ~14 commits because no test crossed this
 * producer↔consumer boundary. This test + the account consumer test close that gap.
 */
class KafkaPartyEventPublisherContractTest {

    // Mirror the Quarkus-configured ObjectMapper (JSR-310 registered) so this test serializes
    // Instant exactly as the running producer does.
    private val mapper = ObjectMapper().findAndRegisterModules()

    private fun publisher(captured: CapturingSlot<String>): KafkaPartyEventPublisher {
        val emitter = mockk<Emitter<String>>()
        every { emitter.send(capture(captured)) } returns CompletableFuture.completedFuture(null)
        return KafkaPartyEventPublisher().also {
            it.emitter = emitter
            it.objectMapper = mapper
        }
    }

    private fun individual() = Party(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        partyType = PartyType.INDIVIDUAL,
        status = PartyStatus.PENDING_KYC,
        legalName = "Jan Novák",
        tradingName = null,
        dateOfBirth = "1990-01-01",
        nationality = "CZ",
        taxId = null,
        registrationNumber = null,
        email = "jan@example.cz",
        phone = null,
        address = null,
        kycStatus = KycStatus.NOT_STARTED,
        createdAt = Instant.parse("2026-06-11T08:00:00Z"),
        updatedAt = Instant.parse("2026-06-11T08:00:00Z"),
        amlStatus = AmlStatus.NOT_SCREENED,
    )

    @Test
    fun `PARTY_CREATED emits the flat envelope consumers depend on`(): Unit = runBlocking {
        val slot = slot<String>()
        publisher(slot).publishPartyCreated(individual())

        val json = mapper.readTree(slot.captured)
        // Field-name contract — these exact names are read by account/aml/kyc/onboarding consumers.
        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_CREATED")
        assertThat(json.get("partyId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(json.get("partyType").asText()).isEqualTo("INDIVIDUAL") // account-service gates on this value
        assertThat(json.get("legalName").asText()).isEqualTo("Jan Novák") // required for OpenAccountCommand (sanctions, ADR-0032 §C)
        assertThat(json.get("status").asText()).isEqualTo("PENDING_KYC")
        assertThat(json.has("occurredAt")).isTrue()
        // It must be a FLAT envelope — NOT the pid-service nested {aggregateId,payload} form.
        assertThat(json.has("aggregateId")).isFalse()
        assertThat(json.has("payload")).isFalse()
    }

    @Test
    fun `PARTY_UPDATED to ACTIVE carries the status field account-service reconciles on`(): Unit = runBlocking {
        val slot = slot<String>()
        publisher(slot).publishPartyUpdated(individual().copy(status = PartyStatus.ACTIVE))

        val json = mapper.readTree(slot.captured)
        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_UPDATED")
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE") // account-service activates the pending account on this
        assertThat(json.get("partyId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
    }
}
