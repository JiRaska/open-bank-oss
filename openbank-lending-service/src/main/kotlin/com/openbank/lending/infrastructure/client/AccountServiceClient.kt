// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.client

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.util.UUID

/**
 * Resolves the borrower's own CURRENT account for a disbursement (`GET /api/v1/accounts?partyId=`,
 * `Roles.API`). Mirrors `openbank-domestic-payment`'s `AccountServiceClient` shape.
 *
 * Build-time gated by `lending.borrower-credit.backend=rest`, the same platform realization
 * pattern (ADR-0045) `RestLedgerPostingAdapter` uses for `lending.ledger.backend`: when unset the
 * `@Default` no-op in `NoOpLendingAdapters.kt` stays bound and the service builds and boots with
 * zero external dependency. Gated together with [BorrowerCreditClient] under the SAME property —
 * a disbursement needs both the lookup and the credit, and enabling one without the other is not
 * a state that should be reachable.
 */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
interface AccountServiceRestClient {
    @GET
    fun listAccounts(@QueryParam("partyId") partyId: UUID, @QueryParam("limit") limit: Int): Uni<AccountPage>
}

data class AccountPage(val data: List<AccountSummary> = emptyList())
data class AccountSummary(val id: UUID, val accountType: String, val currencyCode: String, val status: String)

// Same alternative priority as RestLedgerPostingAdapter (this module).
private const val REST_ADAPTER_PRIORITY = 100
private const val LIST_LIMIT = 50
private const val ACCOUNT_STATUS_ACTIVE = "ACTIVE"
private const val ACCOUNT_TYPE_CURRENT = "CURRENT"

// `@Unremovable`: see the note on BorrowerCreditClient — a test asserts this bean's PRESENCE
// (LedgerAdapterBindingIT, #6057) and the test-scope stub would otherwise make it removable.
@io.quarkus.arc.Unremovable
@ApplicationScoped
@Alternative
@Priority(REST_ADAPTER_PRIORITY)
@IfBuildProperty(name = "lending.borrower-credit.backend", stringValue = "rest")
class AccountServiceClient(
    @org.eclipse.microprofile.rest.client.inject.RestClient private val client: AccountServiceRestClient,
) : BorrowerAccountLookupPort {

    // Retried at the transport level, not recovered to null here: a lookup failure and "this party
    // genuinely has no CURRENT account" are different facts, and the caller (bookLoan) needs to
    // fail loud on either rather than silently disburse with no idea where the money should go.
    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(CALL_TIMEOUT_MS)
    override fun findCurrentAccount(partyId: UUID, currency: String): Uni<UUID?> =
        client.listAccounts(partyId, LIST_LIMIT)
            .map { page ->
                page.data.firstOrNull {
                    it.accountType == ACCOUNT_TYPE_CURRENT &&
                        it.currencyCode == currency &&
                        it.status == ACCOUNT_STATUS_ACTIVE
                }?.id
            }

    private companion object {
        // Resilience tuning — mirrors LedgerCallGuard (this module) / LedgerPostingAdapter
        // (openbank-billing-service).
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 300L
        const val RETRY_JITTER_MS = 150L
        const val CALL_TIMEOUT_MS = 2000L
    }
}
