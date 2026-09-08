// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.out.DelegationPortfolioRepository
import com.openbank.delegation.domain.model.DelegationPortfolio
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class DelegationPortfolioServiceTest {
    private val repository = mockk<DelegationPortfolioRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC)
    private val service = DelegationPortfolioService(repository, clock)
    private val owner = UUID.randomUUID()

    @Test
    fun `a caller outside the resolved business profile cannot create a portfolio`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { service.create(UUID.randomUUID(), owner, "Treasury", setOf(UUID.randomUUID())) }
        }.isInstanceOf(DelegationPortfolioAccessDenied::class.java)

        coVerify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `a portfolio cannot be an empty client side grouping`(): Unit = runBlocking {
        assertThatThrownBy {
            DelegationPortfolio(ownerPartyId = owner, name = "Treasury", accountIds = emptySet(), createdAt = OffsetDateTime.now(clock), updatedAt = OffsetDateTime.now(clock))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("at least one account")
    }

    @Test
    fun `read refuses a stale or different business principal even when it knows the portfolio id`(): Unit = runBlocking {
        val portfolio = DelegationPortfolio(ownerPartyId = owner, name = "Treasury", accountIds = setOf(UUID.randomUUID()), createdAt = OffsetDateTime.now(clock), updatedAt = OffsetDateTime.now(clock))
        coEvery { repository.findById(portfolio.id) } returns portfolio

        assertThatThrownBy { runBlocking { service.get(UUID.randomUUID(), portfolio.id) } }
            .isInstanceOf(DelegationPortfolioAccessDenied::class.java)
    }
}
