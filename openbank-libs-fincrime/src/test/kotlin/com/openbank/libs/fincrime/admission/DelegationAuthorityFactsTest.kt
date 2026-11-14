// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.admission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class DelegationAuthorityFactsTest {

    private val account = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val delegate = UUID.randomUUID()
    private val capability = "ACCOUNT_INITIATE_PAYMENT"
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-11-13T12:00:00Z")

    private fun wire(
        validFrom: String = "2026-11-12T12:00:00Z",
        validTo: String? = "2026-11-14T12:00:00Z",
        capabilities: List<String> = listOf(capability),
        grantId: UUID = UUID.randomUUID(),
    ) = DelegationAuthorityGrantWire(grantId, owner, capabilities, validFrom, validTo)

    private fun outcome(lookup: DelegationFactsLookup, actor: UUID = delegate) =
        DelegationAuthorityFacts.outcome(lookup, account, actor, capability, now)

    private fun available(grants: List<DelegationFact>, ownerPartyId: UUID? = owner) =
        DelegationFactsLookup.Available(grants, ownerPartyId)

    @Test
    fun `a wire grant maps onto the account and party that were asked about`() {
        val fact = DelegationAuthorityFacts.grantOf(wire(), account, delegate)

        assertThat(fact).isNotNull
        assertThat(fact!!.resourceId).isEqualTo(account)
        assertThat(fact.granteePartyId).isEqualTo(delegate)
        assertThat(fact.active).isTrue()
        assertThat(fact.validFrom).isEqualTo(OffsetDateTime.parse("2026-11-12T12:00:00Z"))
        assertThat(fact.validTo).isEqualTo(OffsetDateTime.parse("2026-11-14T12:00:00Z"))
    }

    @Test
    fun `an open-ended grant keeps a null validTo instead of expiring`() {
        val fact = DelegationAuthorityFacts.grantOf(wire(validTo = null), account, delegate)

        assertThat(fact!!.validTo).isNull()
        assertThat(fact.authorizes(account, delegate, capability, now)).isTrue()
    }

    @Test
    fun `an unreadable validFrom is not valid-from-now`() {
        assertThat(DelegationAuthorityFacts.grantOf(wire(validFrom = "yesterday"), account, delegate)).isNull()
    }

    @Test
    fun `an unreadable validTo is not treated as open-ended`() {
        assertThat(DelegationAuthorityFacts.grantOf(wire(validTo = "never"), account, delegate)).isNull()
    }

    @Test
    fun `one unreadable grant makes the whole lookup unavailable, never a refusal`() {
        val lookup = DelegationAuthorityFacts.lookupOf(
            listOf(wire(), wire(validFrom = "nope")),
            account,
            delegate,
            owner,
        )

        assertThat(lookup).isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
        val step = outcome(lookup)
        assertThat(step).isInstanceOf(StepOutcome.Unavailable::class.java)
        assertThat((step as StepOutcome.Unavailable).cause).isEqualTo(UnavailableCause.MISSING_DATA)
    }

    @Test
    fun `an unavailable lookup holds and does not reject`() {
        val step = outcome(DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, "timeout"))

        assertThat(step).isInstanceOf(StepOutcome.Unavailable::class.java)
        assertThat((step as StepOutcome.Unavailable).cause).isEqualTo(UnavailableCause.DEPENDENCY_DOWN)
    }

    @Test
    fun `an empty but successful lookup is a definitive refusal`() {
        val step = outcome(available(emptyList()))

        assertThat(step).isInstanceOf(StepOutcome.Decided::class.java)
        assertThat((step as StepOutcome.Decided).decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(step.reason).isEqualTo(DelegatedDebitAuthority.NO_GRANT)
    }

    @Test
    fun `a permitting grant allows and names itself`() {
        val grantId = UUID.randomUUID()
        val lookup = DelegationAuthorityFacts.lookupOf(listOf(wire(grantId = grantId)), account, delegate, owner)

        val step = outcome(lookup) as StepOutcome.Decided
        assertThat(step.decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(step.evidence.attributes).containsEntry("grant_id", grantId.toString())
    }

    @Test
    fun `a grant for another capability does not admit this action`() {
        val lookup = DelegationAuthorityFacts.lookupOf(
            listOf(wire(capabilities = listOf("ACCOUNT_READ_BALANCES"))),
            account,
            delegate,
            owner,
        )

        val step = outcome(lookup) as StepOutcome.Decided
        assertThat(step.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `a failed lookup holds even for the owner, because the owner is not known either`() {
        val step = outcome(
            DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, "timeout"),
            actor = owner,
        )

        // A deliberate change of behaviour, and the reasoning is worth keeping. While the owner was
        // a PARAMETER this returned ALLOW: the caller asserted who the owner was, so a delegation
        // outage did not need to hold an owner's own payment. That parameter was also the bug — it
        // let the owner come from a different read than the grants, so this branch could fire on
        // the wrong party while the write-time proof compared another.
        //
        // With the owner inside the lookup, a failed lookup means the owner is unknown too, and a
        // library that cannot read its only source must not claim to have established ownership.
        // So this step answers DELEGATED authority only. A rail that can establish ownership by
        // other means — domestic-payment compares the read owner with the reservation's grantor —
        // must not route an owner-funded payment through it. `createPayment` never does.
        assertThat(step).isInstanceOf(StepOutcome.Unavailable::class.java)
        assertThat((step as StepOutcome.Unavailable).cause).isEqualTo(UnavailableCause.DEPENDENCY_DOWN)
    }

    // ── the wire payload, validated once for every rail ───────────────────────────────────

    private fun payload(
        grantId: String? = UUID.randomUUID().toString(),
        grantor: String? = owner.toString(),
        capabilities: List<String> = listOf(capability),
        validFrom: String? = "2026-11-12T12:00:00Z",
        validTo: String? = null,
    ) = DelegationAuthorityGrantPayload(grantId, grantor, capabilities, validFrom, validTo)

    @Test
    fun `a well-formed payload maps onto the account and party that were asked about`() {
        val lookup = DelegationAuthorityFacts.lookupOfPayload(listOf(payload()), account, delegate, owner.toString())

        val grants = (lookup as DelegationFactsLookup.Available).grants
        assertThat(grants).hasSize(1)
        assertThat(grants[0].resourceId).isEqualTo(account)
        assertThat(grants[0].granteePartyId).isEqualTo(delegate)
    }

    @Test
    fun `a grant with no id is unavailable, never a grant quietly dropped`() {
        // Dropping it would leave an otherwise-successful answer that reads downstream as a
        // definitive "this party holds nothing here" — a refusal manufactured from a parse failure.
        val lookup = DelegationAuthorityFacts.lookupOfPayload(
            listOf(payload(), payload(grantId = null)),
            account,
            delegate,
            owner.toString(),
        )

        assertThat(lookup).isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
        assertThat((lookup as DelegationFactsLookup.Unavailable).cause).isEqualTo(UnavailableCause.MISSING_DATA)
    }

    @Test
    fun `a grant id that is not a uuid is unavailable`() {
        val lookup = DelegationAuthorityFacts.lookupOfPayload(
            listOf(payload(grantId = "not-a-uuid")),
            account,
            delegate,
            owner.toString(),
        )

        assertThat(lookup).isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
    }

    @Test
    fun `a grant with no grantor or no validFrom is unavailable`() {
        val o = owner.toString()
        assertThat(DelegationAuthorityFacts.lookupOfPayload(listOf(payload(grantor = null)), account, delegate, o))
            .isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
        assertThat(DelegationAuthorityFacts.lookupOfPayload(listOf(payload(validFrom = null)), account, delegate, o))
            .isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
    }

    @Test
    fun `an empty payload is an answered lookup that holds nothing`() {
        val lookup = DelegationAuthorityFacts.lookupOfPayload(emptyList(), account, delegate, owner.toString())

        // Answered, and the answer is nothing: the chain must be able to REJECT on this.
        assertThat(lookup).isEqualTo(available(emptyList()))
        assertThat(outcome(lookup)).isInstanceOf(StepOutcome.Decided::class.java)
    }

    @Test
    fun `dependencyDown is an unavailable every adapter raises the same way`() {
        val lookup = DelegationAuthorityFacts.dependencyDown("HTTP 503")

        assertThat(lookup.cause).isEqualTo(UnavailableCause.DEPENDENCY_DOWN)
        assertThat(lookup.detail).isEqualTo("HTTP 503")
    }

    // ── owner and grants are one snapshot ─────────────────────────────────────────────────

    @Test
    fun `an answered lookup whose owner is unknown holds, it is not a refusal`() {
        // An unknown account is not a verdict about anybody's authority over it. Refusing here
        // would convert "we have never heard of this account" into a definitive refusal.
        val step = outcome(available(emptyList(), ownerPartyId = null))

        assertThat(step).isInstanceOf(StepOutcome.Unavailable::class.java)
        assertThat((step as StepOutcome.Unavailable).cause).isEqualTo(UnavailableCause.MISSING_DATA)
    }

    @Test
    fun `the owner decision uses the owner that came WITH the grants`() {
        // The actor is the owner according to this lookup, so it is allowed as OWNER — even though
        // it holds no grant at all. Before the owner travelled in the lookup, a caller could pass
        // an owner from a second read and this branch would fire on the wrong party.
        val step = outcome(available(emptyList(), ownerPartyId = delegate))

        assertThat(step).isInstanceOf(StepOutcome.Decided::class.java)
        assertThat((step as StepOutcome.Decided).reason).isEqualTo(DelegatedDebitAuthority.OWNER)
    }

    @Test
    fun `a payload owner that is not a party id is unavailable, not an absent owner`() {
        val lookup = DelegationAuthorityFacts.lookupOfPayload(emptyList(), account, delegate, "nonsense")

        // Silently reading it as "no owner" would downgrade a malformed answer into the
        // unknown-account case, which looks identical and is a different fact.
        assertThat(lookup).isInstanceOf(DelegationFactsLookup.Unavailable::class.java)
        assertThat((lookup as DelegationFactsLookup.Unavailable).cause).isEqualTo(UnavailableCause.MISSING_DATA)
    }

    @Test
    fun `a null payload owner is an answered lookup with no owner`() {
        val lookup = DelegationAuthorityFacts.lookupOfPayload(emptyList(), account, delegate, null)

        assertThat(lookup).isEqualTo(available(emptyList(), ownerPartyId = null))
    }
}
