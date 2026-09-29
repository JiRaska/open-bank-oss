// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.client

import com.openbank.libs.web.SyntheticTaintClientFilter
import com.openbank.risk.application.port.out.LendingPort
import com.openbank.risk.domain.model.LoanContract
import com.openbank.risk.domain.model.ScheduledInstallment
import io.quarkus.oidc.client.reactive.filter.OidcClientRequestReactiveFilter
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Outbound client for openbank-lending-service's loan-book read (ADR-0314 D4),
 * `GET /api/v1/lending/loan-book` (lending `openapi.yaml`, operation `loanBook`). Same M2M wiring as
 * [LedgerRestClient]: the shared `openbank-services` client-credentials token, which lending's OPA
 * admits for exactly the `lending.book.read` action (`service-risk-loan-book-read`).
 */
@RegisterRestClient(configKey = "lending-service")
@RegisterProvider(SyntheticTaintClientFilter::class)
@RegisterProvider(OidcClientRequestReactiveFilter::class)
@Path("/api/v1/lending")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface LendingRestClient {

    @GET
    @Path("/loan-book")
    fun loanBook(@QueryParam("asOf") asOf: String): Uni<LoanBookResponse>
}

data class RateTermsResponse(
    val rateType: String,
    val rateIndex: String?,
    val spread: BigDecimal?,
    val resetFrequencyMonths: Int?,
    val nextResetDate: LocalDate?,
)

data class RemainingInstallmentResponse(
    val number: Int,
    val dueDate: LocalDate,
    val principal: BigDecimal,
    val interest: BigDecimal,
)

data class LoanBookEntryResponse(
    val loanId: UUID,
    val counterpartyRef: UUID,
    val status: String,
    val currency: String,
    val glAccountCode: String?,
    val outstandingPrincipal: BigDecimal,
    val nominalAnnualRate: BigDecimal,
    val rateTerms: RateTermsResponse,
    val method: String,
    val periodsPerYear: Int,
    val disbursedOn: LocalDate,
    val maturityDate: LocalDate?,
    val ifrs9Stage: String?,
    val remainingInstallments: List<RemainingInstallmentResponse>,
)

data class LoanBookResponse(val asOf: LocalDate, val loans: List<LoanBookEntryResponse>)

@ApplicationScoped
class LendingAdapter(@RestClient private val client: LendingRestClient) : LendingPort {

    override suspend fun readLoanBook(asOf: LocalDate): List<LoanContract> {
        val book = client.loanBook(asOf.toString()).awaitSuspending()
        // lending answers for the date it was asked about; a book for another day would tie out
        // against the wrong trial balance, so refuse it rather than carry it into the snapshot.
        check(book.asOf == asOf) { "lending answered the loan book as of ${book.asOf}, asked for $asOf" }
        return book.loans.map(::toContract)
    }

    companion object {
        fun toContract(it: LoanBookEntryResponse) = LoanContract(
            loanId = it.loanId,
            counterpartyRef = it.counterpartyRef,
            status = it.status,
            currency = it.currency,
            glAccountCode = it.glAccountCode,
            outstandingPrincipal = it.outstandingPrincipal,
            nominalAnnualRate = it.nominalAnnualRate,
            rateType = it.rateTerms.rateType,
            rateIndex = it.rateTerms.rateIndex,
            spread = it.rateTerms.spread,
            resetFrequencyMonths = it.rateTerms.resetFrequencyMonths,
            nextResetDate = it.rateTerms.nextResetDate,
            method = it.method,
            periodsPerYear = it.periodsPerYear,
            disbursedOn = it.disbursedOn,
            maturityDate = it.maturityDate,
            ifrs9Stage = it.ifrs9Stage,
            remainingInstallments = it.remainingInstallments.map { i ->
                ScheduledInstallment(i.number, i.dueDate, i.principal, i.interest)
            },
        )
    }
}
