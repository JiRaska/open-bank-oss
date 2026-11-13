// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import java.time.OffsetDateTime
import java.util.UUID

/**
 * One grant as account-service puts it on the wire (ADR-0375 D1).
 *
 * Deliberately primitives: this type is the contract between the facts endpoint and every rail
 * that consumes it, and it lives in the pure module so the parsing of a timestamp — the part that
 * can be wrong in six places at once — is written once. A rail's own REST-client interface may
 * deserialize straight into it; nothing here imports a serialization framework, so the pure
 * module stays pure (ADR-0375 D2).
 */
data class DelegationAuthorityGrantWire(
    val grantId: UUID,
    val grantorPartyId: UUID,
    val capabilities: List<String>,
    val validFrom: String,
    val validTo: String?,
)

/**
 * One grant exactly as it arrives off the wire, before anything has been validated.
 *
 * Every field is nullable and a string, because that is what a deserializer can actually produce:
 * a missing key, a null, or an unparseable value. Keeping the nullable form in the SHARED module is
 * the point — the alternative is each rail writing its own "is this grant usable?" check, and that
 * check has exactly one dangerous mistake available (drop the unusable grant, which turns a payload
 * we cannot read into a confident refusal). It is made once, here, in [DelegationAuthorityFacts].
 *
 * Nothing here imports a serialization framework: a rail's REST-client interface may deserialize
 * straight into it, so the pure module stays pure (ADR-0375 D2).
 */
data class DelegationAuthorityGrantPayload(
    val grantId: String? = null,
    val grantorPartyId: String? = null,
    val capabilities: List<String> = emptyList(),
    val validFrom: String? = null,
    val validTo: String? = null,
)

/**
 * What a rail got back when it asked for the facts.
 *
 * The whole point of this type is that it has two cases and not three. [Available] with an empty
 * list means "this party holds nothing here", which is a definitive answer and must reach
 * [DelegatedDebitAuthority] so the chain can REJECT. [Unavailable] means the question was not
 * answered at all. Collapsing them is the defect this repo keeps paying for — a fail-closed
 * verdict that reads as a clean refusal, or a lookup failure that reads as "no grant" and
 * silently refuses a legitimate delegate.
 */
sealed interface DelegationFactsLookup {

    data class Available(val grants: List<DelegationFact>) : DelegationFactsLookup

    data class Unavailable(val cause: UnavailableCause, val detail: String) : DelegationFactsLookup
}

/** The rail's own adapter; the pure chain never performs I/O itself. */
fun interface DelegationFactsSource {

    suspend fun factsFor(accountId: UUID, partyId: UUID): DelegationFactsLookup
}

/**
 * Turns the wire form into [DelegationFact]s and the authority question into a [StepOutcome].
 *
 * This object is the reason the slice exists. Without it, each rail would re-derive the same four
 * things, and the ones that are easy to get wrong are invisible when wrong: an unparseable
 * `validFrom` read as "now" (which makes a not-yet-valid grant enforceable), an absent `validTo`
 * read as expired (which refuses a legitimate open-ended delegate), a lookup failure read as "no
 * grant", and `active` invented on the rail side.
 */
object DelegationAuthorityFacts {

    /**
     * Maps one wire grant, or `null` when it cannot be understood.
     *
     * A grant whose `validFrom` does not parse is NOT treated as valid-from-now and NOT silently
     * dropped into a REJECT: the caller sees `null` and must raise [DelegationFactsLookup.Unavailable],
     * because a grant we cannot read is a grant whose authority we do not know. Dropping it would
     * turn a malformed row into a confident refusal.
     */
    fun grantOf(wire: DelegationAuthorityGrantWire, accountId: UUID, partyId: UUID): DelegationFact? {
        val from = runCatching { OffsetDateTime.parse(wire.validFrom) }.getOrNull() ?: return null
        val to = wire.validTo?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() ?: return null }
        return DelegationFact(
            grantId = wire.grantId,
            grantorPartyId = wire.grantorPartyId,
            resourceId = accountId,
            granteePartyId = partyId,
            capabilities = wire.capabilities.toSet(),
            // The endpoint returns active grants only and says so; `active` is not on the wire
            // precisely so a field that is always true cannot later be shipped as false.
            active = true,
            validFrom = from,
            validTo = to,
        )
    }

    /**
     * The whole response as it arrives off the wire, validated and mapped in one step.
     *
     * This is what a rail adapter should call: it owns the transport and nothing else. A grant
     * missing or mangling any identifying field makes the WHOLE lookup unavailable — never a grant
     * quietly absent from an otherwise-successful answer, which would read downstream as a
     * definitive "this party holds nothing here".
     */
    fun lookupOfPayload(
        payload: List<DelegationAuthorityGrantPayload>,
        accountId: UUID,
        partyId: UUID,
    ): DelegationFactsLookup {
        val wire = payload.map { wireOf(it) ?: return unreadable("a grant was missing an identifying field") }
        return lookupOf(wire, accountId, partyId)
    }

    private fun wireOf(payload: DelegationAuthorityGrantPayload): DelegationAuthorityGrantWire? {
        val grantId = payload.grantId?.let { uuidOrNull(it) } ?: return null
        val grantor = payload.grantorPartyId?.let { uuidOrNull(it) } ?: return null
        val validFrom = payload.validFrom ?: return null
        return DelegationAuthorityGrantWire(grantId, grantor, payload.capabilities, validFrom, payload.validTo)
    }

    private fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    /** All of them, or [DelegationFactsLookup.Unavailable] if any single one cannot be read. */
    fun lookupOf(wire: List<DelegationAuthorityGrantWire>, accountId: UUID, partyId: UUID): DelegationFactsLookup {
        val grants = wire.map { grantOf(it, accountId, partyId) ?: return malformed(it.grantId) }
        return DelegationFactsLookup.Available(grants)
    }

    /**
     * A transport failure a rail could not answer at all. Exposed so every adapter raises the SAME
     * shape instead of inventing one, and so none of them can reach for an empty list.
     */
    fun dependencyDown(detail: String): DelegationFactsLookup.Unavailable =
        DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, detail)

    private fun unreadable(detail: String): DelegationFactsLookup.Unavailable =
        DelegationFactsLookup.Unavailable(UnavailableCause.MISSING_DATA, detail)

    private fun malformed(grantId: UUID): DelegationFactsLookup.Unavailable = DelegationFactsLookup.Unavailable(
        // MISSING_DATA, not CHECK_FAILED: nothing threw. The rail HAS an answer from the endpoint
        // and cannot trust one grant in it, which is exactly "a fact the rail could not supply".
        UnavailableCause.MISSING_DATA,
        "grant $grantId has an unreadable validity window",
    )

    /**
     * The authority step, given a lookup: decide from the facts, or decline to answer.
     *
     * [DelegationFactsLookup.Unavailable] becomes [StepOutcome.Unavailable], which the chain maps
     * to HOLD for outbound money. It never becomes a REJECT, and never an ALLOW.
     */
    fun outcome(
        lookup: DelegationFactsLookup,
        accountId: UUID,
        ownerPartyId: UUID,
        actorPartyId: UUID,
        requiredCapability: String,
        now: OffsetDateTime,
    ): StepOutcome {
        // The owner is decided before the lookup is even looked at. An owner moving their OWN
        // money must not be held because a DELEGATION lookup failed — their authority does not
        // come from a grant, so a failure to read grants is not a failure to establish it. Asking
        // the lookup first would make every account-service blip a hold on ordinary payments,
        // which is the kind of fail-closed overreach that gets a control switched off.
        if (actorPartyId == ownerPartyId) {
            return DelegatedDebitAuthority.evaluate(
                DebitAuthorityFacts(accountId, ownerPartyId, actorPartyId, requiredCapability, emptyList(), now),
            )
        }
        return when (lookup) {
            is DelegationFactsLookup.Unavailable -> StepOutcome.Unavailable(
                lookup.cause,
                AdmissionEvidence.of("capability" to requiredCapability, "detail" to lookup.detail),
            )
            is DelegationFactsLookup.Available -> DelegatedDebitAuthority.evaluate(
                DebitAuthorityFacts(
                    accountId = accountId,
                    ownerPartyId = ownerPartyId,
                    actorPartyId = actorPartyId,
                    requiredCapability = requiredCapability,
                    grants = lookup.grants,
                    now = now,
                ),
            )
        }
    }
}
