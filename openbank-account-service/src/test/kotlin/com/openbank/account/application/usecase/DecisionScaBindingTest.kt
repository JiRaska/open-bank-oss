// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.ScaChallengeSnapshot
import com.openbank.account.domain.model.SavingsWithdrawalScaReference
import com.openbank.account.domain.model.WithdrawalProposal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/** #9430: SOLO decisions keep main's unlinked challenge; N_OF_M decisions are strictly linked. */
class DecisionScaBindingTest {

    private val now = OffsetDateTime.parse("2026-09-29T12:00:00Z")
    private val solo = WithdrawalProposal(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        delegatePartyId = UUID.randomUUID(),
        amountMinor = 150_000,
        currency = "CZK",
        createdAt = now,
        expiresAt = now.plusDays(1),
    )
    private val nOfM = solo.copy(
        approvalGroupId = UUID.randomUUID(),
        approvalGroupRevision = 3,
        requiredApprovals = 2,
        eligibleApproverIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
    )

    private fun challenge(amount: String? = null, currency: String? = null, reference: String? = null) =
        ScaChallengeSnapshot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SAVINGS_WITHDRAW_APPROVAL",
            "PENDING",
            amount,
            currency,
            reference,
        )

    private fun ref(p: WithdrawalProposal, approve: Boolean) = SavingsWithdrawalScaReference.of(p.id, approve)

    @Test
    fun `SOLO accepts a legacy challenge with no linking and restates nothing`() {
        assertThat(decisionScaBinding(challenge(), solo, true, "1500.00"))
            .isEqualTo(DecisionScaBinding(null, null, null))
    }

    @Test
    fun `SOLO accepts a challenge carrying the exact reference and restates it`() {
        val c = challenge("1500.00", "CZK", ref(solo, true))
        assertThat(decisionScaBinding(c, solo, true, "1500.00"))
            .isEqualTo(DecisionScaBinding("1500.00", "CZK", ref(solo, true)))
    }

    @Test
    fun `SOLO refuses a challenge whose reference is for the opposite decision`() {
        assertThat(decisionScaBinding(challenge(reference = ref(solo, false)), solo, true, "1500.00")).isNull()
    }

    @Test
    fun `SOLO refuses a challenge carrying a different amount`() {
        assertThat(decisionScaBinding(challenge(amount = "1.00"), solo, true, "1500.00")).isNull()
    }

    @Test
    fun `N_OF_M accepts only the exact reference, amount and currency`() {
        val c = challenge("1500.00", "CZK", ref(nOfM, true))
        assertThat(decisionScaBinding(c, nOfM, true, "1500.00"))
            .isEqualTo(DecisionScaBinding("1500.00", "CZK", ref(nOfM, true)))
    }

    @Test
    fun `N_OF_M refuses a legacy challenge with no reference`() {
        assertThat(decisionScaBinding(challenge("1500.00", "CZK"), nOfM, true, "1500.00")).isNull()
    }

    @Test
    fun `N_OF_M refuses a reject-signed challenge counted as an approve`() {
        assertThat(decisionScaBinding(challenge("1500.00", "CZK", ref(nOfM, false)), nOfM, true, "1500.00")).isNull()
    }
}
