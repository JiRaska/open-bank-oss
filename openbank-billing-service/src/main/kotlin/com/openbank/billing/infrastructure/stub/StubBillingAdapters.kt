// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.stub

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.ProductCatalogPort
import com.openbank.billing.domain.BillableFee
import jakarta.enterprise.context.ApplicationScoped

/**
 * Placeholder adapters that satisfy CDI so the service boots, but read nothing and post nothing —
 * the ADR-0143 phase-2b skeleton moves no money. Phase 2c replaces these with reactive REST clients
 * to account-service + balance-service (context) and product-catalog (fee definitions).
 *
 * [AccountContextPort.resolve] returning `null` makes every assessment fail closed (skip, never
 * charge), which is the correct safe default until the real clients land.
 */
@ApplicationScoped
class StubAccountContextPort : AccountContextPort {
    override suspend fun resolve(accountId: String, currency: String): AccountBilling? = null
}

@ApplicationScoped
class StubProductCatalogPort : ProductCatalogPort {
    override suspend fun billableFees(productId: String, currency: String): List<BillableFee> = emptyList()
}
