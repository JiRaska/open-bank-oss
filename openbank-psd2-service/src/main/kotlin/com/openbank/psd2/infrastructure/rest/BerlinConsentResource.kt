// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

/**
 * Berlin Group NextGenPSD2 XS2A 1.3.12 — Consent endpoints (ADR-0090 P1).
 *
 * Reuses [ConsentManagementUseCase] (same domain as the deprecated `/open-banking/v2`); only the
 * path, headers and wire shape are Berlin-conformant ([BerlinXs2aMappers]). TPP auth + AISP role
 * are enforced upstream by EidasMtlsFilter (now covering `v1/`). `X-Request-ID` is mandatory and
 * echoed back per spec.
 */
@Path("/v1/consents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class BerlinConsentResource(
    private val consentMgmt: ConsentManagementUseCase,
    private val idempotencyStore: IdempotencyStore,
    private val objectMapper: ObjectMapper,
) {
    @POST
    @Authorize(action = "psd2.create", resource = "")
    suspend fun createConsent(
        request: ObConsentRequest,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @HeaderParam("TPP-Redirect-URI") redirectUri: String?,
        @HeaderParam("PSU-IP-Address") psuIp: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        if (xRequestId.isNullOrBlank()) return missingRequestId()
        val tppName = ctx.getHeaderString("TPP-Name") ?: tppId

        val idempotencyKey = "psd2:v1:consent:$tppId:$xRequestId"
        idempotencyStore.get(idempotencyKey)?.let { cached ->
            return Response.status(cached.statusCode)
                .entity(cached.responseBody)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Request-ID", xRequestId)
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
                ipAddress = psuIp,
            ),
        )
        val body = BerlinXs2aMappers.consentCreated(consent)
        idempotencyStore.save(idempotencyKey, Response.Status.CREATED.statusCode, objectMapper.writeValueAsString(body))
        return Response.status(Response.Status.CREATED)
            .header("X-Request-ID", xRequestId)
            .header("Location", "/v1/consents/${consent.consentId}")
            .entity(body)
            .build()
    }

    @GET
    @Path("/{consentId}")
    @Authorize(action = "psd2.read", resource = "#consentId")
    suspend fun getConsent(
        @PathParam("consentId") consentId: String,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val consent = consentMgmt.getConsent(GetConsentQuery(consentId, tppId))
        return echo(xRequestId).entity(BerlinXs2aMappers.consentInformation(consent)).build()
    }

    @GET
    @Path("/{consentId}/status")
    @Authorize(action = "psd2.read", resource = "#consentId")
    suspend fun getConsentStatus(
        @PathParam("consentId") consentId: String,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        val status = consentMgmt.getConsentStatus(GetConsentQuery(consentId, tppId))
        return echo(xRequestId).entity(mapOf("consentStatus" to BerlinXs2aMappers.consentStatus(status))).build()
    }

    @DELETE
    @Path("/{consentId}")
    @Authorize(action = "psd2.delete", resource = "#consentId")
    suspend fun deleteConsent(
        @PathParam("consentId") consentId: String,
        @HeaderParam("X-Request-ID") xRequestId: String?,
        @Context ctx: ContainerRequestContext,
    ): Response {
        val tppId = ctx.getProperty("tppId") as? String ?: return tppMissing()
        consentMgmt.deleteConsent(DeleteConsentCommand(consentId, tppId))
        return echo(xRequestId).status(Response.Status.NO_CONTENT).build()
    }

    private fun echo(xRequestId: String?): Response.ResponseBuilder =
        Response.ok().apply { xRequestId?.let { header("X-Request-ID", it) } }

    private fun tppMissing() = Response.status(Response.Status.UNAUTHORIZED)
        .entity(BerlinXs2aMappers.tppError("CERTIFICATE_MISSING")).build()

    private fun missingRequestId() = Response.status(Response.Status.BAD_REQUEST)
        .entity(BerlinXs2aMappers.tppError("FORMAT_ERROR", "X-Request-ID header is mandatory")).build()
}
