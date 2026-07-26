// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.rest

import com.openbank.anacredit.application.port.`in`.BuildAnaCreditReturnUseCase
import com.openbank.anacredit.application.port.`in`.ListExposuresUseCase
import com.openbank.anacredit.application.port.`in`.RegisterExposureUseCase
import com.openbank.anacredit.infrastructure.rest.dto.AnaCreditReturnResponse
import com.openbank.anacredit.infrastructure.rest.dto.ExposureResponse
import com.openbank.anacredit.infrastructure.rest.dto.RegisterExposureRequest
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.time.LocalDate

/**
 * Read/derive AnaCredit feed (ADR-0037). Exposures are fed in (v1), and the credit dataset for a
 * monthly reference date is rendered on demand. No money movement; no events.
 */
@ApplicationScoped
@Path("/api/v1/anacredit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_COMPLIANCE", "ROLE_API")
class AnaCreditResource(
    private val register: RegisterExposureUseCase,
    private val listExposures: ListExposuresUseCase,
    private val buildReturn: BuildAnaCreditReturnUseCase,
) {

    @POST
    @Path("/exposures")
    @Consumes(MediaType.APPLICATION_JSON)
    @Authorize(action = "anacredit.create", resource = "")
    suspend fun registerExposure(request: RegisterExposureRequest): Response {
        val stored = register.register(request.toCommand())
        return Response.status(Response.Status.CREATED).entity(ExposureResponse.of(stored)).build()
    }

    @GET
    @Path("/exposures")
    @Authorize(action = "anacredit.list", resource = "")
    suspend fun listAllExposures(): Response = Response.ok(listExposures.list().map(ExposureResponse::of)).build()

    /** Render the AnaCredit credit dataset as of [referenceDate] (ISO yyyy-MM-dd, the month end). */
    @GET
    @Path("/returns/{referenceDate}")
    @Authorize(action = "anacredit.read", resource = "#referenceDate")
    suspend fun renderReturn(@PathParam("referenceDate") referenceDate: String): Response {
        val date = LocalDate.parse(referenceDate)
        return Response.ok(AnaCreditReturnResponse.of(buildReturn.build(date))).build()
    }
}
