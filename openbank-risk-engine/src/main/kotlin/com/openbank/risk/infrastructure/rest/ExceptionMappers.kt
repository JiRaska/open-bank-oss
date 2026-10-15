// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.MaintenancePeriodNotFoundException
import com.openbank.risk.application.port.`in`.MinReservesNotEvaluableException
import com.openbank.risk.application.port.out.CurveSetNotFoundException
import com.openbank.risk.application.port.out.SnapshotNotFoundException
import com.openbank.risk.application.port.out.UntiedSnapshotException
import com.openbank.risk.domain.model.InvalidLoanContractException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

private const val FAILED_DEPENDENCY = 424

/**
 * Only the ones this service owns. `IllegalArgumentException` → 400 is mapped by libs-runtime
 * fleet-wide, and a service-local mapper for it is forbidden (ADR-0049, #526).
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: SnapshotNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun periodNotFound(e: MaintenancePeriodNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    @ServerExceptionMapper
    fun curveSetNotFound(e: CurveSetNotFoundException): Response =
        Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to e.message)).build()

    /**
     * Lending answered a loan book the engine cannot interpret (ADR-0314 D4). The upstream broke its
     * contract, so this is a 502 and no run is stored — never a guess at what the loan meant.
     */
    @ServerExceptionMapper
    fun invalidLoan(e: InvalidLoanContractException): Response = Response.status(Response.Status.BAD_GATEWAY)
        .entity(mapOf("error" to e.message))
        .build()

    /** ADR-0314 D3: an UNTIED run is never rendered — the caller gets the breaks instead. */
    @ServerExceptionMapper
    fun untied(e: UntiedSnapshotException): Response = Response.status(Response.Status.CONFLICT)
        .entity(UntiedResponse(error = "UNTIED", runId = e.runId, mismatches = e.mismatches.map { it.toDto() }))
        .build()

    /**
     * No ČNB reserve ratio / remuneration fact is in effect on the run's as-of date. The requirement
     * is NOT_EVALUABLE — 424 identifies the missing upstream fact without changing the established
     * 409 UNTIED response shape, and never returns a number computed at a default rate.
     */
    @ServerExceptionMapper
    fun minReservesNotEvaluable(e: MinReservesNotEvaluableException): Response = Response.status(FAILED_DEPENDENCY)
        .entity(
            mapOf(
                "error" to "NOT_EVALUABLE",
                "runId" to e.runId.toString(),
                "asOf" to e.asOf.toString(),
                "reason" to e.reason,
            ),
        )
        .build()
}
