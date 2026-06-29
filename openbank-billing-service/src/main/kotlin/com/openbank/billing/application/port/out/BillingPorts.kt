// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.port.out

import com.openbank.billing.domain.BillableFee
import com.openbank.libs.product.FeeContext

/**
 * The account's product identity plus the resolved fee-evaluation context for a currency.
 * `null` from [AccountContextPort.resolve] means "could not resolve" — billing fails closed
 * and does not charge (ADR-0143).
 */
data class AccountBilling(val productId: String, val context: FeeContext)

/**
 * Reads the account-side facts a waiver rule is evaluated against (balance, turnover, segment,
 * currency) plus the account's product. Backed by account-service + balance-service reactive
 * REST clients in phase 2c; a no-op stub satisfies CDI in the 2b skeleton.
 */
interface AccountContextPort {
    suspend fun resolve(accountId: String, currency: String): AccountBilling?
}

/**
 * Reads the billable fee definitions for a product/currency from the product catalogue
 * (`GET /api/v1/fees`). Backed by a reactive REST client in phase 2c.
 */
interface ProductCatalogPort {
    suspend fun billableFees(productId: String, currency: String): List<BillableFee>
}
