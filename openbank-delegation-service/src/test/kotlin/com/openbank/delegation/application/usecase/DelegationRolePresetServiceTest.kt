// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRolePresetRepository
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationRolePreset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationRolePresetServiceTest {

    private val repo = mockk<DelegationRolePresetRepository>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC)
    private val service = DelegationRolePresetService(repo, clock)

    private fun preset(name: String) = DelegationRolePreset(
        id = UUID.randomUUID(),
        name = name,
        description = "desc",
        resourceType = DelegationResourceType.ACCOUNT,
        capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        createdAt = OffsetDateTime.now(clock),
        updatedAt = OffsetDateTime.now(clock),
    )

    @Test
    fun `a retried create with the same name and resource type replays the original preset`(): Unit = runBlocking {
        // ADR-0292 / #8351: one preset per (name, resourceType) — a retry replays the original row
        // with no second save.
        val existing = preset("viewer")
        coEvery { repo.findByNameAndResourceType("viewer", DelegationResourceType.ACCOUNT) } returns existing

        val result = service.create(
            name = "viewer",
            description = "desc",
            resourceType = DelegationResourceType.ACCOUNT,
            capabilities = setOf(DelegationCapability.ACCOUNT_READ_BALANCES),
        )

        assertThat(result.id).isEqualTo(existing.id)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `the same name under a different resource type is a new preset`(): Unit = runBlocking {
        coEvery { repo.findByNameAndResourceType("viewer", DelegationResourceType.CARD) } returns null
        coEvery { repo.save(any()) } answers { firstArg() }

        val created = service.create(
            name = "viewer",
            description = "desc",
            resourceType = DelegationResourceType.CARD,
            capabilities = setOf(DelegationCapability.CARD_VIEW),
        )

        assertThat(created.resourceType).isEqualTo(DelegationResourceType.CARD)
        coVerify(exactly = 1) { repo.save(any()) }
    }
}
