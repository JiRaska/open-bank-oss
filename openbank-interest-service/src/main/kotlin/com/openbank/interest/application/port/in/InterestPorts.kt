// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.`in`

import com.openbank.interest.domain.model.*
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

interface AccrueInterestUseCase {
    fun accrue(request: AccrualRequest): Uni<InterestAccrual>
    fun accrueAll(date: LocalDate): Uni<Int>
}

interface CapitalizeInterestUseCase {
    fun capitalize(accountId: UUID, productId: String, toDate: LocalDate): Uni<InterestCapitalization>
    fun capitalizeAll(toDate: LocalDate): Uni<Int>
}

interface GetAccrualsUseCase {
    fun listAllAccruals(): Uni<List<InterestAccrual>>
    fun getAccruals(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>>
    fun getSummary(accountId: UUID, from: LocalDate, to: LocalDate): Uni<AccrualSummary>
    fun getCapitalizations(accountId: UUID): Uni<List<InterestCapitalization>>
}

interface ManageRateConfigUseCase {
    fun createConfig(config: InterestRateConfig): Uni<InterestRateConfig>
    fun getConfig(id: UUID): Uni<InterestRateConfig?>
    fun listConfigs(productId: String?): Uni<List<InterestRateConfig>>
    fun deactivateConfig(id: UUID): Uni<InterestRateConfig>
}
