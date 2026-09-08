// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ExternalDisclosureUseCase
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureLinkRequest
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureOtpRequest
import com.openbank.libs.authz.Authorize
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

/**
 * Internal half of the recipient disclosure journey. The only permitted caller is customer-edge,
 * which owns the anonymous Internet boundary and rate limit. This service never accepts an
 * unauthenticated request directly and never exposes disclosure metadata or an original document.
 */
@Path("/api/v1/external-disclosures")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class ExternalDisclosureResource(private val disclosures: ExternalDisclosureUseCase) {
    @POST
    @Path("/{id}/verify-otp")
    @Authorize(action = "delegation.disclosure.verify", resource = "#id")
    suspend fun verifyOtp(@PathParam("id") id: UUID, request: ExternalDisclosureOtpRequest?): Response {
        val body = request ?: throw unavailable()
        try {
            disclosures.verifyOtp(id, body.linkSecret, body.otp)
        } catch (exception: IllegalArgumentException) {
            throw unavailable()
        } catch (exception: NotFoundException) {
            throw unavailable()
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/{id}/content")
    @Produces(PDF_MEDIA_TYPE)
    @Authorize(action = "delegation.disclosure.release", resource = "#id")
    suspend fun content(@PathParam("id") id: UUID, request: ExternalDisclosureLinkRequest?): Response {
        val body = request ?: throw unavailable()
        val artifact = try {
            disclosures.release(id, body.linkSecret)
        } catch (exception: IllegalArgumentException) {
            throw unavailable()
        } catch (exception: NotFoundException) {
            throw unavailable()
        }
        return Response.ok(artifact.bytes, artifact.contentType).build()
    }

    private fun unavailable(): NotFoundException = NotFoundException("external disclosure unavailable")

    private companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
    }
}
