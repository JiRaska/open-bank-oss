// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.interest.application.port.out

import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.interest.domain.model.InterestRateConfig
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Outbound persistence port for interest rate configurations (reactive, Mutiny). */
interface InterestRateConfigRepository {
    fun save(config: InterestRateConfig): Uni<InterestRateConfig>
    fun findById(id: UUID): Uni<InterestRateConfig?>
    fun findByProductId(productId: String): Uni<List<InterestRateConfig>>
    fun findAll(): Uni<List<InterestRateConfig>>
    fun findActiveForProduct(productId: String, date: LocalDate): Uni<InterestRateConfig?>
    fun update(config: InterestRateConfig): Uni<InterestRateConfig>
}

/** Outbound persistence port for daily interest accruals (reactive, Mutiny). */
interface InterestAccrualRepository {
    fun save(accrual: InterestAccrual): Uni<InterestAccrual>
    fun findAll(): Uni<List<InterestAccrual>>
    fun findByAccountId(accountId: UUID, from: LocalDate?, to: LocalDate?): Uni<List<InterestAccrual>>
    fun findPendingCapitalization(accountId: UUID, toDate: LocalDate): Uni<List<InterestAccrual>>
    fun markCapitalized(ids: List<UUID>, capitalizedAt: OffsetDateTime): Uni<Int>
    fun sumAccrued(accountId: UUID, from: LocalDate, to: LocalDate): Uni<BigDecimal>
}

/** Outbound persistence port for interest capitalization events (reactive, Mutiny). */
interface InterestCapitalizationRepository {
    fun save(cap: InterestCapitalization): Uni<InterestCapitalization>
    fun findByAccountId(accountId: UUID): Uni<List<InterestCapitalization>>
}
