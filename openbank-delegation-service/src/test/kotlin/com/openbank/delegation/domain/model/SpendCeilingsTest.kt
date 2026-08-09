// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0249 D3 — the ceiling arithmetic, which is the whole point of the feature. Every assertion
 * here is about a number the customer was previously told the platform enforced and it did not.
 */
class SpendCeilingsTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2026-08-08T12:00:00Z")
    private val grantor: UUID = UUID.randomUUID()
    private val grantee: UUID = UUID.randomUUID()
    private val accountId: UUID = UUID.randomUUID()

    private fun czk(amount: String) = Money.of(amount, "CZK")

    private fun spendGrant(
        perTx: Money? = null,
        daily: Money? = null,
        monthly: Money? = null,
        capabilities: Set<DelegationCapability> = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
        status: DelegationStatus = DelegationStatus.ACTIVE,
    ) = DelegationGrant(
        grantorPartyId = grantor,
        granteePartyId = grantee,
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = accountId,
        capabilities = capabilities,
        perTransactionLimit = perTx,
        dailyLimit = daily,
        monthlyLimit = monthly,
        validFrom = now.minusDays(1),
        validTo = now.plusDays(30),
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private fun counted(day: String, month: String) = CountedSpend(czk(day), czk(month))

    private val nothingCounted = counted("0.00", "0.00")

    @Test
    fun `allows an amount inside every ceiling`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(perTx = czk("2000.00"), daily = czk("5000.00"), monthly = czk("50000.00")),
            czk("1500.00"),
            counted("1000.00", "20000.00"),
            now,
        )

        assertThat(decision).isEqualTo(SpendDecision.Allowed)
    }

    /** Spending exactly the cap is spending within the cap — the boundary customers actually hit. */
    @Test
    fun `allows an amount that lands exactly on the daily ceiling`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("5000.00")),
            czk("2000.00"),
            counted("3000.00", "3000.00"),
            now,
        )

        assertThat(decision).isEqualTo(SpendDecision.Allowed)
    }

    @Test
    fun `refuses the daily ceiling by one minor unit and reports the headroom left`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("5000.00")),
            czk("2000.01"),
            counted("3000.00", "3000.00"),
            now,
        )

        assertThat(decision).isInstanceOf(SpendDecision.Refused::class.java)
        val refused = decision as SpendDecision.Refused
        assertThat(refused.reason).isEqualTo(SpendRefusalReason.DAILY)
        assertThat(refused.ceiling).isEqualTo(czk("5000.00"))
        assertThat(refused.alreadyCounted).isEqualTo(czk("3000.00"))
        assertThat(refused.remaining).isEqualTo(czk("2000.00"))
    }

    @Test
    fun `refuses the monthly ceiling even when the daily one is untouched`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("5000.00"), monthly = czk("20000.00")),
            czk("1000.00"),
            counted("0.00", "19500.00"),
            now,
        )

        val refused = decision as SpendDecision.Refused
        assertThat(refused.reason).isEqualTo(SpendRefusalReason.MONTHLY)
        assertThat(refused.remaining).isEqualTo(czk("500.00"))
    }

    /** The per-transaction ceiling is checked first, so its refusal is the one the caller sees. */
    @Test
    fun `reports PER_TX when the amount breaches both the per-transaction and the daily ceiling`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(perTx = czk("1000.00"), daily = czk("5000.00")),
            czk("9000.00"),
            nothingCounted,
            now,
        )

        assertThat((decision as SpendDecision.Refused).reason).isEqualTo(SpendRefusalReason.PER_TX)
    }

    /**
     * A grantor who lowers a ceiling below what is already reserved must not produce a negative
     * "remaining": rendered to a delegate it reads as a debt they do not owe.
     */
    @Test
    fun `clamps remaining headroom at zero when the ceiling is already over-consumed`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("1000.00")),
            czk("100.00"),
            counted("4000.00", "4000.00"),
            now,
        )

        assertThat((decision as SpendDecision.Refused).remaining).isEqualTo(czk("0.00"))
    }

    /** Same reason `withinLimits` denies rather than throws: an authz answer, not a crash. */
    @Test
    fun `denies rather than throws when the amount is in another currency than the ceiling`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("5000.00")),
            Money.of("100.00", "EUR"),
            counted("0.00", "0.00"),
            now,
        )

        assertThat((decision as SpendDecision.Refused).reason).isEqualTo(SpendRefusalReason.CURRENCY_MISMATCH)
    }

    @Test
    fun `refuses a grant that is not active`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(daily = czk("5000.00"), status = DelegationStatus.REVOKED),
            czk("1.00"),
            nothingCounted,
            now,
        )

        assertThat((decision as SpendDecision.Refused).reason).isEqualTo(SpendRefusalReason.GRANT_NOT_ACTIVE)
    }

    @Test
    fun `refuses a grant with no money-moving capability`() {
        val decision = SpendCeilings.evaluate(
            spendGrant(capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES)),
            czk("1.00"),
            nothingCounted,
            now,
        )

        assertThat((decision as SpendDecision.Refused).reason).isEqualTo(SpendRefusalReason.NO_SPEND_CAPABILITY)
    }

    /** No ceilings set at all is not "refuse everything" — it is the pre-ADR-0249 grant shape. */
    @Test
    fun `allows any amount on a grant that carries no ceilings`() {
        val decision = SpendCeilings.evaluate(spendGrant(), czk("999999.00"), nothingCounted, now)

        assertThat(decision).isEqualTo(SpendDecision.Allowed)
    }
}
