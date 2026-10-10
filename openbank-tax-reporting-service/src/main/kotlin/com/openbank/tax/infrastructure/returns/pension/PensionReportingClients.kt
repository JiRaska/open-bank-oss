// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.pension

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import java.time.LocalDate
import java.util.UUID

/*
 * The two reporting read models the ČNB pension returns are assembled from (#12425, ADR-0336 D4).
 * Both are called with tax-reporting-service's OWN client-credentials token (Keycloak client
 * `openbank-tax-reporting`, ROLE_API only): the providers admit their aggregate routes for
 * service-account-openbank-tax-reporting and staff, never the shared openbank-services account.
 */

/** pension-fund-service's `GET /api/v1/reporting/funds/{fundId}/period-figures` (its openapi.yaml). */
@Path("/api/v1/reporting/funds")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "pension-fund-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PensionFundReportingClient {
    @GET
    @Path("/{fundId}/period-figures")
    suspend fun periodFigures(
        @PathParam("fundId") fundId: UUID,
        @QueryParam("periodStart") periodStart: LocalDate,
        @QueryParam("periodEnd") periodEnd: LocalDate,
    ): FundPeriodFiguresDto
}

/** pension-service's `GET /api/v1/pension/reporting/participant-aggregates` (its openapi.yaml). */
@Path("/api/v1/pension/reporting")
@Produces(MediaType.APPLICATION_JSON)
@OidcClientFilter
@RegisterRestClient(configKey = "pension-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface PensionReportingClient {
    @GET
    @Path("/participant-aggregates")
    suspend fun participantAggregates(
        @QueryParam("periodStart") periodStart: LocalDate,
        @QueryParam("periodEnd") periodEnd: LocalDate,
    ): ParticipantAggregatesDto
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BalanceSheetDto(val totalAssets: BigDecimal, val totalLiabilities: BigDecimal, val totalEquity: BigDecimal)

/** Separately accumulated YTD lines; the investment lines are null, with a reason, when unknown. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfitAndLossDto(
    val revaluationGains: BigDecimal? = null,
    val revaluationLosses: BigDecimal? = null,
    val otherInvestmentResult: BigDecimal? = null,
    val managementFees: BigDecimal,
    val profitLoss: BigDecimal,
    val linesUnavailableReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class UnitsDto(
    val opening: BigDecimal,
    val issued: BigDecimal,
    val cancelled: BigDecimal,
    val closing: BigDecimal,
    val unitValue: BigDecimal,
    val unitValuePeriodMax: BigDecimal,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PortfolioDto(
    val carryingValue: BigDecimal? = null,
    val holdingsCount: Int? = null,
    val cash: BigDecimal? = null,
    /** Null while any closing position is UNCLASSIFIED ([unclassifiedCount] > 0) or positions are unknown. */
    val loansOutstanding: BigDecimal? = null,
    val unclassifiedCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EntitlementsDto(
    val opening: BigDecimal,
    val increase: BigDecimal,
    val decrease: BigDecimal,
    val closing: BigDecimal,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FundParticipantsDto(val holders: Int, val subscribing: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FundPeriodFiguresDto(
    val fundId: UUID,
    val currency: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val closingValuationDate: LocalDate,
    val balanceSheet: BalanceSheetDto,
    val profitAndLossYtd: ProfitAndLossDto,
    val units: UnitsDto,
    val portfolio: PortfolioDto,
    val entitlements: EntitlementsDto,
    val participants: FundParticipantsDto,
    val fingerprint: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ContributionsDto(
    val participant: BigDecimal,
    val employer: BigDecimal,
    val state: BigDecimal,
    val transferIn: BigDecimal,
    val total: BigDecimal,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PayoutsDto(val total: BigDecimal, val cases: Long, val taxWithheld: BigDecimal)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PensionParticipantsDto(val inForce: Long, val contributing: Long, val pensioners: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ParticipantAggregatesDto(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currency: String,
    val participants: PensionParticipantsDto,
    val contributionsYtd: ContributionsDto,
    val payoutsYtd: PayoutsDto,
)
