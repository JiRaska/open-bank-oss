// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
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

/**
 * Mirror of balance-service's `Balance` as `GET /api/v1/balances/{accountId}/{currency}` serialises
 * it: the booked balance is `bookedAmount`. This DTO used to demand a `currentBalance` field that
 * balance-service has never sent; Jackson failed to construct it on every call, the adapter's
 * `runCatching` turned that into `null`, and every balance-conditioned fee waiver evaluated against
 * no balance at all (#11650, found by the consumer pact for #8345).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceDto(val accountId: String, val currency: String, val bookedAmount: BigDecimal)

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
 * Product-catalog revisions (ADR-0341): the catalog is the source of truth for fee amounts and the
 * accounting class, read by PINNED REVISION through the v2 API. product-catalog authenticates its
 * callers (issue #401), so propagate an openbank-services bearer like the other clients below -- a
 * fee assessment cannot silently fall back to "no fees" on a 401.
 */
@RegisterRestClient(configKey = "product-catalog")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterProvider(ProductCatalogHostHeaderFilter::class)
@Path("/api/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ProductCatalogRestClient {
    /** The revision effective now for a v1 product id; 404 when none is published. */
    @GET
    @Path("/legacy-products/{productId}/published-revision")
    fun getPublishedRevisionForLegacyProduct(@PathParam("productId") productId: String): Uni<RevisionDto>

    /** One immutable revision by its durable id. */
    @GET
    @Path("/revisions/{id}")
    fun getRevision(@PathParam("id") revisionId: String): Uni<RevisionDto>
}

/** The subset of a catalog `ProductRevision` billing reads (`GET /api/v2/revisions/{id}`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RevisionDto(
    val id: String,
    val state: String,
    val content: RevisionContentDto = RevisionContentDto(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RevisionContentDto(
    val attributes: Map<String, Any?> = emptyMap(),
    val prices: List<PriceComponentDto> = emptyList(),
)

/** One price component; [value] is an exact decimal STRING in v2, never a binary float. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PriceComponentDto(
    val code: String,
    val kind: String,
    val value: BigDecimal,
    val currency: String? = null,
    val unit: String? = null,
    val cadence: String? = null,
)

/** Account read — carries account PII, so the call propagates an OIDC service token. */
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
// #10486 batch 5: account reads (account.list, account.read) are minted by the NAMED oidc-client
// `m2m` - Keycloak client `openbank-billing` (ROLE_API only) - never the shared `openbank-services` one.
@OidcClientFilter("m2m")
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
    val accountingClass: String,
    val accountingRole: String,
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
