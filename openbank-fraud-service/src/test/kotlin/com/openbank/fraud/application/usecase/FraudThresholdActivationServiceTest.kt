// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.fraud.application.port.out.FraudThresholdRepository
import com.openbank.fraud.application.port.out.ThresholdSetProposal
import com.openbank.fraud.domain.rules.FraudThresholdFixtures
import com.openbank.fraud.domain.rules.FraudThresholdSet
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.ProposalState
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FraudThresholdActivationServiceTest {

    private val now = Instant.parse("2026-10-20T10:00:00Z")
    private val repo = InMemoryThresholdRepository()
    private val service = FraudThresholdActivationService(repo, Clock.fixed(now, ZoneOffset.UTC))

    private val v2 = FraudThresholdSet(
        version = 2,
        thresholds = FraudThresholdFixtures.SEED_V1.thresholds +
            (FraudThresholdSet.LARGE_SINGLE_TRANSACTION to mapOf("CZK" to BigDecimal("400000"))),
    )

    init {
        repo.rows[SEED_ID] = ThresholdSetProposal(
            SEED_ID, FraudThresholdFixtures.SEED_V1, FraudThresholdFixtures.SEED_V1_HASH, null,
            ProposalState.EXECUTED, "flyway", now.minusSeconds(60), "review", now.minusSeconds(60),
            activeFrom = now.minusSeconds(60),
        )
    }

    @Test
    fun `a proposal changes nothing that scoring reads`(): Unit = runBlocking {
        service.propose(v2, null, "maker")
        assertThat(repo.findActive(now)?.version).isEqualTo(1)
    }

    @Test
    fun `the maker cannot approve their own proposal`(): Unit = runBlocking {
        val p = service.propose(v2, null, "maker")
        assertThatThrownBy { runBlocking { service.decide(p.id, true, "maker", null) } }
            .isInstanceOf(MakerCheckerViolation::class.java)
        assertThat(repo.findActive(now)?.version).isEqualTo(1)
        assertThat(repo.rows.getValue(p.id).state).isEqualTo(ProposalState.PROPOSED)
    }

    @Test
    fun `a different checker's approval puts the set in force from approval`(): Unit = runBlocking {
        val p = service.propose(v2, null, "maker")
        val decided = service.decide(p.id, true, "checker", "calibrated")
        assertThat(decided.state).isEqualTo(ProposalState.EXECUTED)
        assertThat(decided.activeFrom).isEqualTo(now)
        assertThat(repo.findActive(now)?.version).isEqualTo(2)
    }

    @Test
    fun `a future effectiveFrom is honoured, not pulled forward`(): Unit = runBlocking {
        val later = now.plus(Duration.ofDays(1))
        val p = service.propose(v2, later, "maker")
        service.decide(p.id, true, "checker", null)
        assertThat(repo.findActive(now)?.version).isEqualTo(1)
        assertThat(repo.findActive(later)?.version).isEqualTo(2)
    }

    @Test
    fun `rejection leaves the active set alone`(): Unit = runBlocking {
        val p = service.propose(v2, null, "maker")
        assertThat(service.decide(p.id, false, "checker", "no").state).isEqualTo(ProposalState.REJECTED)
        assertThat(repo.findActive(now)?.version).isEqualTo(1)
    }

    @Test
    fun `a past effectiveFrom, a stale version and a lost race are refused`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.propose(v2, now.minusSeconds(1), "maker") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { runBlocking { service.propose(FraudThresholdFixtures.SEED_V1, null, "maker") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        val p = service.propose(v2, null, "maker")
        repo.loseNextRace = true
        assertThatThrownBy { runBlocking { service.decide(p.id, true, "checker", null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an identical retried proposal replays instead of stacking a duplicate`(): Unit = runBlocking {
        val first = service.propose(v2, null, "maker")
        val again = service.propose(v2, null, "maker")
        assertThat(again.id).isEqualTo(first.id)
        assertThat(repo.rows.values.count { it.state == ProposalState.PROPOSED }).isEqualTo(1)
    }

    private class InMemoryThresholdRepository : FraudThresholdRepository {
        val rows = linkedMapOf<UUID, ThresholdSetProposal>()
        var loseNextRace = false

        override suspend fun findActive(at: Instant) = rows.values
            .filter { it.state == ProposalState.EXECUTED && !it.activeFrom!!.isAfter(at) }
            .maxByOrNull { it.set.version }?.set

        override suspend fun insert(proposal: ThresholdSetProposal) {
            rows[proposal.id] = proposal
        }

        override suspend fun findById(id: UUID) = rows[id]

        override suspend fun findByState(state: ProposalState) = rows.values.filter { it.state == state }

        override suspend fun maxLiveVersion() = rows.values
            .filter { it.state in setOf(ProposalState.PROPOSED, ProposalState.APPROVED, ProposalState.EXECUTED) }
            .maxOfOrNull { it.set.version }

        override suspend fun compareAndSetDecision(decided: ThresholdSetProposal): Int {
            if (loseNextRace) return 0.also { loseNextRace = false }
            if (rows[decided.id]?.state != ProposalState.PROPOSED) return 0
            rows[decided.id] = decided
            return 1
        }
    }

    private companion object {
        val SEED_ID: UUID = UUID.fromString("01920000-0000-7000-8000-000000000347")
    }
}
