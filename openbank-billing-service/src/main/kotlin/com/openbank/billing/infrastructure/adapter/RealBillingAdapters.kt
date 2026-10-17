// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.openbank.billing.application.port.out.AccountBilling
import com.openbank.billing.application.port.out.AccountContextPort
import com.openbank.billing.application.port.out.AccountPartyLookupPort
import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.BillableAccountsPage
import com.openbank.billing.application.port.out.AccountingClassSource
import com.openbank.billing.application.port.out.ProductRevisionPort
import com.openbank.billing.application.port.out.RevisionTerms
import com.openbank.billing.domain.BillableFee
import com.openbank.billing.infrastructure.client.AccountRestClient
import com.openbank.billing.infrastructure.client.BalanceRestClient
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import com.openbank.libs.product.FeeContext
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
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
            balance = balance?.bookedAmount,
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
 * Reads billing terms from the catalog by PINNED revision (ADR-0341 D2): fee amounts from the
 * revision's AMOUNT price components, the accounting class from its `accountingClass` attribute.
 *
 * Deliberately does NOT swallow a catalog-read failure into an empty fee list: found live during
 * real-environment verification (product-catalog scaled to zero) that a `runCatching { ... }
 * .getOrDefault(emptyList())` here silently reported "this product has zero billable fees" whenever
 * the catalog was merely unreachable -- indistinguishable from a product that genuinely has none, and
 * the OPPOSITE of every other read port in this file, which all fail closed. A transient catalog blip
 * during the monthly sweep would otherwise silently under-charge every account for that cycle. Let
 * the exception propagate; [com.openbank.billing.application.usecase.FeeAssessmentService] converts
 * it into an explicit `skipReason`.
 *
 * Terms are cached by revision id and nothing else. That is safe only because a PUBLISHED or
 * SUPERSEDED revision is immutable (the catalog database refuses any change to its content); a DRAFT
 * revision is never cached, and is never returned by the lookup that produces a pin.
 */
@ApplicationScoped
class RestProductRevisionPort(
    @RestClient private val catalog: ProductCatalogRestClient,
    private val meters: MeterRegistry,
) : ProductRevisionPort {

    private val cache = object : LinkedHashMap<String, RevisionTerms>(CACHE_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RevisionTerms>?) = size > CACHE_CAPACITY
    }

    override suspend fun publishedRevisionIdFor(productId: String): String? = try {
        catalog.getPublishedRevisionForLegacyProduct(productId).awaitSuspending().id
    } catch (e: WebApplicationException) {
        // The catalog's own 404 means "unknown product / nothing published"; every other status is a fault.
        if (e.response.status == NOT_FOUND) null else throw e
    }

    override suspend fun terms(revisionId: String, currency: String): RevisionTerms {
        val cached = synchronized(cache) { cache[revisionId] }
        val all = cached ?: loadTerms(revisionId)
        return all.copy(fees = all.fees.filter { it.currency == currency })
    }

    private suspend fun loadTerms(revisionId: String): RevisionTerms {
        val revision = catalog.getRevision(revisionId).awaitSuspending()
        require(revision.id == revisionId) { "catalog answered revision ${revision.id} for $revisionId" }
        val attributes = revision.content.attributes
        val explicit = (attributes["accountingClass"] as? String)?.takeIf(String::isNotBlank)
        val derived = if (explicit == null) LegacyProductTypes.accountingClassOf(attributes["productType"] as? String) else null
        val legacyFees = LegacyFees.byPriceCode(attributes[LEGACY_DOCUMENT] as? String)
        val terms = RevisionTerms(
            revisionId = revisionId,
            accountingClass = explicit ?: derived,
            accountingClassSource = when {
                explicit != null -> AccountingClassSource.REVISION
                derived != null -> AccountingClassSource.DERIVED_FROM_LEGACY_PRODUCT_TYPE
                else -> AccountingClassSource.NONE
            },
            fees = revision.content.prices.filter { it.kind == "AMOUNT" && it.currency != null }.map { price ->
                val meta = legacyFees[price.code]
                BillableFee(
                    feeId = meta?.id ?: price.code,
                    name = meta?.name ?: price.code,
                    type = meta?.type ?: (price.cadence ?: "UNSPECIFIED"),
                    amount = price.value,
                    currency = checkNotNull(price.currency),
                    waivable = meta?.waivable ?: false,
                    waiveCondition = meta?.waiveCondition,
                )
            },
        )
        if (terms.accountingClassSource == AccountingClassSource.DERIVED_FROM_LEGACY_PRODUCT_TYPE) {
            meters.counter("billing.accounting_class.derived", "source", "legacy_product_type").increment()
        }
        // Only an immutable revision may be cached.
        if (revision.state == "PUBLISHED" || revision.state == "SUPERSEDED") {
            synchronized(cache) { cache[revisionId] = terms }
        }
        return terms
    }

    private companion object {
        const val LEGACY_DOCUMENT = "legacyDocument"
        const val NOT_FOUND = 404
        const val CACHE_CAPACITY = 512
        const val LOAD_FACTOR = 0.75f
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
