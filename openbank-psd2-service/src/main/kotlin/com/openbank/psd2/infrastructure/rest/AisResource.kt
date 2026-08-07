// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.psd2.application.port.`in`.AccountInformationUseCase
import com.openbank.psd2.application.port.`in`.GetAccountsQuery
import com.openbank.psd2.application.port.`in`.GetBalancesQuery
import com.openbank.psd2.application.port.`in`.GetTransactionsQuery
import com.openbank.psd2.application.usecase.Psd2RequestFormatException
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

@Path("/open-banking/v2")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AisResource(private val ais: AccountInformationUseCase) {
    @GET
    @Path("/accounts")
    @Authorize(action = "psd2.list", resource = "")
    suspend fun getAccounts(
        @HeaderParam("Consent-ID") consentId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        if (consentId.isNullOrBlank()) throw Psd2RequestFormatException(CONSENT_ID_REQUIRED)
        val tppId = ctx.getProperty("tppId") as? String ?: return missingTpp()
        val accounts = ais.getAccounts(GetAccountsQuery(consentId, tppId))
        return Response.ok(mapOf("accounts" to accounts)).build()
    }

    @GET
    @Path("/accounts/{accountId}/balances")
    @Authorize(action = "psd2.read", resource = "#accountId")
    suspend fun getBalances(
        @PathParam("accountId") accountId: String,
        @HeaderParam("Consent-ID") consentId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        if (consentId.isNullOrBlank()) throw Psd2RequestFormatException(CONSENT_ID_REQUIRED)
        val tppId = ctx.getProperty("tppId") as? String ?: return missingTpp()
        val balances = ais.getBalances(GetBalancesQuery(consentId, tppId, accountId))
        return Response.ok(mapOf("account" to mapOf("iban" to accountId), "balances" to balances)).build()
    }

    @GET
    @Path("/accounts/{accountId}/transactions")
    @Authorize(action = "psd2.read", resource = "#accountId")
    @Suppress("LongParameterList")
    suspend fun getTransactions(
        @PathParam("accountId") accountId: String,
        @HeaderParam("Consent-ID") consentId: String?,
        @QueryParam("dateFrom") dateFrom: String?,
        @QueryParam("dateTo") dateTo: String?,
        @QueryParam("bookingStatus") bookingStatus: String?,
        @QueryParam("limit") limit: Int?,
        @QueryParam("afterCursor") afterCursor: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        if (consentId.isNullOrBlank()) throw Psd2RequestFormatException(CONSENT_ID_REQUIRED)
        val tppId = ctx.getProperty("tppId") as? String ?: return missingTpp()
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
        val body = mutableMapOf<String, Any>(
            "account" to mapOf("iban" to accountId),
            "transactions" to mapOf(
                "booked" to page.booked,
                "pending" to page.pending,
            ),
        )
        page.nextCursor?.let { body["_links"] = mapOf("next" to mapOf("href" to "?afterCursor=$it")) }
        return Response.ok(body).build()
    }

    private fun missingTpp() = Response.status(401)
        .entity(mapOf("tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "CERTIFICATE_MISSING")))).build()

    private companion object {
        // #3624 — Consent-ID is mandated on every AIS call by the Berlin Group NextGenPSD2 spec.
        // Declared non-nullable it could only ever be a 500: these handlers are `suspend`, so no
        // Intrinsics.checkNotNullParameter is emitted and JAX-RS's null for an ABSENT header
        // reached GetAccountsQuery / GetBalancesQuery / GetTransactionsQuery — telling the TPP the
        // ASPSP had broken. Thrown as Psd2RequestFormatException, not require(), so the 400 carries
        // this service's Berlin Group tppMessages envelope rather than libs-runtime's generic
        // ApiError (the #526 shape lottery ExceptionMappers.kt documents); same shape as
        // ObConsentResource's X-Request-ID guard.
        const val CONSENT_ID_REQUIRED = "Consent-ID header is required"
    }
}
