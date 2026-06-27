// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Provider-side verification for party-domain message contracts (ADR-0063 P1+P2 → ADR-0092).
 * Covers PARTY_CREATED (P1), PARTY_UPDATED and KYC_STATUS_CHANGED (P2 Batch C). Plain JUnit +
 * [MessageTestTarget] — no Quarkus boot. Each message mirrors the wire shape of
 * [com.openbank.party.infrastructure.kafka.KafkaPartyEventPublisher] and is serialized with the
 * same Jackson modules so the contract is verified against the real envelope.
 *
 * Pacts are fetched from the Pact Broker (`@PactBroker`, configured via `pactbroker.*` system
 * properties from CI `-D`) and the result published back for `can-i-deploy`. Gated on
 * `pactbroker.url`: skipped locally, where committed `pacts/` (git-pact) stays the fallback.
 */
@Provider("openbank-party-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class PartyEventPactProviderVerificationTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        // Package-scoped scan: the default classpath-wide ClassGraph scan throws on the JDK 25 toolchain.
        // context is null when @IgnoreNoPactsToVerify fires a dummy invocation (no pacts in the broker).
        context?.let { it.target = MessageTestTarget(listOf("com.openbank.party.contract")) }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        // context is null on the @IgnoreNoPactsToVerify dummy invocation — skip gracefully.
        context?.verifyInteraction()
    }

    @State("a party has been created")
    fun partyHasBeenCreated() {
        // No setup: the message is produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a PARTY_CREATED event")
    fun producePartyCreatedEvent(): String {
        // Mirrors KafkaPartyEventPublisher.publish("PARTY_CREATED", party): the flat envelope, with
        // enums serialized to their names (partyType -> "INDIVIDUAL", etc.).
        val event = linkedMapOf(
            "eventType" to "PARTY_CREATED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.PENDING_KYC,
            "kycStatus" to KycStatus.NOT_STARTED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party has been updated")
    fun partyHasBeenUpdated() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a PARTY_UPDATED event")
    fun producePartyUpdatedEvent(): String {
        val event = linkedMapOf(
            "eventType" to "PARTY_UPDATED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.ACTIVE,
            "kycStatus" to KycStatus.APPROVED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }

    @State("a party KYC status has changed")
    fun partyKycStatusHasChanged() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_STATUS_CHANGED event")
    fun produceKycStatusChangedEvent(): String {
        val event = linkedMapOf(
            "eventType" to "KYC_STATUS_CHANGED",
            "partyId" to UUID.randomUUID(),
            "partyType" to PartyType.INDIVIDUAL,
            "status" to PartyStatus.ACTIVE,
            "kycStatus" to KycStatus.APPROVED,
            "legalName" to "Jane Smith",
            "email" to "jane.smith@example.com",
            "occurredAt" to Instant.now(),
        )
        return objectMapper.writeValueAsString(event)
    }
}
