// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.psd2.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.authz.Authorize
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.psd2.application.port.`in`.ConsentManagementUseCase
import com.openbank.psd2.application.port.`in`.CreateConsentCommand
import com.openbank.psd2.application.port.`in`.DeleteConsentCommand
import com.openbank.psd2.application.port.`in`.GetConsentQuery
import com.openbank.psd2.domain.model.ObConsentRequest
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/open-banking/v2/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConsentResource(
    private val consentMgmt: ConsentManagementUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @POST
    @Authorize(action = "psd2.create", resource = "")
    suspend fun createConsent(
        request: ObConsentRequest,
        @HeaderParam("TPP-Redirect-URI") redirectUri: String?,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String
            ?: return tppMissing()
        val tppName = ctx.getHeaderString("TPP-Name") ?: tppId

        require(!xRequestId.isNullOrBlank()) { "X-Request-ID header is required" }

        val idempotencyKey = consentCreateKey(tppId, xRequestId)
        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Replayed", "true")
                .build()
        }

        val consent = consentMgmt.createConsent(
            CreateConsentCommand(
                tppId = tppId,
                tppName = tppName,
                request = request,
                redirectUri = redirectUri,
                tppTransactionId = xRequestId,
                ipAddress = null,
            ),
        )
        idempotencyStore.save(idempotencyKey, 201, objectMapper.writeValueAsString(consent))
        return Response.status(201)
            .header("Location", "/open-banking/v2/consents/${consent.consentId}")
            .entity(consent).build()
    }

    @GET
    @Path("/{consentId}")
    @Authorize(action = "psd2.read", resource = "#consentId")
    suspend fun getConsent(
        @PathParam("consentId") consentId: String,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        return Response.ok(consentMgmt.getConsent(GetConsentQuery(consentId, tppId))).build()
    }

    @GET
    @Path("/{consentId}/status")
    @Authorize(action = "psd2.read", resource = "#consentId")
    suspend fun getConsentStatus(
        @PathParam("consentId") consentId: String,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val status = consentMgmt.getConsentStatus(GetConsentQuery(consentId, tppId))
        return Response.ok(mapOf("consentStatus" to status.name)).build()
    }

    @DELETE
    @Path("/{consentId}")
    @Authorize(action = "psd2.delete", resource = "#consentId")
    suspend fun deleteConsent(
        @PathParam("consentId") consentId: String,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        consentMgmt.deleteConsent(DeleteConsentCommand(consentId, tppId))
        return Response.noContent().build()
    }

    private fun tppMissing() = Response.status(401)
        .entity(mapOf("tppMessages" to listOf(mapOf("category" to "ERROR", "code" to "CERTIFICATE_MISSING")))).build()

    private fun consentCreateKey(tppId: String, requestId: String) = "psd2:consent:$tppId:$requestId"
}
