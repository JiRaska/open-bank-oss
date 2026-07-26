// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.rest

import com.openbank.ledger.application.port.`in`.GetControlAccountTieOutQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.SubLedgerBalance
import com.openbank.libs.authz.Authorize
import com.openbank.libs.security.Roles
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Path("/api/v1/control-accounts")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Ledger", description = "General ledger journal entries")
class ControlAccountResource(private val clock: Clock, private val ledgerUseCase: LedgerUseCase) {

    /**
     * Sub-ledger tie-out for a single deposit-control account (ADR-0039 Phase B).
     *
     * Returns the GL aggregate vs the sum of all per-customer sub-ledger lines per currency.
     * A non-zero [ControlAccountTieOutResponse.delta] is an incident. An empty list means the
     * account has no posted activity yet.
     */
    @GET
    @Path("/{controlAccountId}/tie-out")
    @RolesAllowed(Roles.API, Roles.AUDITOR, Roles.OPERATOR, Roles.ADMIN)
    @Authorize(action = "ledger.read", resource = "#controlAccountId")
    @Operation(summary = "Sub-ledger tie-out for a deposit-control GL account (ADR-0039 Phase B)")
    suspend fun controlAccountTieOut(
        @PathParam("controlAccountId") controlAccountId: UUID,
        @QueryParam("asOf") asOf: String?,
    ): Response {
        val date = asOf?.let { LocalDate.parse(it) } ?: LocalDate.now(clock)
        val tieOuts = ledgerUseCase.getControlAccountTieOut(
            GetControlAccountTieOutQuery(controlAccountId = controlAccountId, asOf = date),
        )
        return Response.ok(tieOuts.map { it.toResponse() }).build()
    }
}

data class SubLedgerBalanceLineResponse(
    val subAccountId: UUID,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

data class ControlAccountTieOutResponse(
    val controlAccountId: UUID,
    val currency: String,
    val asOf: String,
    val glNet: BigDecimal,
    val subLedgerNet: BigDecimal,
    val delta: BigDecimal,
    val tiedOut: Boolean,
    val lines: List<SubLedgerBalanceLineResponse>,
)

private fun SubLedgerBalance.toLineResponse() = SubLedgerBalanceLineResponse(
    subAccountId = subAccountId,
    currency = currency,
    totalDebit = totalDebit,
    totalCredit = totalCredit,
    net = net,
)

private fun ControlAccountTieOut.toResponse() = ControlAccountTieOutResponse(
    controlAccountId = controlAccountId,
    currency = currency,
    asOf = asOf.toString(),
    glNet = glNet,
    subLedgerNet = subLedgerNet,
    delta = delta,
    tiedOut = isTiedOut,
    lines = lines.map { it.toLineResponse() },
)
