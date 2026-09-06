// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.application.usecase

import com.openbank.tppregistry.application.port.`in`.CheckTppAuthorizationQuery
import com.openbank.tppregistry.application.port.`in`.ListTppsQuery
import com.openbank.tppregistry.application.port.out.TppRepository
import com.openbank.tppregistry.domain.model.EbaRegisterSyncState
import com.openbank.tppregistry.domain.model.TppEntry
import com.openbank.tppregistry.domain.model.TppRole
import com.openbank.tppregistry.domain.model.TppStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The EBA-sync and list paths, plus the QWAC-expiry BOUNDARY (`isBefore`, so an expiry of *today*
 * is still valid) that the existing service test does not pin.
 */
class TppRegistrySyncAndListTest {

    private val repo = mockk<TppRepository>()
    private val fixedInstant: Instant = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC).toInstant()
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val service = TppRegistryService(repo, clock)

    private fun tpp(qwacExpiresAt: LocalDate?) = TppEntry(
        id = UUID.randomUUID(),
        tppId = "CZ-CNB-1",
        name = "Acme",
        countryCode = "CZ",
        nca = "CNB",
        roles = setOf(TppRole.AISP),
        status = TppStatus.ACTIVE,
        qwacSubjectDn = null,
        qsealSubjectDn = null,
        qwacExpiresAt = qwacExpiresAt,
        qsealExpiresAt = null,
        registeredAt = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC),
        updatedAt = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC),
        blacklistedAt = null,
        blacklistReason = null,
    )

    @Test
    fun `a QWAC expiring today is still valid - the check is isBefore, not isAfter`(): Unit = runBlocking {
        coEvery { repo.findByTppId("CZ-CNB-1") } returns tpp(LocalDate.of(2026, 3, 4))

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("CZ-CNB-1", TppRole.AISP))

        assertThat(result.authorized).isTrue()
        assertThat(result.reason).isNull()
    }

    @Test
    fun `a QWAC that expired yesterday denies authorization`(): Unit = runBlocking {
        coEvery { repo.findByTppId("CZ-CNB-1") } returns tpp(LocalDate.of(2026, 3, 3))

        val result = service.checkAuthorization(CheckTppAuthorizationQuery("CZ-CNB-1", TppRole.AISP))

        assertThat(result.authorized).isFalse()
        assertThat(result.reason).isEqualTo("QWAC certificate expired")
    }

    @Test
    fun `a null QWAC expiry is treated as no expiry rather than as expired`(): Unit = runBlocking {
        coEvery { repo.findByTppId("CZ-CNB-1") } returns tpp(null)

        assertThat(service.checkAuthorization(CheckTppAuthorizationQuery("CZ-CNB-1", TppRole.AISP)).authorized)
            .isTrue()
    }

    @Test
    fun `listTpps forwards every filter to the repository unchanged`(): Unit = runBlocking {
        val entries = listOf(tpp(null))
        coEvery { repo.list("CZ", TppRole.PISP, TppStatus.REVOKED, 10, "cursor") } returns entries

        val result = service.listTpps(ListTppsQuery("CZ", TppRole.PISP, TppStatus.REVOKED, 10, "cursor"))

        assertThat(result).isSameAs(entries)
        coVerify(exactly = 1) { repo.list("CZ", TppRole.PISP, TppStatus.REVOKED, 10, "cursor") }
    }

    @Test
    fun `triggerEbaSync persists the state it returns, stamped from the clock`(): Unit = runBlocking {
        val saved = slot<EbaRegisterSyncState>()
        coEvery { repo.saveSyncState(capture(saved)) } returns Unit

        val result = service.triggerEbaSync()

        assertThat(result.lastSyncAt).isEqualTo(OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC))
        assertThat(result.lastSuccessAt).isNull()
        assertThat(result.totalEntries).isEqualTo(0)
        assertThat(result.errorMessage).isEqualTo("EBA sync not yet implemented — manual registration only")
        assertThat(saved.captured).isEqualTo(result)
    }

    @Test
    fun `getSyncState returns an empty state when nothing has ever been persisted`(): Unit = runBlocking {
        coEvery { repo.getSyncState() } returns null

        assertThat(service.getSyncState()).isEqualTo(EbaRegisterSyncState(null, null, 0, null))
    }

    @Test
    fun `getSyncState returns the persisted state when there is one`(): Unit = runBlocking {
        val persisted = EbaRegisterSyncState(
            OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC),
            42,
            null,
        )
        coEvery { repo.getSyncState() } returns persisted

        assertThat(service.getSyncState()).isEqualTo(persisted)
    }
}
