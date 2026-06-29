// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.ProductCatalogPort
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.infrastructure.client.AccountRestClient
import com.openbank.billing.infrastructure.client.BalanceRestClient
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.libs.product.FeeContext
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resolves the account's product + fee-evaluation context by reading account-service and
 * balance-service (ADR-0143 phase 2c, read path). Fail-closed: if the account cannot be read the
 * whole assessment is skipped (returns null); if the balance cannot be read, `balance` is left
 * null so balance-conditioned waivers fail closed (charge) rather than waive on a missing input.
 *
 * `monthlyTurnover` / `aggregatePocketBalance` / `segment` are not yet exposed by any read port,
 * so they stay null — waivers that reference them fail closed until those ports land.
 */
@ApplicationScoped
class RestAccountContextPort(
    @RestClient private val accounts: AccountRestClient,
    @RestClient private val balances: BalanceRestClient,
) : AccountContextPort {

    override suspend fun resolve(accountId: String, currency: String): AccountBilling? {
        val account = runCatching { accounts.getAccount(accountId).awaitSuspending() }.getOrNull()
            ?: return null
        val balance = runCatching { balances.getBalance(accountId, currency).awaitSuspending() }.getOrNull()
        val context = FeeContext(
            balance = balance?.currentBalance,
            monthlyTurnover = null,
            aggregatePocketBalance = null,
            segment = null,
            currency = currency,
        )
        return AccountBilling(productId = account.productId, context = context)
    }
}

/** Reads a product's billable fee definitions from the product catalogue (ADR-0143 phase 2c). */
@ApplicationScoped
class RestProductCatalogPort(@RestClient private val catalog: ProductCatalogRestClient) : ProductCatalogPort {

    override suspend fun billableFees(productId: String, currency: String): List<BillableFee> {
        val fees = runCatching { catalog.getProductFees(productId).awaitSuspending() }.getOrDefault(emptyList())
        return fees
            .filter { it.currency == currency }
            .map { BillableFee(it.id, it.name, it.type, it.amount, it.currency, it.waivable, it.waiveCondition) }
    }
}
