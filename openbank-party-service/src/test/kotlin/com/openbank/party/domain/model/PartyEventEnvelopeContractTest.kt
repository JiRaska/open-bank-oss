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
 *
 * It used to assert against `KafkaPartyEventPublisher`, the direct `@Channel` emitter removed by
 * issue #4007. The envelope moved to [PartyEvents] and the bytes are unchanged — the outbox
 * payload IS `envelope` serialized, and `party-outbox-out` publishes to the same topic the
 * emitter did. Asserting the serialized form (rather than the map) is deliberate: the wire is
 * what consumers read.
 */
class PartyEventEnvelopeContractTest {

    // Mirror the Quarkus-configured ObjectMapper (JSR-310 registered) so this test serializes
    // Instant exactly as the running producer does.
    private val mapper = ObjectMapper().findAndRegisterModules()

    private val at = Instant.parse("2026-06-11T08:00:00Z")

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
        createdAt = at,
        updatedAt = at,
        amlStatus = AmlStatus.NOT_SCREENED,
    )

    @Test
    fun `PARTY_CREATED emits the flat envelope consumers depend on`() {
        val event = PartyEvents.created(individual(), at, PartyActor.system("party-api"))
        val json = mapper.readTree(mapper.writeValueAsString(event.envelope))

        // Field-name contract — these exact names are read by account/aml/kyc/onboarding consumers.
        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_CREATED")
        assertThat(json.get("partyId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
        // account-service gates on partyType; legalName required for OpenAccountCommand (sanctions, ADR-0032 §C)
        assertThat(json.get("partyType").asText()).isEqualTo("INDIVIDUAL")
        assertThat(json.get("classification").asText()).isEqualTo("CUSTOMER")
        assertThat(json.get("legalName").asText()).isEqualTo("Jan Novák")
        assertThat(json.get("status").asText()).isEqualTo("PENDING_KYC")
        assertThat(json.has("occurredAt")).isTrue()
        // It must be a FLAT envelope — NOT the pid-service nested {aggregateId,payload} form.
        assertThat(json.has("aggregateId")).isFalse()
        assertThat(json.has("payload")).isFalse()
        // AuditConsumer attribution (#3994/#5256): the strongest (EVENT-sourced) claim it reads.
        assertThat(json.get("sourceService").asText()).isEqualTo("party-service")
    }

    @Test
    fun `PARTY_UPDATED to ACTIVE carries the status field account-service reconciles on`() {
        val event = PartyEvents.updated(
            individual(),
            individual().copy(status = PartyStatus.ACTIVE),
            at,
            PartyActor.system("party-api"),
        )
        val json = mapper.readTree(mapper.writeValueAsString(event.envelope))

        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_UPDATED")
        // account-service activates the pending account when it sees status=ACTIVE
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE")
        assertThat(json.get("partyId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111")
    }

    @Test
    fun `synthetic classification is preserved in the lifecycle contract`() {
        val canary = individual().copy(classification = PartyClassification.SYNTHETIC)
        val event = PartyEvents.created(canary, at, PartyActor.system("canary"))
        val json = mapper.readTree(mapper.writeValueAsString(event.envelope))

        assertThat(json.get("classification").asText()).isEqualTo("SYNTHETIC")
    }

    @Test
    fun `the outbox routing fields carry the aggregate and event type, not just the body`() {
        // What the dispatcher keys and headers the Kafka record on (OutboxKafkaHeaders) — a wrong
        // aggregateId would scatter one party's events across partitions and lose their ordering.
        val event = PartyEvents.merged(
            individual(),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            at,
            PartyActor.system("party-merge"),
        )
        assertThat(event.eventType).isEqualTo("PARTY_MERGED")
        assertThat(event.aggregateId).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        assertThat(event.occurredAt).isEqualTo(at)

        val json = mapper.readTree(mapper.writeValueAsString(event.envelope))
        assertThat(json.get("mergedIntoPartyId").asText()).isEqualTo("22222222-2222-2222-2222-222222222222")
        assertThat(json.get("sourceService").asText()).isEqualTo("party-service")
    }

    @Test
    fun `PARTY_ERASED keeps the narrower envelope - no PII survives GDPR Art 17`() {
        val id = UUID.randomUUID()
        val json = mapper.readTree(mapper.writeValueAsString(PartyEvents.erased(id, at).envelope))

        assertThat(json.get("eventType").asText()).isEqualTo("PARTY_ERASED")
        assertThat(json.get("partyId").asText()).isEqualTo(id.toString())
        assertThat(json.has("erasedAt")).isTrue()
        assertThat(json.get("sourceService").asText()).isEqualTo("party-service")
        listOf("legalName", "email", "partyType", "status", "kycStatus").forEach {
            assertThat(json.has(it)).describedAs("PARTY_ERASED must not carry %s", it).isFalse()
        }
    }

    /**
     * #3994 — red against `origin/main`, where no `PartyEvents` builder emitted an actor key and
     * 171 `PARTY_*` / `KYC_STATUS_CHANGED` audit rows therefore stored NULL.
     *
     * Exact values, not presence: the mechanism segment is what makes an unattributed row
     * actionable ("which path wrote this?"), and `isNotNull` would pass against every one of the
     * four different origins below.
     */
    @Test
    fun `a self-registration is attributed to the customer who registered`() {
        val sub = "d0b1d110-6698-46f0-8fcd-22314d000000"
        val json = mapper.readTree(
            mapper.writeValueAsString(PartyEvents.created(individual(), at, PartyActor.customer(sub)).envelope),
        )

        assertThat(json.get("actorId").asText()).isEqualTo(sub)
        assertThat(json.get("actorType").asText()).isEqualTo("CUSTOMER")
    }

    @Test
    fun `a KYC status projection says a system did it, and which one`() {
        val json = mapper.readTree(
            mapper.writeValueAsString(
                PartyEvents.kycStatusChanged(individual(), at, PartyActor.system("kyc-status-projection")).envelope,
            ),
        )

        assertThat(json.get("actorId").asText()).isEqualTo("system:party-service:kyc-status-projection")
        assertThat(json.get("actorType").asText()).isEqualTo("SYSTEM")
    }

    @Test
    fun `the AML projection is a DIFFERENT origin from the KYC one`() {
        // Both land on KYC_STATUS_CHANGED, and before this change both were one indistinguishable
        // NULL. Collapsing them back into a single "system" id would lose that again.
        val kyc = PartyEvents.kycStatusChanged(individual(), at, PartyActor.system("kyc-status-projection"))
        val aml = PartyEvents.kycStatusChanged(individual(), at, PartyActor.system("aml-status-projection"))

        assertThat(kyc.envelope["actorId"]).isNotEqualTo(aml.envelope["actorId"])
        assertThat(aml.envelope["actorId"]).isEqualTo("system:party-service:aml-status-projection")
    }

    @Test
    fun `PARTY_ERASED still carries no actor - it must not re-publish the erased subject`() {
        val json = mapper.readTree(mapper.writeValueAsString(PartyEvents.erased(UUID.randomUUID(), at).envelope))

        assertThat(json.has("actorId")).isFalse()
        assertThat(json.has("actorType")).isFalse()
    }
}
