// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

/** A product's fee definition as returned by product-catalog `GET /api/v1/products/{id}/fees`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FeeDto(
    val id: String,
    val name: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val waivable: Boolean = false,
    val waiveCondition: String? = null,
)

/**
 * The subset of account-service `GET /api/v1/accounts/{id}` the billing read path needs.
 * [partyId] was added for the ADR-0248 annual fee-summary's `partyRef` field — account-service's
 * `AccountResponse` already returns it (`partyId: UUID`, `AccountDtos.kt`), this DTO simply had
 * never mapped it before there was a reader.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountDto(
    val id: String,
    val productId: String,
    val currencyCode: String,
    val status: String? = null,
    val partyId: String? = null,
)

/** The subset of balance-service `GET /api/v1/balances/{accountId}/{currency}` we need. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceDto(val accountId: String, val currency: String, val currentBalance: BigDecimal)

/** The subset of account-service's cursor-page envelope the discovery sweep needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountPageInfoDto(val hasNextPage: Boolean = false, val nextCursor: String? = null)

/** One page of account-service `GET /api/v1/accounts/active` (ADR-0143 billing discovery). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountPageDto(
    val data: List<AccountDto> = emptyList(),
    val pagination: AccountPageInfoDto = AccountPageInfoDto(),
)

/**
 * Product-catalog fee definitions (the catalog is the fee source-of-truth). product-catalog now
 * authenticates its callers (issue #401), so propagate an openbank-services bearer like the other
 * clients below — a fee assessment cannot silently fall back to "no fees" on a 401.
 */
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ProductCatalogRestClient {
    @GET
    @Path("/products/{id}/fees")
    fun getProductFees(@PathParam("id") productId: String): Uni<List<FeeDto>>
}

/** Account read — carries account PII, so the call propagates an OIDC service token. */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface AccountRestClient {
    @GET
    @Path("/accounts/{id}")
    fun getAccount(@PathParam("id") accountId: String): Uni<AccountDto>

    /** Fleet-wide ACTIVE-account sweep, cursor-paginated (ADR-0143 billing discovery). */
    @GET
    @Path("/accounts/active")
    fun listActiveAccounts(@QueryParam("limit") limit: Int, @QueryParam("cursor") cursor: String?): Uni<AccountPageDto>
}

/** Balance read — balance is PII, so the call propagates an OIDC service token. */
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface BalanceRestClient {
    @GET
    @Path("/balances/{accountId}/{currency}")
    fun getBalance(@PathParam("accountId") accountId: String, @PathParam("currency") currency: String): Uni<BalanceDto>
}

// --- Ledger posting (ADR-0143 phase 2c-ii) --------------------------------------------------

/**
 * Mirrors `openbank-ledger-service`'s `JournalLineRequest` field-for-field
 * (see `LedgerResource.PostJournalLineRequest`).
 */
data class LedgerJournalLineRequest(
    val glAccountId: UUID,
    val side: String,
    val amount: BigDecimal,
    val currencyCode: String,
    val baseAmount: BigDecimal,
    val baseCurrencyCode: String,
    val subAccountId: UUID? = null,
)

/** Mirrors `openbank-ledger-service`'s `PostJournalRequest` (`POST /api/v1/journals`). */
data class LedgerPostJournalRequest(
    val idempotencyKey: String,
    val transactionId: UUID,
    val entryDate: String,
    val valueDate: String,
    val description: String? = null,
    val createdBy: UUID,
    val lines: List<LedgerJournalLineRequest>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LedgerJournalEntryResponse(val id: UUID, val transactionId: UUID, val status: String)

/**
 * Ledger journal posting — a money-path call, so it propagates an OIDC client-credentials
 * token (ADR-0143 step 4: `postedBy` is the billing service's authenticated caller, forwarded
 * as [LedgerPostJournalRequest.createdBy]; the ledger records who posted, billing does not
 * re-derive it).
 */
@RegisterRestClient(configKey = "ledger-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/journals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LedgerRestClient {
    @POST
    fun postJournal(body: LedgerPostJournalRequest): Uni<LedgerJournalEntryResponse>
}
