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
        DelegationAuthorityFacts.outcome(lookup, account, owner, actor, capability, now)

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
        val step = outcome(DelegationFactsLookup.Available(emptyList()))

        assertThat(step).isInstanceOf(StepOutcome.Decided::class.java)
        assertThat((step as StepOutcome.Decided).decision).isEqualTo(AdmissionDecision.REJECT)
        assertThat(step.reason).isEqualTo(DelegatedDebitAuthority.NO_GRANT)
    }

    @Test
    fun `a permitting grant allows and names itself`() {
        val grantId = UUID.randomUUID()
        val lookup = DelegationAuthorityFacts.lookupOf(listOf(wire(grantId = grantId)), account, delegate)

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
        )

        val step = outcome(lookup) as StepOutcome.Decided
        assertThat(step.decision).isEqualTo(AdmissionDecision.REJECT)
    }

    @Test
    fun `an owner is allowed even when the grant lookup failed`() {
        val step = outcome(
            DelegationFactsLookup.Unavailable(UnavailableCause.DEPENDENCY_DOWN, "timeout"),
            actor = owner,
        )

        // The owner's authority does not come from a grant, so failing to READ grants is not
        // failing to establish it. Mapping unavailability before the owner check would hold every
        // ordinary payment whenever account-service blipped.
        assertThat(step).isInstanceOf(StepOutcome.Decided::class.java)
        assertThat((step as StepOutcome.Decided).decision).isEqualTo(AdmissionDecision.ALLOW)
        assertThat(step.reason).isEqualTo(DelegatedDebitAuthority.OWNER)
    }
}
