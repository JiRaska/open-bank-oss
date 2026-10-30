// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.crsfatca

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.web.SyntheticTaintClientFilter
import io.quarkus.oidc.client.filter.OidcClientFilter
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * The three reporting read models the CRS/FATCA assembly is built from (ADR-0340 B).
 *
 * All three are called with tax-reporting-service's OWN client-credentials token (Keycloak client
 * `openbank-tax-reporting`, ROLE_API only), the same machine identity as the pension read models:
 *  - party-service admits `GET /reporting/parties/{id}/tax-facts` for service-account-openbank-tax-reporting
 *    ALONE (in-code guard + rego + audit) — it is the TIN boundary;
 *  - account-service admits `GET /accounts/active` (its billing-discovery route) for the reporting
 *    service account in rego;
 *  - balance-service admits `GET /balances/{accountId}/{currency}?asOf=` (its statement route) for
 *    the same caller in rego.
 */

/** party-service's `GET /api/v1/reporting/parties/{id}/tax-facts` (PartyTaxReportingResource). */
@Path("/api/v1/reporting/parties")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "party-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PartyTaxReportingClient {
    @GET
    @Path("/{id}/tax-facts")
    suspend fun taxFacts(@PathParam("id") id: UUID): PartyTaxFactsDto
}

/** account-service's `GET /api/v1/accounts/active` (its fleet-wide billing-discovery list). */
@Path("/api/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "account-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface AccountReportingClient {
    @GET
    @Path("/active")
    suspend fun active(
        @QueryParam("limit") limit: Int,
        @QueryParam("cursor") cursor: String?,
    ): CursorPage<AccountSummaryDto>
}

/** balance-service's `GET /api/v1/balances/{accountId}/{currency}` (statement point-in-time read). */
@Path("/api/v1/balances")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "balance-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface BalanceReportingClient {
    @GET
    @Path("/{accountId}/{currency}")
    suspend fun bookedAsOf(
        @PathParam("accountId") accountId: UUID,
        @PathParam("currency") currency: String,
        @QueryParam("asOf") asOf: LocalDate,
    ): AccountBalanceDto
}

/** The TIN boundary's body — statuses and residencies WITH TINs. Readable by tax-reporting only. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PartyTaxFactsDto(
    val partyId: UUID,
    val holderName: String,
    val profileVersion: Int,
    val declaredAt: Instant,
    val crsStatus: String,
    val fatcaStatus: String,
    val taxResidencies: List<TaxResidencyDto>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TaxResidencyDto(val country: String, val tin: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountSummaryDto(
    val id: UUID,
    val accountNumber: String,
    val partyId: UUID,
    val productId: UUID,
    val currencyCode: String,
    val status: String,
    val openedAt: Instant,
    val closedAt: Instant?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountBalanceDto(val bookedAmount: BigDecimal)
