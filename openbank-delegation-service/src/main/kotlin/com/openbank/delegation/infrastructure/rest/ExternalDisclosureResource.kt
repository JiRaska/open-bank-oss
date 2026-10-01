// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.rest

import com.openbank.delegation.application.port.`in`.ExternalDisclosureUseCase
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureLinkRequest
import com.openbank.delegation.infrastructure.rest.dto.ExternalDisclosureOtpRequest
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.Base64
import java.util.UUID

/**
 * Internal half of the recipient disclosure journey. The only permitted caller is customer-edge,
 * which owns the anonymous Internet boundary and rate limit. This service never accepts an
 * unauthenticated request directly and never exposes disclosure metadata or an original document.
 */
@Path("/api/v1/external-disclosures")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_API")
class ExternalDisclosureResource(
    private val disclosures: ExternalDisclosureUseCase,
    private val idempotencyStore: IdempotencyStore,
) {
    @POST
    @Path("/{id}/verify-otp")
    @Authorize(action = "delegation.disclosure.verify", resource = "#id")
    suspend fun verifyOtp(@PathParam("id") id: UUID, request: ExternalDisclosureOtpRequest?): Response {
        val body = request ?: return unavailableResponse()
        val key = key(id, "verify", body.idempotencyKey)
        idempotencyStore.get(key)?.let {
            return Response.status(it.statusCode).header("X-Idempotency-Replayed", "true").build()
        }
        conceal { disclosures.verifyOtp(id, body.linkSecret, body.otp) }
        idempotencyStore.save(key, Response.Status.NO_CONTENT.statusCode, "")
        return Response.noContent().build()
    }

    @POST
    @Path("/{id}/content")
    @Produces(PDF_MEDIA_TYPE)
    @Authorize(action = "delegation.disclosure.release", resource = "#id")
    suspend fun content(@PathParam("id") id: UUID, request: ExternalDisclosureLinkRequest?): Response {
        val body = request ?: return unavailableResponse()
        val key = key(id, "content", body.idempotencyKey)
        idempotencyStore.get(key)?.let { cached ->
            return Response.ok(Base64.getDecoder().decode(cached.responseBody), PDF_MEDIA_TYPE)
                .header("X-Idempotency-Replayed", "true").build()
        }
        val artifact = conceal { disclosures.release(id, body.linkSecret) }
        idempotencyStore.save(key, Response.Status.OK.statusCode, Base64.getEncoder().encodeToString(artifact.bytes))
        return Response.ok(artifact.bytes, artifact.contentType).build()
    }

    /** Keep the external surface non-enumerable while retaining the root cause for server diagnostics. */
    private suspend fun <T> conceal(action: suspend () -> T): T = try {
        action()
    } catch (exception: IllegalArgumentException) {
        throw NotFoundException("external disclosure unavailable", exception)
    } catch (exception: NotFoundException) {
        throw NotFoundException("external disclosure unavailable", exception)
    }

    private fun unavailableResponse(): Response = Response.status(Response.Status.NOT_FOUND).build()

    private fun key(id: UUID, operation: String, supplied: String): String {
        require(supplied.isNotBlank()) { "idempotencyKey is required" }
        return "external-disclosure:$id:$operation:$supplied"
    }

    private companion object {
        const val PDF_MEDIA_TYPE = "application/pdf"
    }
}
