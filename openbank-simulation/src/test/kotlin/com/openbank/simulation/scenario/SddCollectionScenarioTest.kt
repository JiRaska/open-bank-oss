// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.sdd.domain.authorise.AuthorisationResult
import com.openbank.sdd.domain.authorise.CollectionAuthorisationPolicy
import com.openbank.sdd.domain.authorise.CollectionInstruction
import com.openbank.sdd.domain.authorise.DebtorControls
import com.openbank.sdd.domain.lifecycle.MandateLifecycle
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import com.openbank.sdd.domain.refund.RefundDecision
import com.openbank.sdd.domain.refund.RefundKind
import com.openbank.sdd.domain.refund.RefundPolicy
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.model.SddCollectionStatus
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Money-path SEPA Direct Debit adoption: exercises [SddCollectionScenario] directly against the
 * REAL sdd-service domain — the `SddMandate` aggregate, the pure `MandateLifecycle` state
 * machine, the fail-closed `CollectionAuthorisationPolicy` and the `RefundPolicy` windows — so
 * the harness's binding to sdd-service is proven by an actual assertion, not merely by compiling,
 * the same shape as [SctInstPaymentScenarioTest]/[ClearingCycleScenarioTest].
 *
 * The forced-fault branches are deterministic (`FaultProfile(1.0)` fires its draw with certainty,
 * and the write-failure draw precedes the duplicate/conflict draws); the seeded draws (settled
 * collection, eligible refund claim, expired mandate) are located by scanning fixed seeds, so the
 * test stays green if the scenario's draw order changes.
 */
class SddCollectionScenarioTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun newWorld(seed: Long, faultProfile: FaultProfile = FaultProfile.NONE): World =
        World(SimulationContext(seed, faultProfile), SimulationConfig())

    private fun newMandate(
        scheme: SddScheme = SddScheme.CORE,
        sequenceType: SequenceType = SequenceType.FRST,
        status: MandateStatus = MandateStatus.ACTIVE,
    ) = SddMandate(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        debtorIban = "DE0000000000000000000000",
        creditorIdentifier = "CREDITOR-X",
        umr = "UMR-1",
        scheme = scheme,
        sequenceType = sequenceType,
        creditorName = "Creditor",
        debtorName = "Debtor",
        signatureDate = today,
        status = status,
        b2bConfirmed = status == MandateStatus.ACTIVE && scheme == SddScheme.B2B,
        lastCollectionDate = null,
        lastPreNotificationDate = null,
        createdAt = Instant.now(),
    )

    private fun instruction(
        mandate: SddMandate,
        amount: BigDecimal = BigDecimal("100.00"),
        currency: String = "EUR",
        sequenceType: SequenceType = mandate.sequenceType,
    ) = CollectionInstruction(
        creditorIdentifier = mandate.creditorIdentifier,
        umr = mandate.umr,
        scheme = mandate.scheme,
        sequenceType = sequenceType,
        amount = amount,
        currency = currency,
        dueDate = today,
    )

    private fun rejectCode(decision: AuthorisationResult): String {
        val reject = decision as AuthorisationResult.Reject
        return reject.reasonCode
    }

    @Test
    fun `the real authorisation policy rejects mandate, scheme, currency and one-off faults`() {
        val mandate = newMandate()
        assertThat(CollectionAuthorisationPolicy.authorise(null, instruction(mandate)))
            .isInstanceOf(AuthorisationResult.Reject::class.java)

        val suspended = newMandate(status = MandateStatus.SUSPENDED)
        val rejected = CollectionAuthorisationPolicy.authorise(suspended, instruction(suspended))
        assertThat(rejectCode(rejected)).isEqualTo("MD01")

        val wrongScheme = CollectionAuthorisationPolicy.authorise(
            mandate,
            instruction(mandate, sequenceType = mandate.sequenceType).copy(scheme = SddScheme.B2B),
        )
        assertThat(rejectCode(wrongScheme)).isEqualTo("MD01")

        val wrongCurrency = CollectionAuthorisationPolicy.authorise(mandate, instruction(mandate, currency = "CZK"))
        assertThat(rejectCode(wrongCurrency)).isEqualTo("FF05")

        // a one-off mandate that already collected once is refused reuse by the REAL policy
        val used = MandateLifecycle.recordCollection(newMandate(sequenceType = SequenceType.OOFF), today)
        val reuse = CollectionAuthorisationPolicy.authorise(used, instruction(used))
        assertThat(rejectCode(reuse)).isEqualTo("MD01")
    }

    @Test
    fun `the real authorisation policy refuses debtor controls but accepts a clean collection`() {
        val mandate = newMandate()
        val blocked = CollectionAuthorisationPolicy.authorise(
            mandate,
            instruction(mandate),
            DebtorControls(blockAll = true),
        )
        assertThat((blocked as AuthorisationResult.Refuse).reasonCode).isEqualTo("MS02")

        val capped = CollectionAuthorisationPolicy.authorise(
            mandate,
            instruction(mandate, amount = BigDecimal("500.00")),
            DebtorControls(maxAmountPerCollection = BigDecimal("100.00")),
        )
        assertThat((capped as AuthorisationResult.Refuse).reasonCode).isEqualTo("MS02")

        val underCap = CollectionAuthorisationPolicy.authorise(
            mandate,
            instruction(mandate, amount = BigDecimal("50.00")),
            DebtorControls(maxAmountPerCollection = BigDecimal("100.00")),
        )
        assertThat(underCap).isEqualTo(AuthorisationResult.Accept)
    }

    @Test
    fun `the real refund policy honours the Core window, the 13-month rule and the B2B bar`() {
        val within = RefundPolicy.assess(SddScheme.CORE, today, today.plusDays(56), authorised = true)
        assertThat(within).isInstanceOf(RefundDecision.Eligible::class.java)

        val beyond = RefundPolicy.assess(SddScheme.CORE, today, today.plusDays(57), authorised = true)
        assertThat(beyond).isInstanceOf(RefundDecision.Ineligible::class.java)

        val unauthorised = RefundPolicy.assess(SddScheme.B2B, today, today.plusMonths(13), authorised = false)
        assertThat((unauthorised as RefundDecision.Eligible).kind).isEqualTo(RefundKind.UNAUTHORISED)

        val b2b = RefundPolicy.assess(SddScheme.B2B, today, today.plusDays(1), authorised = true)
        assertThat(b2b).isInstanceOf(RefundDecision.Ineligible::class.java)
    }

    @Test
    fun `a happy-path step settles the collection, stamps the mandate and posts one EUR journal`() {
        val world = worldWithStatus(SddCollectionStatus.SETTLED)
        val collection = world.sdd.collections.first { it.status == SddCollectionStatus.SETTLED }
        assertThat(collection.debitJournalId).isNotNull()
        assertThat(collection.currency).isEqualTo("EUR")
        // the happy path stamped the mandate through the real recordCollection
        val mandate = world.sdd.mandates.first { it.id == collection.mandateId }
        assertThat(mandate.lastCollectionDate).isNotNull()
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a write fault at the debit post fails the collection before any money moved`() {
        // 100% write-failure rate forces the debit fault on every accepted collection.
        val world = newWorld(seed = 42L, faultProfile = FaultProfile(writeFailureRate = 1.0))
        repeat(SEED_STEPS) { SddCollectionScenario.step(world) }

        assertThat(world.sdd.collections).isNotEmpty()
        assertThat(world.ledger.postedCount()).isEqualTo(0)
        assertThat(world.sdd.collections.filter { it.status == SddCollectionStatus.FAILED })
            .allMatch { it.debitJournalId == null }
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a post-commit conflict reverses the durable debit journal`() {
        // 100% lock-conflict rate forces the reversal after every debit booked, so every accepted
        // collection ends REVERSED with exactly two durable entries (original + reversal).
        val world = worldWithReversedCollection()
        val reversed = world.sdd.collections.count { it.status == SddCollectionStatus.REVERSED }
        assertThat(reversed).isGreaterThan(0)
        assertThat(world.sdd.collections.filter { it.status == SddCollectionStatus.REVERSED })
            .allMatch { it.reversed }
        assertThat(world.ledger.postedCount()).isEqualTo(reversed * 2)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddCompleteness.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `an eligible refund claim posts the exact reverse EUR legs`() {
        val world = worldWithRefund()
        val refunded = world.sdd.collections.first { it.refundJournalId != null }
        assertThat(refunded.status).isEqualTo(SddCollectionStatus.SETTLED)
        // one debit journal per settled collection + one refund journal per refunded one
        val settled = world.sdd.collections.count { it.status == SddCollectionStatus.SETTLED }
        val refunds = world.sdd.collections.count { it.refundJournalId != null }
        assertThat(world.ledger.postedCount()).isEqualTo(settled + refunds)
        assertThat(MoneyPathInvariants.conservationOfMoney.check(world)).isNull()
        assertThat(MoneyPathInvariants.sddMoneyConservation.check(world)).isNull()
    }

    @Test
    fun `a backdated mandate expires through the real idle-expiry sweep`() {
        val world = worldWithExpiredMandate()
        val expired = world.sdd.mandates.first { it.status == MandateStatus.EXPIRED }
        assertThat(expired.isTerminal).isTrue()
        assertThat(MoneyPathInvariants.sddCompleteness.check(world)).isNull()
    }

    @Test
    fun `the money conservation invariant catches a journal on a refused collection`() {
        val world = worldWithStatus(SddCollectionStatus.REFUSED)
        val refused = world.sdd.collections.first { it.status == SddCollectionStatus.REFUSED }
        // mutation: a refused collection must never have moved money — give it a journal
        world.sdd.replaceCollection(refused.copy(debitJournalId = UUID.randomUUID()))
        assertThat(MoneyPathInvariants.sddMoneyConservation.check(world)).isNotNull()
    }

    /** Scan fixed seeds until a collection reaches [status], so the test survives draw-order changes. */
    private fun worldWithStatus(status: SddCollectionStatus): World {
        for (seed in 0L..200L) {
            val world = newWorld(seed)
            repeat(SEED_STEPS) { SddCollectionScenario.step(world) }
            if (world.sdd.collections.any { it.status == status }) return world
        }
        error("no seed in 0..200 produced a $status collection")
    }

    private fun worldWithReversedCollection(): World {
        for (seed in 0L..200L) {
            val world = newWorld(seed, FaultProfile(lockConflictRate = 1.0))
            repeat(SEED_STEPS) { SddCollectionScenario.step(world) }
            if (world.sdd.collections.any { it.status == SddCollectionStatus.REVERSED }) return world
        }
        error("no seed in 0..200 produced a REVERSED collection")
    }

    private fun worldWithRefund(): World {
        for (seed in 0L..300L) {
            val world = newWorld(seed)
            repeat(SEED_STEPS) { SddCollectionScenario.step(world) }
            if (world.sdd.collections.any { it.refundJournalId != null }) return world
        }
        error("no seed in 0..300 produced a refund")
    }

    private fun worldWithExpiredMandate(): World {
        for (seed in 0L..300L) {
            val world = newWorld(seed)
            repeat(SEED_STEPS) { SddCollectionScenario.step(world) }
            if (world.sdd.mandates.any { it.status == MandateStatus.EXPIRED }) return world
        }
        error("no seed in 0..300 produced an expired mandate")
    }

    companion object {
        private const val SEED_STEPS = 10
    }
}
