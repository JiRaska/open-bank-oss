// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.libs.web.SyntheticTaintClientFilter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient

data class TppAuthorizationResponse(
    val tppId: String,
    val authorized: Boolean,
    val roles: Set<String>,
    val reason: String?,
)

@Path("/api/v1/tpp-registry")
@RegisterRestClient(configKey = "tpp-registry")
@RegisterProvider(SyntheticTaintClientFilter::class)
interface TppRegistryRestClient {
    @GET
    @Path("/check")
    fun checkAuthorization(
        @QueryParam("tppId") tppId: String,
        @QueryParam("role") role: String,
    ): TppAuthorizationResponse
}

@ApplicationScoped
class TppAuthorizationGuard @Inject constructor(@RestClient private val client: TppRegistryRestClient) {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    fun requireAuthorized(tppId: String, role: String): TppAuthorizationResponse = try {
        client.checkAuthorization(tppId, role)
    } catch (e: WebApplicationException) {
        // tpp-registry's documented business denial is HTTP 403 with a TppAuthorizationResult
        // body (`authorized` is the JSON boolean false). Validate the raw node before DTO
        // construction so conversion defaults cannot broaden the accepted denial shape.
        // Translate only that exact, valid denial into the DTO the
        // filter already maps to public 401 CERTIFICATE_INVALID. A 401, 404, malformed 403, or
        // upstream failure remains an exception and therefore keeps the fail-closed 503 path.
        if (e.response.status != Response.Status.FORBIDDEN.statusCode) throw e
        val response = e.response
        val denial = try {
            val body = response.readEntity(JsonNode::class.java)
            val responseTppId = body.get("tppId")
            val authorized = body.get("authorized")
            val roles = body.get("roles")
            val reason = body.get("reason")
            val validShape = body.isObject &&
                responseTppId?.isTextual == true &&
                authorized?.isBoolean == true &&
                !authorized.booleanValue() &&
                roles?.isArray == true &&
                roles.elements().asSequence().all { it.isTextual } &&
                (reason == null || reason.isNull || reason.isTextual)
            if (!validShape || responseTppId.asText() != tppId) throw e
            TppAuthorizationResponse(
                tppId = responseTppId.asText(),
                authorized = authorized.booleanValue(),
                roles = roles.elements().asSequence().map { it.asText() }.toSet(),
                reason = reason?.takeUnless { it.isNull }?.asText(),
            )
        } catch (_: Exception) {
            closeFailedResponse(response, e)
            throw e
        }
        response.close()
        denial
    }

    private fun closeFailedResponse(response: Response, original: WebApplicationException) {
        try {
            response.close()
        } catch (closeFailure: ProcessingException) {
            original.addSuppressed(closeFailure)
        }
    }
}
