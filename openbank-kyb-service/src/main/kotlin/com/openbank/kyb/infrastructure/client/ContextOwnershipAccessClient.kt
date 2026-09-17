// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.openbank.kyb.application.port.out.UboObservationAccess
import com.openbank.kyb.application.port.out.UboObservationAccessDecision
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

private const val ASSIGNMENT_TIMEOUT_MS = 3000L
private const val HTTP_OK = 200
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** Uses the investigator's bearer, never the KYB service identity, for the live assignment check. */
@Path("/api/v1/context/kyb-cases")
@RegisterRestClient(configKey = "context-service")
interface ContextOwnershipAccessRestClient {
    @GET
    @Path("/{caseId}/ownership-observations")
    suspend fun history(
        @PathParam("caseId") caseId: UUID,
        @HeaderParam("Authorization") bearer: String,
        @HeaderParam("X-Investigation-Case-Id") investigationCaseId: String,
        @HeaderParam("X-Investigation-Purpose") purpose: String,
    ): Response
}

@ApplicationScoped
class ContextOwnershipAccessAdapter : UboObservationAccess {
    @Inject
    @RestClient
    lateinit var client: ContextOwnershipAccessRestClient

    @Timeout(ASSIGNMENT_TIMEOUT_MS)
    override suspend fun check(caseId: UUID, bearer: String): UboObservationAccessDecision = try {
        client.history(caseId, bearer, caseId.toString(), "KYB_OWNERSHIP_REVIEW").use { response ->
            when (response.status) {
                HTTP_OK -> UboObservationAccessDecision.ALLOWED
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND -> UboObservationAccessDecision.DENIED
                else -> UboObservationAccessDecision.UNAVAILABLE
            }
        }
    } catch (exception: WebApplicationException) {
        when (exception.response?.status) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND -> UboObservationAccessDecision.DENIED
            else -> UboObservationAccessDecision.UNAVAILABLE
        }
    } catch (_: Exception) {
        UboObservationAccessDecision.UNAVAILABLE
    }
}
