// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * Anonymous recipient boundary for a short-lived disclosure magic link. It is deliberately a
 * separate unannotated resource: class-level customer roles pre-empt method-level PermitAll.
 * Invalid, expired, revoked, locked and unknown links are indistinguishable; the edge forwards no
 * disclosure metadata and only proxies a sealed PDF derivative after successful OTP verification.
 */
@Path("/customer/v1/external-disclosures")
@Consumes(MediaType.APPLICATION_JSON)
class ExternalDisclosureResource(private val upstream: UpstreamClient) {
    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    @POST
    @Path("/{id}/verify-otp")
    @PermitAll
    @Blocking
    fun verifyOtp(@PathParam("id") id: UUID, body: String?): Response = unavailableUnlessSuccess(
        upstream.postAnonymous("$delegationServiceUrl/api/v1/external-disclosures/$id/verify-otp", body ?: "{}"),
        Response.Status.NO_CONTENT.statusCode,
    )

    @POST
    @Path("/{id}/content")
    @PermitAll
    @Blocking
    @Produces(PDF_MEDIA_TYPE)
    fun content(@PathParam("id") id: UUID, body: String?): Response {
        val response = upstream.postRaw(
            "$delegationServiceUrl/api/v1/external-disclosures/$id/content",
            body ?: "{}",
            PDF_MEDIA_TYPE,
        )
        return when {
            response.status == Response.Status.OK.statusCode -> response
            isClientError(response.status) -> unavailable()
            else -> upstreamUnavailable()
        }
    }

    private fun unavailableUnlessSuccess(response: Response, expectedStatus: Int): Response = when {
        response.status == expectedStatus -> Response.status(expectedStatus).build()
        isClientError(response.status) -> unavailable()
        else -> upstreamUnavailable()
    }

    private fun unavailable(): Response = Response.status(Response.Status.NOT_FOUND)
        .entity(mapOf("error" to "external disclosure unavailable")).type(MediaType.APPLICATION_JSON).build()

    private fun upstreamUnavailable(): Response = Response.status(Response.Status.BAD_GATEWAY)
        .entity(mapOf("error" to "upstream unavailable")).type(MediaType.APPLICATION_JSON).build()

    private fun isClientError(status: Int): Boolean =
        Response.Status.Family.familyOf(status) == Response.Status.Family.CLIENT_ERROR

    private companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
    }
}
