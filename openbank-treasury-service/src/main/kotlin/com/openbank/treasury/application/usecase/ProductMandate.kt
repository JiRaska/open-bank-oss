// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.model.ProductLimit
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.ProductTypePayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceStatus
import java.time.Clock

/**
 * ADR-0344 catalogue: the product mandate's authority is the reference store's ACTIVE
 * `product-type` record effective today; configuration (`openbank.treasury.product-limits`) is the
 * seed for products that have no record yet. A record with `permitted = false` removes the product
 * — fail-closed exactly like a product missing from configuration. A record naming an unknown
 * product is ignored (it cannot permit anything that does not exist).
 */
class ProductMandate(
    private val references: ReferenceRepository,
    private val seed: ProductLimitPolicy,
    private val clock: Clock,
) {
    suspend fun policy(): ProductLimitPolicy {
        val today = AccountingClock.bank(clock).today()
        val records = references.list(ReferenceKind.PRODUCT_TYPE, ReferenceStatus.ACTIVE, today)
            .map { it.payload as ProductTypePayload }
            .mapNotNull { p -> ProductType.entries.firstOrNull { it.name == p.code }?.let { it to p } }
            .toMap()
        val limits = ProductType.entries.mapNotNull { product ->
            val record = records[product]
            when {
                record == null -> seed.limitFor(product)
                !record.permitted -> null
                else -> ProductLimit(product, record.maxPrincipal, record.maxTenorDays)
            }
        }
        return ProductLimitPolicy(limits)
    }
}
