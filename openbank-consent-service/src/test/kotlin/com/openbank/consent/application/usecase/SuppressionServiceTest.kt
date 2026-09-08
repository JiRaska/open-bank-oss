// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.usecase

import com.openbank.consent.application.port.out.SuppressionRepository
import com.openbank.consent.domain.model.Suppression
import com.openbank.consent.domain.model.SuppressionReason
import com.openbank.consent.domain.model.SuppressionScope
import com.openbank.libs.domain.event.DomainEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SuppressionServiceTest {

    private val repo = mockk<SuppressionRepository>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
    private val service = SuppressionService(repo, clock)

    private fun suppression(partyId: UUID, scope: SuppressionScope, value: String?) = Suppression(
        id = UUID.randomUUID(),
        partyId = partyId,
        scope = scope,
        value = value,
        reason = SuppressionReason.CUSTOMER_OPTOUT,
        source = "test",
        createdBy = "tester",
        createdAt = java.time.OffsetDateTime.now(clock),
        revokedAt = null,
        revokedBy = null,
    )

    @Test
    fun `a retried create with the same natural key replays the original suppression`(): Unit = runBlocking {
        // ADR-0293 / #8351: one active suppression per (partyId, scope, value) — a retry must
        // replay the original row with no second save and no second event.
        val partyId = UUID.randomUUID()
        val existing = suppression(partyId, SuppressionScope.TOPIC, "marketing")
        coEvery { repo.findActiveByParty(partyId) } returns listOf(existing)

        val result = service.create(
            partyId = partyId,
            scope = SuppressionScope.TOPIC,
            value = "marketing",
            reason = SuppressionReason.CUSTOMER_OPTOUT,
            source = "test",
            createdBy = "tester",
        )

        assertThat(result.id).isEqualTo(existing.id)
        coVerify(exactly = 0) { repo.save(any(), any<DomainEvent>()) }
    }

    @Test
    fun `a revoked suppression does not block a fresh create of the same natural key`(): Unit = runBlocking {
        // The dedup key covers ACTIVE rows only (partial index) — a revoked row is history,
        // and re-suppressing the same value is a legitimate new fact.
        val partyId = UUID.randomUUID()
        coEvery { repo.findActiveByParty(partyId) } returns emptyList()
        coEvery { repo.save(any(), any<DomainEvent>()) } answers { firstArg() }

        val created = service.create(
            partyId = partyId,
            scope = SuppressionScope.ALL,
            value = null,
            reason = SuppressionReason.CUSTOMER_OPTOUT,
            source = "test",
            createdBy = "tester",
        )

        assertThat(created.scope).isEqualTo(SuppressionScope.ALL)
        coVerify(exactly = 1) { repo.save(any(), any<DomainEvent>()) }
    }
}
