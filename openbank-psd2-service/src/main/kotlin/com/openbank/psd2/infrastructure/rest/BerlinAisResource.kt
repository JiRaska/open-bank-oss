// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.psd2.application.port.`in`.AccountInformationUseCase
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.domain.model.BookingStatus
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.LocalDate

/**
 * Berlin Group NextGenPSD2 XS2A 1.3.12 — Account Information (AIS) endpoints (ADR-0090 P1).
 *
 * Reuses [AccountInformationUseCase]; only path/headers/wire shape are Berlin-conformant
 * ([BerlinXs2aMappers]). TPP auth + AISP role + consent fail-closed are enforced upstream
 * (EidasMtlsFilter, now covering `v1/`). `Consent-ID` and `X-Request-ID` are mandatory per spec.
 */
@Path("/v1/accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BerlinAisResource(private val ais: AccountInformationUseCase) {
    @GET
    @Authorize(action = "psd2.list", resource = "")
    suspend fun getAccounts(
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        if (consentId.isNullOrBlank()) return missingConsentId()
        val accounts = ais.getAccounts(GetAccountsQuery(consentId, tppId))
        return echo(xRequestId).entity(BerlinXs2aMappers.accountList(accounts)).build()
    }

    @GET
    @Path("/{accountId}/balances")
    @Authorize(action = "psd2.read", resource = "#accountId")
    suspend fun getBalances(
        @PathParam("accountId") accountId: String,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        if (consentId.isNullOrBlank()) return missingConsentId()
        val balances = ais.getBalances(GetBalancesQuery(consentId, tppId, accountId))
        return echo(xRequestId).entity(BerlinXs2aMappers.balances(accountId, balances)).build()
    }

    @GET
    @Path("/{accountId}/transactions")
    @Authorize(action = "psd2.read", resource = "#accountId")
    @Suppress("LongParameterList") // Berlin XS2A transaction filters are spec-mandated query params
    suspend fun getTransactions(
        @PathParam("accountId") accountId: String,
        @HeaderParam("Consent-ID") consentId: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @QueryParam("dateFrom") dateFrom: String?,
        @QueryParam("dateTo") dateTo: String?,
        @QueryParam("bookingStatus") bookingStatus: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("afterCursor") afterCursor: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        if (consentId.isNullOrBlank()) return missingConsentId()
        val page = ais.getTransactions(
            GetTransactionsQuery(
                consentId = consentId,
                tppId = tppId,
                accountId = accountId,
                dateFrom = dateFrom?.let { LocalDate.parse(it) },
                dateTo = dateTo?.let { LocalDate.parse(it) },
                bookingStatus = bookingStatus?.let { BookingStatus.valueOf(it.uppercase()) } ?: BookingStatus.BOOKED,
                limit = limit ?: 50,
                afterCursor = afterCursor,
            ),
        )
        return echo(xRequestId).entity(BerlinXs2aMappers.transactions(accountId, page)).build()
    }

    private fun echo(xRequestId: String?): Response.ResponseBuilder =
        Response.ok().apply { xRequestId?.let { header("X-Request-ID", it) } }

    private fun tppMissing() = Response.status(Response.Status.UNAUTHORIZED)
        .entity(BerlinXs2aMappers.tppError("CERTIFICATE_MISSING")).build()

    private fun missingConsentId() = Response.status(Response.Status.UNAUTHORIZED)
        .entity(BerlinXs2aMappers.tppError("CONSENT_INVALID", "Consent-ID header is mandatory")).build()
}
