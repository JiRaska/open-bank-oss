// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CompliancePackActivationRepository
import com.openbank.lending.infrastructure.persistence.entity.CompliancePackActivationEntity
import com.openbank.libs.governance.MakerCheckerViolation
import com.openbank.libs.governance.ProposalState
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import com.openbank.libs.lending.compliance.PackProductType
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Covers ADR-0212 D4 service mechanics: propose, four-eyes decide, activation into the registry. */
class CompliancePackActivationServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC)
    private val registry = CompliancePackRegistry()
    private val repository = InMemoryActivationRepository()
    private val service = CompliancePackActivationService(repository, registry, clock)

    private val czPackJson = """
        {
          "jurisdiction": "CZ",
          "productType": "CONSUMER_CREDIT",
          "version": 1,
          "effectiveFrom": "2026-08-01",
          "coolingOffDays": 14,
          "aprDisclosure": { "label": "RPSN", "locale": "cs-CZ" },
          "terminationRules": { "noticePeriodDays": 30, "permittedGrounds": ["DEFAULT_DPD"] }
        }
    """.trimIndent()

    @Test
    fun `propose persists a pending activation without touching the registry`() {
        val view = service.propose(czPackJson, "compliance-maker").await().indefinitely()

        assertThat(view.state).isEqualTo(ProposalState.PROPOSED)
        assertThat(view.proposedBy).isEqualTo("compliance-maker")
        assertThat(view.contentHash).hasSize(64)
        assertThat(registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15")))
            .isNull()
    }

    @Test
    fun `invalid pack JSON is refused at proposal time`() {
        assertThatThrownBy { service.propose("{ \"bogus\": true }", "maker") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `approve by a different checker activates the pack in the registry`() {
        val pending = service.propose(czPackJson, "maker-1").await().indefinitely()
        val decided = service.decide(pending.id, approve = true, checker = "checker-2", reason = "LGTM")
            .await().indefinitely()

        assertThat(decided.state).isEqualTo(ProposalState.EXECUTED)
        assertThat(decided.decidedBy).isEqualTo("checker-2")
        assertThat(
            registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15")),
        ).isNotNull()
    }

    @Test
    fun `maker cannot approve their own proposal`() {
        val pending = service.propose(czPackJson, "same-person").await().indefinitely()

        assertThatThrownBy {
            service.decide(pending.id, approve = true, checker = "same-person", reason = null).await().indefinitely()
        }.isInstanceOf(MakerCheckerViolation::class.java)
    }

    @Test
    fun `reject leaves the registry untouched`() {
        val pending = service.propose(czPackJson, "maker-1").await().indefinitely()
        val decided = service.decide(pending.id, approve = false, checker = "checker-2", reason = "bad legal basis")
            .await().indefinitely()

        assertThat(decided.state).isEqualTo(ProposalState.REJECTED)
        assertThat(registry.activePack("CZ", PackProductType.CONSUMER_CREDIT, LocalDate.parse("2026-08-15")))
            .isNull()
    }

    @Test
    fun `deciding an already-decided proposal is refused`() {
        val pending = service.propose(czPackJson, "maker-1").await().indefinitely()
        service.decide(pending.id, approve = true, checker = "checker-2", reason = null).await().indefinitely()

        assertThatThrownBy {
            service.decide(pending.id, approve = true, checker = "checker-3", reason = null).await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `pending list shows only undecided proposals`() {
        service.propose(czPackJson, "maker-1").await().indefinitely()
        val second = service.propose(czPackJson.replace("\"version\": 1", "\"version\": 2"), "maker-1")
            .await().indefinitely()
        service.decide(second.id, approve = true, checker = "checker-2", reason = null).await().indefinitely()

        val pending = service.listPending().await().indefinitely()
        assertThat(pending).hasSize(1)
        assertThat(pending.single().packVersion).isEqualTo(1)
    }

    private class InMemoryActivationRepository : CompliancePackActivationRepository {
        private val rows = mutableMapOf<UUID, CompliancePackActivationEntity>()

        override fun save(entity: CompliancePackActivationEntity): Uni<CompliancePackActivationEntity> {
            rows[entity.id] = entity
            return Uni.createFrom().item(entity)
        }

        override fun findById(id: UUID): Uni<CompliancePackActivationEntity?> = Uni.createFrom().item(rows[id])

        override fun findByState(state: ProposalState): Uni<List<CompliancePackActivationEntity>> =
            Uni.createFrom().item(rows.values.filter { it.state == state })

        override fun findActivated(): Uni<List<CompliancePackActivationEntity>> = Uni.createFrom().item(
            rows.values.filter {
                it.state == ProposalState.APPROVED || it.state == ProposalState.EXECUTED
            },
        )
    }
}
