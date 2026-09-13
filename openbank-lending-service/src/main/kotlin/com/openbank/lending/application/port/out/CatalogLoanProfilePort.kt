// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.libs.lending.AmortizationMethod
import io.smallrye.mutiny.Uni
import java.math.BigDecimal
import java.util.UUID

/** Published product terms accepted by lending at origination. */
data class CatalogLoanProfile(
    val snapshot: CatalogLoanSnapshot,
    val currency: String,
    val tenorMonths: Int,
    val method: AmortizationMethod,
    val nominalAnnualRate: BigDecimal,
    val minPrincipal: BigDecimal?,
    val maxPrincipal: BigDecimal?,
)

/**
 * Reads a published immutable loan offering from product-catalog. A catalog-selected loan fails
 * closed if its exact revision cannot be resolved or does not satisfy the lending profile.
 */
interface CatalogLoanProfilePort {
    fun resolvePublished(offeringId: UUID): Uni<CatalogLoanProfile>
}
