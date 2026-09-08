// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationRecertificationRepository
import com.openbank.delegation.domain.model.DelegationRecertificationAudience
import com.openbank.delegation.domain.model.DelegationRecertificationCycle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationRecertificationServiceTest {
    private val repository = mockk<DelegationRecertificationRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-09-08T09:00:00Z"), ZoneOffset.UTC)
    private val grantor = UUID.randomUUID()
    private val cycle = sampleCycle()
    private val service = DelegationRecertificationService(repository, clock)

    @Test
    fun `a grantor lists only their own pending recertifications`(): Unit = runBlocking {
        coEvery { repository.listPendingByGrantor(grantor) } returns listOf(cycle)

        val result = service.listPending(grantor, grantor)

        assertThat(result).containsExactly(cycle)
        coVerify(exactly = 1) { repository.listPendingByGrantor(grantor) }
    }

    @Test
    fun `a caller cannot list another party's recertifications`(): Unit = runBlocking {
        assertThatThrownBy { runBlocking { service.listPending(grantor, UUID.randomUUID()) } }
            .isInstanceOf(DelegationCallerMismatchException::class.java)
        coVerify(exactly = 0) { repository.listPendingByGrantor(any()) }
    }

    @Test
    fun `confirmation is explicit and carries the authoritative clock time`(): Unit = runBlocking {
        coEvery { repository.confirm(cycle.id, grantor, any()) } returns cycle

        val result = service.confirm(cycle.id, grantor, grantor)

        assertThat(result).isEqualTo(cycle)
        coVerify(exactly = 1) {
            repository.confirm(cycle.id, grantor, OffsetDateTime.now(clock))
        }
    }

    private fun sampleCycle() = DelegationRecertificationCycle(
        delegationId = UUID.randomUUID(),
        grantorPartyId = grantor,
        expectedLifecycleRevision = 4,
        audience = DelegationRecertificationAudience.CORPORATE,
        sequence = 1,
        dueAt = OffsetDateTime.now(clock).minusDays(1),
        createdAt = OffsetDateTime.now(clock).minusDays(1),
    )
}
