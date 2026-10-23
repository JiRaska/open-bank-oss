// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import com.openbank.treasury.application.usecase.CashFlowAnalysis
import com.openbank.treasury.application.usecase.CashFlowAnalysisRequest
import com.openbank.treasury.application.usecase.CashFlowAnalysisService
import com.openbank.treasury.application.usecase.FlowExpression
import com.openbank.treasury.application.usecase.FlowScenario
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.time.LocalDate

/** The wire body of contract 8.4; every field but the date and the currency has a default. */
data class CashFlowAnalysisBody(
    val calculationDate: String? = null,
    val referenceCurrency: String? = null,
    val convertToReference: Boolean? = null,
    val expression: String? = null,
    val scenario: String? = null,
    val splitMonthly: Boolean? = null,
    val bookId: String? = null,
    val bankAccountId: String? = null,
    val includePlanned: Boolean? = null,
    val horizonDays: Int? = null,
    val futureBusinessScenarioId: String? = null,
)

/**
 * Contract 8.4 cash-flow analysis over the stored ledger (ADR-0344). Read-only: the same treasury
 * staff and OPA action that read a deal's cash flows. `@Path` sits immediately above `class` (#3371).
 */
@Tag(name = "Treasury", description = "Treasury cash-flow analysis (ADR-0344, contract 8.4)")
@Path("/api/v1/treasury/analysis/cash-flows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN, DEALER, APPROVER, SENIOR_APPROVER, BACK_OFFICE)
class CashFlowAnalysisResource {

    @Inject
    lateinit var analysis: CashFlowAnalysisService

    @POST
    @Operation(
        summary = "Contractual cash flows by currency (gross, net or cumulative), optionally in a reference currency",
    )
    @Authorize(action = "treasury.cashflow.read")
    suspend fun analyse(body: CashFlowAnalysisBody?): CashFlowAnalysis {
        val b = requireNotNull(body) { "a request body is required" }
        // A future-business scenario projects new deals: that is a PLANNED-deal view, not a model.
        require(b.futureBusinessScenarioId == null) { "futureBusinessScenarioId is not supported; use includePlanned" }
        val date = runCatching { LocalDate.parse(requireNotNull(b.calculationDate)) }
            .getOrElse { throw IllegalArgumentException("'calculationDate' is required as an ISO date (yyyy-MM-dd)") }
        return analysis.analyse(
            CashFlowAnalysisRequest(
                calculationDate = date,
                referenceCurrency = requireNotNull(b.referenceCurrency) { "'referenceCurrency' is required" },
                convertToReference = b.convertToReference ?: false,
                expression = enumOf<FlowExpression>(b.expression, "expression") ?: FlowExpression.NET,
                scenario = enumOf<FlowScenario>(b.scenario, "scenario") ?: FlowScenario.CONTRACTUAL,
                splitMonthly = b.splitMonthly ?: false,
                bookId = b.bookId?.takeIf { it.isNotBlank() },
                bankAccountId = b.bankAccountId?.takeIf { it.isNotBlank() },
                includePlanned = b.includePlanned ?: false,
                horizonDays = b.horizonDays ?: CashFlowAnalysisRequest.DEFAULT_HORIZON_DAYS,
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(raw: String?, name: String): E? = raw?.let { v ->
        requireNotNull(
            enumValues<E>().firstOrNull {
                it.name == v
            },
        ) { "'$name' must be one of ${enumValues<E>().toList()}" }
    }
}
