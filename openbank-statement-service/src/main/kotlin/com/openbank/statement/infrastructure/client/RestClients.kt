// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.util.UUID

// Booked entries read from transaction-service via its search endpoint (the by-account/date booked
// source, ADR-0035). Shape mirrors transaction-service's TransactionResponse; direction is derived
// per-account from source/target (no `direction` field), and the currency field is `currencyCode`.
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionDto(
    val referenceNumber: String? = null,
    val amount: BigDecimal? = null,
    val currencyCode: String? = null,
    val type: String? = null,
    val sourceAccountId: String? = null,
    val targetAccountId: String? = null,
    val bookingDate: String? = null,
    val valueDate: String? = null,
    val description: String? = null,
    val status: String? = null,
)

/** transaction-service search envelope: {"data":[...],"count","limit","offset"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TransactionSearchResponse(val data: List<TransactionDto> = emptyList())

@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "transaction-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface TransactionRestClient {
    // No Kotlin default-valued parameter here: a default on an interface method compiles to a
    // synthetic JVM default method, which the Quarkus REST Client rejects.
    // limit/offset MUST always be passed explicitly: transaction-service defaults limit to 50 and
    // silently truncates, which capped statements at 50 entries per month (fix: paginate in the
    // adapter). The upstream coerces limit into 1..200.
    @GET
    @Path("/api/v1/transactions/search")
    fun searchByAccount(
        @QueryParam("accountId") accountId: UUID,
        @QueryParam("dateFrom") dateFrom: String,
        @QueryParam("dateTo") dateTo: String,
        @QueryParam("status") status: String,
        @QueryParam("limit") limit: Int,
        @QueryParam("offset") offset: Int,
    ): Uni<TransactionSearchResponse>
}

// Per-pocket balance read from balance-service for fail-closed reconciliation (ADR-0035 §E). The
// per-currency endpoint now accepts an `asOf` query param (balance-service >= 1.3.0): with it the
// service rewinds the booked balance to the end-of-day balance as of that date, so statement close
// can anchor opening (period start - 1) and closing (period end) at the true period boundaries. When
// the upstream ledger-projection audit is empty (projection disabled) the rewind degrades to the
// current balance, exactly the prior behavior — so wiring asOf is safe ahead of projection rollout.
@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceDto(
    val accountId: UUID? = null,
    val currency: String? = null,
    val bookedAmount: BigDecimal? = null,
    val availableAmount: BigDecimal? = null,
)

@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "balance-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface BalanceRestClient {
    // No Kotlin default-valued parameter (synthetic JVM default method → Quarkus REST client rejects);
    // the adapter always passes an explicit asOf date string.
    @GET
    @Path("/api/v1/balances/{accountId}/{currency}")
    fun pocketBalance(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @QueryParam("asOf") asOf: String,
    ): Uni<BalanceDto>
}

// Pocket account identity read from account-service (ADR-0024). The account-service AccountResponse
// exposes the IBAN as `accountNumber` (NOT `iban`) and carries no holder name — only the owning
// `partyId`. The holder name is resolved separately from party-service. Reading the wrong field names
// is what left the rendered camt.053/MT940/PDF with a blank IBAN and empty holder.
@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountDto(
    val id: UUID? = null,
    val accountNumber: String? = null,
    val partyId: UUID? = null,
    val currencies: List<String>? = null,
)

/** Party identity read from party-service — the source of the statement holder name. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyDto(val id: UUID? = null, val legalName: String? = null)

@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "party-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface PartyRestClient {
    @GET
    @Path("/api/v1/parties/{partyId}")
    fun party(@PathParam("partyId") partyId: UUID): Uni<PartyDto>
}

/** Currency pockets of an account — the real per-currency enumeration for period close (ADR-0024). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PocketsResponse(val pockets: List<PocketDto> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class PocketDto(val currencyCode: String? = null, val status: String? = null)

@RegisterProvider(OidcClientRequestReactiveFilter::class)
@RegisterRestClient(configKey = "account-api")
@RegisterProvider(SyntheticTaintClientFilter::class)
@Produces(MediaType.APPLICATION_JSON)
interface AccountRestClient {
    @GET
    @Path("/api/v1/accounts/{accountId}")
    fun account(@PathParam("accountId") accountId: UUID): Uni<AccountDto>

    @GET
    @Path("/api/v1/accounts/{accountId}/pockets")
    fun pockets(@PathParam("accountId") accountId: UUID): Uni<PocketsResponse>
}
