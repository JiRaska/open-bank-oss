// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The materiality classification boundary (ADR-0256 D1, issue #4458).
 *
 * Two things are asserted here and both are deliberate:
 *
 * 1. **The SERIALIZED body**, not the classification object and not the outbox row's columns.
 *    sca-service put `eventType` in the outbox COLUMN and not in the payload; its producer test
 *    asserted the column, stayed green, and the consumer's parser read `""` and returned early —
 *    a branch that never once executed in production while the events sat SENT. A consumer of
 *    `openbank.party.events` sees the payload bytes and nothing else, so that is what is pinned.
 * 2. **The boundary in both directions.** A test that only proves a name change is MATERIAL
 *    passes against a classifier that returns MATERIAL unconditionally — which is the version of
 *    this feature that re-screens every customer who corrects their phone number.
 */
class PartyChangeMaterialityContractTest {

    // Mirrors the RUNNING producer's mapper. `findAndRegisterModules()` alone is NOT that mapper:
    // it leaves WRITE_DATES_AS_TIMESTAMPS on, so `occurredAt` serializes as `1.7866E9` and a test
    // written against it would assert a wire shape no consumer ever receives (Quarkus defaults
    // `quarkus.jackson.write-dates-as-timestamps` to false). The `Z` assertion below is the one
    // that caught it; `PartyOutboxWriteIT` re-asserts it against the real serialized outbox row.
    private val mapper = ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val at = Instant.parse("2026-08-14T08:00:00Z")

    private fun party() = Party(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        partyType = PartyType.INDIVIDUAL,
        status = PartyStatus.ACTIVE,
        legalName = "Jan Novák",
        tradingName = null,
        dateOfBirth = "1990-01-01",
        nationality = "CZ",
        taxId = null,
        registrationNumber = null,
        email = "jan@example.cz",
        phone = "+420111222333",
        address = null,
        kycStatus = KycStatus.APPROVED,
        createdAt = at,
        updatedAt = at,
        amlStatus = AmlStatus.CLEARED,
    )

    private fun envelope(before: Party, after: Party) = mapper.readTree(
        mapper.writeValueAsString(
            PartyEvents.updated(before, after, at, PartyActor.system("party-api")).envelope,
        ),
    )

    @Test
    fun `a legal-name change is MATERIAL on the wire and names the field that made it so`() {
        val json = envelope(party(), party().copy(legalName = "Jan Novotný"))

        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_UPDATED")
        assertThat(json.get("materiality").asText()).isEqualTo("MATERIAL")
        assertThat(json.get("materialFields").map { it.asText() }).containsExactly("legalName")
    }

    @Test
    fun `date of birth and residency country are material too`() {
        assertThat(envelope(party(), party().copy(dateOfBirth = "1990-01-02")).get("materiality").asText())
            .isEqualTo("MATERIAL")
        assertThat(envelope(party(), party().copy(nationality = "SK")).get("materiality").asText())
            .isEqualTo("MATERIAL")
    }

    @Test
    fun `contact-only and address-only edits are NON_MATERIAL and must never trigger a re-screen`() {
        listOf(
            party().copy(email = "jan.novak@example.cz"),
            party().copy(phone = "+420999888777"),
            party().copy(tradingName = "Novák Consulting"),
            party().copy(
                address = Address("Dlouhá 1", null, "Praha", "11000", "CZ"),
            ),
            party().copy(consentMarketing = true),
        ).forEach { after ->
            val json = envelope(party(), after)
            assertThat(json.get("materiality").asText())
                .describedAs("materiality for %s", json.toString())
                .isEqualTo("NON_MATERIAL")
            assertThat(json.get("materialFields")).isEmpty()
        }
    }

    /**
     * The third outcome, and the reason this is an enum rather than a boolean: an update that
     * moved nothing is not the same event as one that moved something KYC does not care about.
     * A shared "not material" flag would let a no-op PATCH storm read as ordinary contact churn.
     */
    @Test
    fun `an update that changes nothing is NO_CHANGE, not NON_MATERIAL`() {
        val json = envelope(party(), party().copy(updatedAt = at.plusSeconds(60)))

        assertThat(json.get("materiality").asText()).isEqualTo("NO_CHANGE")
        assertThat(json.get("materialFields")).isEmpty()
    }

    /**
     * Lifecycle state has its own events. If a KYC verdict projected onto the party record could
     * classify itself as a material party change, kyc-service would re-trigger on its own output.
     */
    @Test
    fun `a KYC or AML status projection is not a material master-data change`() {
        val json = envelope(party(), party().copy(kycStatus = KycStatus.EXPIRED, amlStatus = AmlStatus.BLOCKED))

        assertThat(json.get("materiality").asText()).isEqualTo("NO_CHANGE")
    }

    @Test
    fun `the classification is additive - the fields consumers already parse are untouched`() {
        val json = envelope(party(), party().copy(legalName = "Jan Novotný"))

        assertThat(json.get("partyId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(json.get("partyType").asText()).isEqualTo("INDIVIDUAL")
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE")
        assertThat(json.get("legalName").asText()).isEqualTo("Jan Novotný")
        assertThat(json.get("actorId").asText()).isEqualTo("system:party-service:party-api")
        assertThat(json.has("aggregateId")).isFalse()
    }

    /**
     * `occurredAt` (never `timestamp`) and it must render with a `Z` offset:
     * `AuditConsumer.eventTime()` parses with `Instant.parse`, which rejects a non-`Z` offset and
     * falls back to ingest time behind nothing louder than a warning. Recency, not non-nullity —
     * `isNotNull()` passes happily against 1970-01-01.
     */
    @Test
    fun `the event time is occurredAt, renders as Z, and is recent`() {
        val before = Instant.now().minusSeconds(1)
        val event = PartyEvents.updated(
            party(),
            party().copy(legalName = "Jan Novotný"),
            Instant.now(java.time.Clock.systemUTC()),
            PartyActor.system("party-api"),
        )
        val json = mapper.readTree(mapper.writeValueAsString(event.envelope))

        assertThat(json.has("timestamp")).isFalse()
        assertThat(json.get("occurredAt").asText()).endsWith("Z")
        assertThat(event.occurredAt).isBetween(before, Instant.now().plusSeconds(1))
    }

    @Test
    fun `no other event type carries a materiality claim it did not compute`() {
        listOf(
            PartyEvents.created(party(), at, PartyActor.system("party-api")),
            PartyEvents.kycStatusChanged(party(), at, PartyActor.system("kyc-status-projection")),
            PartyEvents.erased(party().id, at),
            PartyEvents.merged(party(), UUID.randomUUID(), at, PartyActor.system("party-merge")),
        ).forEach { event ->
            val json = mapper.readTree(mapper.writeValueAsString(event.envelope))
            assertThat(json.has("materiality"))
                .describedAs("%s must not declare a materiality", event.eventType)
                .isFalse()
        }
    }
}
