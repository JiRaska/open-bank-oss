// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.LoanBookUseCase
import com.openbank.lending.domain.model.LoanBook
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The loan book for the risk engine's instrument model (ADR-0314 D4).
 *
 * READ-ONLY BY CONSTRUCTION: one `GET`, no mutation. It exists because a loan's remaining
 * schedule is carried by no event, so the risk engine PULLS it at snapshot time — a documented
 * deviation from ADR-0314 D1 (positions from events) for this slice.
 *
 * `ROLE_API` is admitted because the caller is a machine: the risk engine's client-credentials
 * token carries only `ROLE_API` in the deployed realm. OPA's `lending.book.read` then narrows it to
 * the shared `openbank-services` service account (`lending_rest_ext.rego`: service-risk-loan-book-read),
 * so another `ROLE_API` holder is still denied. The credit desk and admins may read it too.
 */
@Path("/api/v1/lending/loan-book")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Loan origination, servicing, collateral and IFRS 9 provisioning")
@RolesAllowed("ROLE_API", "ROLE_CREDIT_RISK", "ROLE_ADMIN")
class LoanBookResource(private val book: LoanBookUseCase, private val clock: Clock) {

    @GET
    @Operation(
        summary = "Loans on the book as of a date, with rate terms and remaining installments (ADR-0314 D4)",
        description = "Every loan not closed, written off, unwound, settled or withdrawn and disbursed on " +
            "or before asOf, with its Loans Receivable GL code, outstanding principal (the sum of its " +
            "remaining installments' principal), rate terms, latest IFRS 9 stage and the installments " +
            "unpaid at asOf. The whole book or an error, never a prefix. asOf defaults to today (UTC).",
    )
    @Authorize(action = "lending.book.read", resource = "")
    fun loanBook(@QueryParam("asOf") asOf: String?): Uni<LoanBook> {
        val date = try {
            asOf?.let(LocalDate::parse) ?: LocalDate.now(clock)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter 'asOf' must be an ISO date (yyyy-MM-dd)", e)
        }
        return book.loanBook(date)
    }
}
