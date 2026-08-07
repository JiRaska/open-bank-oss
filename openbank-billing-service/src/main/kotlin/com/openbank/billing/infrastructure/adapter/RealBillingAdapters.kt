// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountPartyLookupPort
import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.BillableAccountsPage
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

/**
 * Discovers the billing sweep's account batch from account-service's fleet-wide
 * `GET /api/v1/accounts/active` (ADR-0143 / issue #548 follow-up). Deliberately NOT
 * fail-open: an error propagates so the scheduler aborts (and logs) the sweep instead of
 * silently billing a partial batch — the monthly re-run is idempotent per
 * (cycleId, accountId, currency), so an aborted sweep is safely retried.
 */
@ApplicationScoped
class RestBillableAccountDiscoveryPort(@RestClient private val accounts: AccountRestClient) :
    BillableAccountDiscoveryPort {

    override suspend fun activeAccounts(limit: Int, afterCursor: String?): BillableAccountsPage {
        val page = accounts.listActiveAccounts(limit, afterCursor).awaitSuspending()
        return BillableAccountsPage(
            accountIds = page.data.map { it.id },
            nextCursor = if (page.pagination.hasNextPage) page.pagination.nextCursor else null,
        )
    }
}

/**
 * Reads a product's billable fee definitions from the product catalogue (ADR-0143 phase 2c).
 *
 * Deliberately does NOT swallow a catalog-read failure into an empty fee list: found live during
 * real-environment verification (product-catalog scaled to zero) that the previous
 * `runCatching { ... }.getOrDefault(emptyList())` here silently reported "this product has zero
 * billable fees" whenever the catalog was merely unreachable — indistinguishable from a product
 * that genuinely has no fees, and the OPPOSITE of every other read port in this file
 * ([RestAccountContextPort], [RestBillableAccountDiscoveryPort]), which all fail closed (skip or
 * abort) rather than silently substituting a confident-looking empty/zero result. A transient
 * catalog blip during the monthly scheduled sweep would otherwise silently under-charge every
 * account for that cycle with no operator-visible signal. Let the exception propagate — the
 * caller ([com.openbank.billing.application.usecase.FeeAssessmentService]) converts it into an
 * explicit `skipReason`, matching how an unresolvable account context is already handled.
 */
@ApplicationScoped
class RestProductCatalogPort(@RestClient private val catalog: ProductCatalogRestClient) : ProductCatalogPort {

    override suspend fun billableFees(productId: String, currency: String): List<BillableFee> {
        val fees = catalog.getProductFees(productId).awaitSuspending()
        return fees
            .filter { it.currency == currency }
            .map { BillableFee(it.id, it.name, it.type, it.amount, it.currency, it.waivable, it.waiveCondition) }
    }
}

/**
 * Reads an account's owning party id from account-service (ADR-0248 annual fee-summary
 * `partyRef`). Fail-closed like [RestAccountContextPort]: an unreadable account or a response
 * with no `partyId` returns `null` rather than fabricating a value — the caller skips that
 * account for this run instead of publishing a summary with a placeholder party reference.
 */
@ApplicationScoped
class RestAccountPartyLookupPort(@RestClient private val accounts: AccountRestClient) : AccountPartyLookupPort {

    override suspend fun partyIdFor(accountId: String): String? {
        val account = runCatching { accounts.getAccount(accountId).awaitSuspending() }.getOrNull()
            ?: return null
        return account.partyId
    }
}
