// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.rest

import com.openbank.lending.application.port.`in`.CreditRiskInsightUseCase
import com.openbank.lending.domain.model.CreditDecisionView
import com.openbank.lending.domain.model.CreditPolicyView
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.LoanRiskView
import com.openbank.libs.authz.Authorize
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DefaultValue
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
 * Credit-risk read surface (ADR-0230 D1, ADR-0213 D4 evidence, ADR-0028 Phase 3).
 *
 * READ-ONLY BY CONSTRUCTION. Four `GET`s, no mutation: the console this serves renders decisions
 * and never makes them (ADR-0227 D4 keeps disposal in the approval inbox). Role-gated to the
 * credit desk and compliance — the same set that may read the evidence bundle — never
 * `@PermitAll` (`LendingSecurityTest`), and OPA-annotated with the existing `lending.list` /
 * `lending.read` actions so no new matrix grant is needed (rules.yaml: authz).
 *
 * Every list is capped server-side (`CreditRiskInsightService.MAX_LIMIT`) and the summary is
 * grouped in the database, so a figure the console labels as a total IS a total (#3294).
 */
@Path("/api/v1/lending/risk")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Lending", description = "Loan origination, servicing, collateral and IFRS 9 provisioning")
@RolesAllowed("ROLE_CREDIT_RISK", "ROLE_COMPLIANCE", "ROLE_LENDING_OFFICER", "ROLE_ADMIN")
class CreditRiskResource(private val insight: CreditRiskInsightUseCase, private val clock: Clock) {
    @GET
    @Path("/decisions")
    @Operation(
        summary = "Engine-evaluated applications with decoded ADR-0213 evidence (newest first)",
        description = "Outcome, price band, reason codes with rule ids, matched rules, pinned policy " +
            "versions, input hash and the affordability ratios the engine read. Capped at 1000.",
    )
    @Authorize(action = "lending.list", resource = "")
    fun decisions(@QueryParam("limit") @DefaultValue("200") limit: Int): Uni<List<CreditDecisionView>> =
        insight.decisions(limit)

    @GET
    @Path("/decisions/summary")
    @Operation(summary = "Book-wide engine outcome × price-band totals, grouped in the database")
    @Authorize(action = "lending.list", resource = "")
    fun decisionSummary(): Uni<List<DecisionOutcomeSummary>> = insight.summariseDecisions()

    @GET
    @Path("/portfolio")
    @Operation(
        summary = "Loans with their latest IFRS 9 provisioning record (null where never assessed)",
        description = "Stage, DPD bucket, ECL, outstanding and the PD/LGD model version that produced " +
            "them, read back from loan_provisioning — never recomputed here. Capped at 1000.",
    )
    @Authorize(action = "lending.list", resource = "")
    fun portfolio(@QueryParam("limit") @DefaultValue("500") limit: Int): Uni<List<LoanRiskView>> =
        insight.portfolio(limit)

    @GET
    @Path("/policy")
    @Operation(
        summary = "The credit policy bundle the engine evaluates as of a date",
        description = "Every decision table (exclusion, eligibility, affordability, pricing band) with " +
            "its rules, version and effective window. codeSeeded=true while the ADR-0213 D3 starter " +
            "policy is the binding, i.e. before the four-eyes table store (D4) exists.",
    )
    @Authorize(action = "lending.read", resource = "")
    fun policy(@QueryParam("asOf") asOf: String?): Uni<CreditPolicyView> = insight.activePolicy(parseAsOf(asOf))

    /** ISO date or today; a malformed value is the caller's error (400 via libs-runtime), not a 500. */
    private fun parseAsOf(raw: String?): LocalDate {
        if (raw.isNullOrBlank()) return LocalDate.now(clock)
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("query parameter 'asOf' must be an ISO-8601 date", e)
        }
    }
}
