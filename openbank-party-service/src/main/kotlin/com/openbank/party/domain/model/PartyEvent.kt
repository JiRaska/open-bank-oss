// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.domain.model

import com.openbank.libs.domain.event.EventActor
import java.time.Instant
import java.util.UUID

/**
 * A party lifecycle event, together with the exact flat JSON envelope that goes on the wire
 * (topic `openbank.party.events`).
 *
 * [envelope] is deliberately the whole message body rather than a set of typed fields: the
 * envelope IS the contract downstream services parse (account-service opens an account on
 * `PARTY_CREATED`, aml/kyc/onboarding read the same field names), and it is pinned by
 * `PartyEventEnvelopeContractTest` and by the provider-side pacts. Serialization happens in the
 * infrastructure layer — this stays framework-free.
 */
data class PartyEvent(
    val eventType: String,
    val aggregateId: UUID,
    val occurredAt: Instant,
    val envelope: Map<String, Any?>,
)

/**
 * Who a party event is attributed to (#3994).
 *
 * An explicit parameter on every builder rather than a default, because the honest answer differs
 * per entry path and a default would let the wrong one be inherited silently — which is how the
 * 171 unattributed `PARTY_*`/`KYC_STATUS_CHANGED` audit rows happened in the first place.
 *
 * There is no request-scoped holder behind this and deliberately so: `PartyService` is the domain
 * use case, `openbank-libs-domain` carries zero framework imports (ADR-0002/ADR-0122), and a
 * CDI-produced ambient actor in `openbank-libs-runtime` would be injected into every service that
 * consumes the module whether or not it wants one.
 */
data class PartyActor(val id: String, val type: String) {
    companion object {
        private const val SERVICE = "party-service"

        /** No person originated this — a projection, a consumer, or an unattributed API call. */
        fun system(mechanism: String): PartyActor =
            PartyActor(EventActor.system(SERVICE, mechanism), EventActor.TYPE_SYSTEM)

        /**
         * The customer acting on their own record, identified by their Keycloak subject.
         *
         * Used only where the identity is unambiguous — self-registration, where `keycloakSub` IS
         * the authenticated caller. It is deliberately NOT used for the admin/onboarding
         * `createParty` path: the B1 invariant makes `cmd.id` equal the subject for self-service
         * onboarding but that path is also driven by onboarding-service, so attributing every
         * creation to the party itself would be a plausible, confident and sometimes false claim —
         * strictly worse than the `SYSTEM` id, which is at least true.
         */
        fun customer(keycloakSub: String): PartyActor = PartyActor(keycloakSub, TYPE_CUSTOMER)

        /** Matches the `actorType` customer-edge already writes for a self-service subject. */
        private const val TYPE_CUSTOMER = "CUSTOMER"
    }
}

/**
 * Builds the party lifecycle events.
 *
 * These used to be built inside `KafkaPartyEventPublisher`, a bare `@Channel("party-events-out")`
 * emitter that fired AFTER the repository transaction had already committed — a dual write. The
 * events now travel through `party_outbox`, written in the same transaction as the state change
 * (issue #4007), so the envelope construction had to move somewhere both the use case and the
 * repository can see. Field names, field order and the flat (non-nested) shape are unchanged, and
 * both channels publish to the same topic, so no consumer sees a difference.
 */
object PartyEvents {

    fun created(party: Party, at: Instant, actor: PartyActor): PartyEvent = lifecycle("PARTY_CREATED", party, at, actor)

    /**
     * A master-data update, carrying its own materiality classification (ADR-0256 D1, #4458).
     *
     * [before] is the record as it was; the classification is computed here rather than taken as
     * a parameter so no call site can declare a materiality the diff does not support. The two
     * added keys (`materiality`, `materialFields`) are ADDITIVE — existing consumers parse the
     * same fields they always did, and the wire schema version is a minor bump.
     */
    fun updated(before: Party, party: Party, at: Instant, actor: PartyActor): PartyEvent {
        val classification = PartyChange.classify(before, party)
        val base = lifecycle("PARTY_UPDATED", party, at, actor)
        return base.copy(
            envelope = LinkedHashMap(base.envelope).apply {
                put("materiality", classification.materiality.name)
                put("materialFields", classification.materialFields)
            },
        )
    }

    fun kycStatusChanged(party: Party, at: Instant, actor: PartyActor): PartyEvent =
        lifecycle("KYC_STATUS_CHANGED", party, at, actor)

    /**
     * **Deliberately carries no actor (#3994).** Every other builder here gained `actorId`/
     * `actorType`; this one did not. GDPR Art. 17 erasure is the one event whose envelope is
     * narrowed on purpose (see `PartyEventEnvelopeContractTest`), and the plausible actor for a
     * self-service erasure is the data subject's own Keycloak subject — putting that on a
     * broadcast topic would re-publish an identifier for the person the event exists to erase.
     * Zero rows of the live unattributed set are `PARTY_ERASED`, so nothing is lost by leaving it.
     */
    fun erased(partyId: UUID, at: Instant): PartyEvent = PartyEvent(
        eventType = "PARTY_ERASED",
        aggregateId = partyId,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to "PARTY_ERASED",
            "partyId" to partyId,
            "erasedAt" to at,
            "sourceService" to SOURCE_SERVICE,
        ),
    )

    /**
     * ADR-0179: [merged] is the retired duplicate (status MERGED); [survivingPartyId] is the party
     * consumers should follow from now on.
     */
    fun merged(merged: Party, survivingPartyId: UUID, at: Instant, actor: PartyActor): PartyEvent = PartyEvent(
        eventType = "PARTY_MERGED",
        aggregateId = merged.id,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to "PARTY_MERGED",
            "partyId" to merged.id,
            "mergedIntoPartyId" to survivingPartyId,
            "status" to merged.status,
            "occurredAt" to at,
            EventActor.FIELD_ACTOR_ID to actor.id,
            EventActor.FIELD_ACTOR_TYPE to actor.type,
            "sourceService" to SOURCE_SERVICE,
        ),
    )

    private fun lifecycle(eventType: String, party: Party, at: Instant, actor: PartyActor): PartyEvent = PartyEvent(
        eventType = eventType,
        aggregateId = party.id,
        occurredAt = at,
        envelope = linkedMapOf(
            "eventType" to eventType,
            "partyId" to party.id,
            "partyType" to party.partyType,
            "classification" to party.classification,
            "status" to party.status,
            "kycStatus" to party.kycStatus,
            "legalName" to party.legalName,
            "email" to party.email,
            "occurredAt" to at,
            EventActor.FIELD_ACTOR_ID to actor.id,
            EventActor.FIELD_ACTOR_TYPE to actor.type,
            "sourceService" to SOURCE_SERVICE,
        ),
    )

    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
     * (EVENT-sourced) attribution — issue #3994/#5256. Value matches the fleet's audit
     * convention: the module directory without the `openbank-` prefix, the same spelling
     * `TopicAttribution` already maps `openbank.party.events` to.
     */
    private const val SOURCE_SERVICE = "party-service"
}
