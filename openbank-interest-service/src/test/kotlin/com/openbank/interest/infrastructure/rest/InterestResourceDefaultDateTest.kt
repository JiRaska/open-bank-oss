// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.`in`.GetAccrualsUseCase
import com.openbank.interest.application.port.`in`.ManageRateConfigUseCase
import com.openbank.interest.domain.model.InterestCapitalization
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * A manual run with no `date` defaults to the ledger's accounting day (Europe/Prague), not the UTC
 * calendar date: between 00:00 and 02:00 Prague time those differ, and the manual run would otherwise
 * accrue/capitalize for a day the ledger has already moved past (or not yet opened).
 */
class InterestResourceDefaultDateTest {

    // 2026-10-02T22:30Z is 00:30 on 2026-10-03 in Prague (CEST): UTC date 10-02, accounting day 10-03.
    private val clock = Clock.fixed(Instant.parse("2026-10-02T22:30:00Z"), ZoneOffset.UTC)
    private val accrue = mockk<AccrueInterestUseCase>()
    private val capitalize = mockk<CapitalizeInterestUseCase>()
    private val resource = InterestResource(
        accrue,
        capitalize,
        mockk<GetAccrualsUseCase>(),
        mockk<ManageRateConfigUseCase>(),
        clock,
    )

    @Test
    fun `manual accrue-all without a date defaults to the Prague accounting day`() {
        every { accrue.accrueAll(any()) } returns Uni.createFrom().item(0)

        resource.accrueAll(null).await().indefinitely()

        verify(exactly = 1) { accrue.accrueAll(LocalDate.of(2026, 10, 3)) }
    }

    @Test
    fun `manual capitalize without a toDate defaults to the Prague accounting day`() {
        val account = UUID.fromString("11111111-1111-1111-1111-111111111111")
        every { capitalize.capitalize(any(), any(), any()) } returns
            Uni.createFrom().item(mockk<InterestCapitalization>(relaxed = true))

        resource.capitalize(account, "SAVINGS", null).await().indefinitely()

        verify(exactly = 1) { capitalize.capitalize(account, "SAVINGS", LocalDate.of(2026, 10, 3)) }
    }
}
