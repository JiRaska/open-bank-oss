// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.out.CurveSetNotFoundException
import com.openbank.risk.application.port.out.SnapshotNotFoundException
import com.openbank.risk.application.port.out.UntiedSnapshotException
import com.openbank.risk.domain.model.InvalidLoanContractException
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Only the ones this service owns. `IllegalArgumentException` → 400 is mapped by libs-runtime
 * fleet-wide, and a service-local mapper for it is forbidden (ADR-0049, #526).
 */
class ExceptionMappers {

    @ServerExceptionMapper
    fun notFound(e: SnapshotNotFoundException): Response =
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
}
