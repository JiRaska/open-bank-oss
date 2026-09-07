// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.application

import com.openbank.loyalty.application.port.out.BenefitGrantRepository
import com.openbank.loyalty.application.port.out.LeafLedgerRepository
import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import com.openbank.loyalty.application.usecase.EarnLeavesUseCase
import com.openbank.loyalty.application.usecase.ExpireLeavesUseCase
import com.openbank.loyalty.application.usecase.RedeemBenefitUseCase
import com.openbank.loyalty.domain.AnnualCap
import com.openbank.loyalty.domain.BenefitCatalog
import com.openbank.loyalty.domain.BenefitGrant
import com.openbank.loyalty.domain.EarnCatalog
import com.openbank.loyalty.domain.EarnOutcome
import com.openbank.loyalty.domain.LeafEarnSource
import com.openbank.loyalty.domain.LeafEntryType
import com.openbank.loyalty.domain.LeafLedger
import com.openbank.loyalty.domain.LeafLedgerEntry
import com.openbank.loyalty.domain.Leaves
import com.openbank.loyalty.domain.RedemptionOutcome
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An in-memory ledger rather than a mock. A mock would let each test assert the call it expected
 * and nothing else; this fake keeps real state, so "the burn actually reduced the balance" is
 * observable — and it is the property these tests exist for. What the fake CANNOT show is
 * transactional atomicity: it has no transaction. That is `LoyaltyLedgerOutboxIT`'s job against a
 * real database, and no amount of mocking substitutes for it.
 */
private class FakeLedger : LeafLedgerRepository {
    val entries = mutableListOf<LeafLedgerEntry>()
    val grants = mutableListOf<BenefitGrant>()

    override suspend fun entriesFor(partyId: UUID) = entries.filter { it.partyId == partyId }

    override suspend fun findEarn(partyId: UUID, source: LeafEarnSource, correlationEventId: UUID) =
        entries.firstOrNull {
            it.partyId == partyId &&
                it.type == LeafEntryType.EARN &&
                it.earnSource?.id == source.id &&
                it.correlationEventId == correlationEventId
        }

    override suspend fun earnedInYearOf(partyId: UUID, at: Instant): Leaves = entries
        .filter { it.partyId == partyId && it.type == LeafEntryType.EARN }
        .fold(Leaves.ZERO) { acc, e -> acc + e.leaves }

    override suspend fun appendEarn(entry: LeafLedgerEntry) {
        entries += entry
    }

    override suspend fun appendBurnAndGrant(
        entry: LeafLedgerEntry,
        debits: List<LeafLedger.LotDebit>,
        grant: BenefitGrant,
    ) {
        debits.forEach { debit ->
            val idx = entries.indexOfFirst { it.id == debit.lotId }
            entries[idx] = entries[idx].copy(remaining = entries[idx].remaining - debit.amount)
        }
        entries += entry
        grants += grant
    }

    override suspend fun appendExpiries(entries2: List<LeafLedgerEntry>, lotIds: List<UUID>) {
        lotIds.forEach { lotId ->
            val idx = entries.indexOfFirst { it.id == lotId }
            entries[idx] = entries[idx].copy(remaining = Leaves.ZERO)
        }
        entries += entries2
    }

    override suspend fun partiesWithExpirableLots(at: Instant, limit: Int) = entries
        .filter { it.type == LeafEntryType.EARN && !it.remaining.isZero() && it.isExpiredAt(at) }
        .map { it.partyId }.distinct().take(limit)

    override suspend fun outstandingLeaves(at: Instant) = entries
        .filter { it.type == LeafEntryType.EARN && !it.isExpiredAt(at) }
        .sumOf { it.remaining.value.toLong() }
}

private class FakeGrants(private val ledger: FakeLedger) : BenefitGrantRepository {
    override suspend fun findByIdempotencyKey(partyId: UUID, key: String) =
        ledger.grants.firstOrNull { it.partyId == partyId && it.idempotencyKey == key }
}

private class RecordingMetrics : LoyaltyMetricsPort {
    val awarded = mutableListOf<String>()
    val capped = mutableListOf<String>()
    val replayed = mutableListOf<String>()
    val granted = mutableListOf<String>()
    val refused = mutableListOf<Pair<String, String>>()
    var expired = 0
    var outstanding = 0L

    override fun earnAwarded(sourceId: String, leaves: Int) {
        awarded += sourceId
    }
    override fun earnCapped(sourceId: String, requested: Int) {
        capped += sourceId
    }
    override fun earnReplayed(sourceId: String) {
        replayed += sourceId
    }
    override fun benefitGranted(benefitId: String, price: Int) {
        granted += benefitId
    }
    override fun benefitRefused(benefitId: String, reason: String) {
        refused += benefitId to reason
    }
    override fun leavesExpired(count: Int) {
        expired += count
    }
    override fun outstandingObligation(leaves: Long) {
        outstanding = leaves
    }
}

class EarnLeavesUseCaseTest {
    private val party = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `an achievement awards a lot that expires`(): Unit = runBlocking {
        val ledger = FakeLedger()
        val metrics = RecordingMetrics()
        val outcome = EarnLeavesUseCase(ledger, metrics, clock)
            .earn(party, LeafEarnSource.SavingsGoalReached, UUID.randomUUID())

        val awarded = assertInstance<EarnOutcome.Awarded>(outcome)
        assertThat(awarded.entry.expiresAt).isNotNull()
        assertThat(awarded.entry.ruleVersion).isEqualTo(EarnCatalog.RULE_VERSION)
        assertThat(metrics.awarded).containsExactly(LeafEarnSource.SavingsGoalReached.id)
    }

    /**
     * The same achievement reported twice is ONE achievement. Keyed on the triggering event, so a
     * redelivered message replays instead of awarding — the exact guard engagement-service got
     * wrong first time by keying on a freshly minted id.
     */
    @Test
    fun `the same achievement reported twice awards once`(): Unit = runBlocking {
        val ledger = FakeLedger()
        val metrics = RecordingMetrics()
        val useCase = EarnLeavesUseCase(ledger, metrics, clock)
        val correlation = UUID.randomUUID()

        useCase.earn(party, LeafEarnSource.LoginStreak, correlation)
        val second = useCase.earn(party, LeafEarnSource.LoginStreak, correlation)

        assertInstance<EarnOutcome.AlreadyAwarded>(second)
        assertThat(ledger.entries).hasSize(1)
        assertThat(metrics.replayed).containsExactly(LeafEarnSource.LoginStreak.id)
    }

    /**
     * The falsification the cap exists for: a capped attempt writes NO ledger row, is not an
     * `Awarded`, and lands on its own metric. If `Capped` ever became a flag on a shared outcome,
     * the balance assertion below would still pass while the programme silently stopped rewarding.
     */
    @Test
    fun `a capped earn awards nothing and is counted separately from an award`(): Unit = runBlocking {
        val ledger = FakeLedger()
        val metrics = RecordingMetrics()
        val rule = EarnCatalog.ruleFor(LeafEarnSource.QualifiedReferral)
        // Seed the party just under the cap so the next award cannot fit.
        ledger.entries += LeafLedgerEntry(
            id = UUID.randomUUID(),
            partyId = party,
            type = LeafEntryType.EARN,
            leaves = AnnualCap.PER_PARTY_PER_YEAR,
            remaining = AnnualCap.PER_PARTY_PER_YEAR,
            earnSource = LeafEarnSource.TenureAnniversary,
            ruleVersion = EarnCatalog.RULE_VERSION,
            correlationEventId = UUID.randomUUID(),
            occurredAt = Instant.parse("2026-01-02T00:00:00Z"),
            expiresAt = Instant.parse("2027-01-02T00:00:00Z"),
        )

        val outcome = EarnLeavesUseCase(ledger, metrics, clock)
            .earn(party, LeafEarnSource.QualifiedReferral, UUID.randomUUID())

        val capped = assertInstance<EarnOutcome.Capped>(outcome)
        assertThat(capped.requested).isEqualTo(rule.leaves)
        assertThat(capped.remaining).isEqualTo(Leaves.ZERO)
        assertThat(ledger.entries).hasSize(1)
        assertThat(metrics.capped).containsExactly(LeafEarnSource.QualifiedReferral.id)
        assertThat(metrics.awarded).isEmpty()
    }
}

class RedeemBenefitUseCaseTest {
    private val party = UUID.randomUUID()
    private val now = Instant.parse("2026-06-01T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun seededLedger(amount: Int): FakeLedger = FakeLedger().apply {
        entries += LeafLedgerEntry(
            id = UUID.randomUUID(),
            partyId = party,
            type = LeafEntryType.EARN,
            leaves = Leaves.of(amount),
            remaining = Leaves.of(amount),
            earnSource = LeafEarnSource.EmergencyBufferReached,
            ruleVersion = EarnCatalog.RULE_VERSION,
            correlationEventId = UUID.randomUUID(),
            occurredAt = now.minusSeconds(60),
            expiresAt = now.plus(Duration.ofDays(30)),
        )
    }

    @Test
    fun `a redemption burns the price and records a grant`(): Unit = runBlocking {
        val benefit = BenefitCatalog.ALL.getValue("MONTHLY_MAINTENANCE_FEE_WAIVER")
        val ledger = seededLedger(benefit.price.value + 10)
        val metrics = RecordingMetrics()

        val outcome = RedeemBenefitUseCase(ledger, FakeGrants(ledger), metrics, clock)
            .redeem(party, benefit.id, "key-1")

        val granted = assertInstance<RedemptionOutcome.Granted>(outcome)
        assertThat(granted.grant.grantedAt).isEqualTo(now)
        assertThat(LeafLedger.balance(ledger.entriesFor(party), now)).isEqualTo(Leaves.of(10))
        assertThat(metrics.granted).containsExactly(benefit.id)
    }

    /** A retry with the same key resolves to the grant it already made — it must not burn twice. */
    @Test
    fun `a retried redemption with the same idempotency key burns once`(): Unit = runBlocking {
        val benefit = BenefitCatalog.ALL.getValue("MONTHLY_MAINTENANCE_FEE_WAIVER")
        val ledger = seededLedger(benefit.price.value * 2)
        val useCase = RedeemBenefitUseCase(ledger, FakeGrants(ledger), RecordingMetrics(), clock)

        val first = useCase.redeem(party, benefit.id, "same-key")
        val second = useCase.redeem(party, benefit.id, "same-key")

        val firstGrant = assertInstance<RedemptionOutcome.Granted>(first).grant
        val replay = assertInstance<RedemptionOutcome.AlreadyGranted>(second).grant
        assertThat(replay.id).isEqualTo(firstGrant.id)
        assertThat(ledger.grants).hasSize(1)
        assertThat(LeafLedger.balance(ledger.entriesFor(party), now)).isEqualTo(benefit.price)
    }

    @Test
    fun `an unaffordable redemption reports the shortfall and burns nothing`(): Unit = runBlocking {
        val benefit = BenefitCatalog.ALL.getValue("SAVINGS_RATE_BONUS_90D")
        val ledger = seededLedger(1)
        val metrics = RecordingMetrics()

        val outcome = RedeemBenefitUseCase(ledger, FakeGrants(ledger), metrics, clock)
            .redeem(party, benefit.id, "key-2")

        val refused = assertInstance<RedemptionOutcome.InsufficientLeaves>(outcome)
        assertThat(refused.available).isEqualTo(Leaves.of(1))
        assertThat(ledger.grants).isEmpty()
        assertThat(metrics.refused).containsExactly(benefit.id to "insufficient_leaves")
    }

    /** An expired lot cannot pay for anything, even though it is still on the ledger. */
    @Test
    fun `expired leaves cannot fund a redemption`(): Unit = runBlocking {
        val benefit = BenefitCatalog.ALL.getValue("FX_REFERENCE_RATE_ONE_CONVERSION")
        val ledger = FakeLedger().apply {
            entries += LeafLedgerEntry(
                id = UUID.randomUUID(),
                partyId = party,
                type = LeafEntryType.EARN,
                leaves = Leaves.of(benefit.price.value * 2),
                remaining = Leaves.of(benefit.price.value * 2),
                earnSource = LeafEarnSource.TenureAnniversary,
                ruleVersion = EarnCatalog.RULE_VERSION,
                correlationEventId = UUID.randomUUID(),
                occurredAt = now.minus(Duration.ofDays(400)),
                expiresAt = now.minus(Duration.ofDays(1)),
            )
        }

        val outcome = RedeemBenefitUseCase(ledger, FakeGrants(ledger), RecordingMetrics(), clock)
            .redeem(party, benefit.id, "key-3")

        assertThat(assertInstance<RedemptionOutcome.InsufficientLeaves>(outcome).available).isEqualTo(Leaves.ZERO)
    }

    @Test
    fun `an unknown benefit is refused as unknown, never as unaffordable`(): Unit = runBlocking {
        val ledger = seededLedger(10_000)
        val outcome = RedeemBenefitUseCase(ledger, FakeGrants(ledger), RecordingMetrics(), clock)
            .redeem(party, "NO_SUCH_BENEFIT", "key-4")
        assertInstance<RedemptionOutcome.UnknownBenefit>(outcome)
    }
}

class ExpireLeavesUseCaseTest {
    private val party = UUID.randomUUID()
    private val now = Instant.parse("2026-06-01T10:00:00Z")

    @Test
    fun `an expired lot is zeroed and leaves an EXPIRE row correlated to it`(): Unit = runBlocking {
        val lotId = UUID.randomUUID()
        val ledger = FakeLedger().apply {
            entries += LeafLedgerEntry(
                id = lotId,
                partyId = party,
                type = LeafEntryType.EARN,
                leaves = Leaves.of(75),
                remaining = Leaves.of(75),
                earnSource = LeafEarnSource.FeedbackGiven,
                ruleVersion = EarnCatalog.RULE_VERSION,
                correlationEventId = UUID.randomUUID(),
                occurredAt = now.minus(Duration.ofDays(400)),
                expiresAt = now.minus(Duration.ofDays(1)),
            )
        }
        val metrics = RecordingMetrics()

        val expired = ExpireLeavesUseCase(ledger, metrics, Clock.fixed(now, ZoneOffset.UTC)).sweep()

        assertThat(expired).isEqualTo(1)
        assertThat(LeafLedger.balance(ledger.entriesFor(party), now)).isEqualTo(Leaves.ZERO)
        val expiry = ledger.entries.single { it.type == LeafEntryType.EXPIRE }
        assertThat(expiry.leaves).isEqualTo(Leaves.of(75))
        assertThat(expiry.correlationEventId).isEqualTo(lotId)
        assertThat(metrics.expired).isEqualTo(1)
    }

    /** A sweep with nothing to do returns zero and writes nothing — a fact, not an absence. */
    @Test
    fun `a sweep with nothing due reports zero`(): Unit = runBlocking {
        val ledger = FakeLedger()
        val metrics = RecordingMetrics()
        assertThat(ExpireLeavesUseCase(ledger, metrics, Clock.fixed(now, ZoneOffset.UTC)).sweep()).isZero()
        assertThat(ledger.entries).isEmpty()
    }
}

private inline fun <reified T> assertInstance(value: Any): T {
    assertThat(value).isInstanceOf(T::class.java)
    return value as T
}
