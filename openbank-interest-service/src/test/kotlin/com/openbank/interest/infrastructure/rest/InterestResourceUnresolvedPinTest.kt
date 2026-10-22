// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.interest.infrastructure.rest

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.application.port.`in`.GetAccrualsUseCase
import com.openbank.interest.application.port.`in`.ManageRateConfigUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.CatalogRevisionUnresolvableException
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** ADR-0341: an unresolvable pin is a visible 409 on the accrual endpoint, not a generic 500. */
class InterestResourceUnresolvedPinTest {
    @Test
    fun `accrue answers 409 when the pinned revision cannot be resolved`() {
        val accrue = mockk<AccrueInterestUseCase>()
        val account = UUID.randomUUID()
        every { accrue.accrue(any()) } returns
            Uni.createFrom().failure(CatalogRevisionUnresolvableException(account, "SAVINGS", "EUR"))
        val resource = InterestResource(
            accrue,
            mockk<CapitalizeInterestUseCase>(),
            mockk<GetAccrualsUseCase>(),
            mockk<ManageRateConfigUseCase>(),
            Clock.systemUTC(),
        )

        val response = resource.accrue(
            AccrualRequest(account, "SAVINGS", BigDecimal("100.00"), "EUR", LocalDate.of(2027, 1, 1)),
        ).await().indefinitely()

        assertThat(response.status).isEqualTo(409)
    }
}
