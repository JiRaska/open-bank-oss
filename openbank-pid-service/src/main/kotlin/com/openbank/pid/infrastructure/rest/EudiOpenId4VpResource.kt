// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.rest

import com.openbank.libs.authz.Authorize
import com.openbank.pid.infrastructure.openid4vp.AuthorizationRequestFactory
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore
import com.openbank.pid.infrastructure.rest.dto.CreatePresentationRequestResponse
import com.openbank.pid.infrastructure.rest.dto.PresentationExchangeStatusResponse
import com.openbank.pid.infrastructure.rest.dto.toResponse
import com.openbank.pid.infrastructure.rest.dto.toVerifyResponse
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jose4j.base64url.Base64Url
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * OpenID4VP Relying-Party request side (eIDAS 2.0, ADR-0094 — cross-device `direct_post` profile).
 *
 * The verifier-initiated flow: a back-end caller (M2M) starts an exchange here and renders the
 * returned `openid4vp://` URI as a QR / deep-link; the wallet POSTs its presentation to the public
 * [EudiWalletCallbackResource]; this resource's poll endpoint then collects the resolved decision.
 * The cryptographic verification (signature, trust, disclosure binding, holder key-binding to the
 * single-use nonce) runs in the callback via the existing verifier — fail-closed throughout.
 */
@Path("/api/v1/parties/eudi")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "EUDI identity", description = "eIDAS 2.0 wallet presentation verification (ADR-0094)")
class EudiOpenId4VpResource(
    private val exchangeStore: PresentationExchangeStore,
    private val requestFactory: AuthorizationRequestFactory,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    @POST
    @Path("/presentation-requests")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.request")
    @Operation(summary = "Start an OpenID4VP presentation exchange; returns the wallet authorization request URI")
    suspend fun createPresentationRequest(): Response {
        val transactionId = UUID.randomUUID().toString()
        val nonce = newNonce()
        val now = Instant.now(clock)
        val exchange = exchangeStore.create(transactionId, nonce, requestFactory.audience, now)
        val uri = requestFactory.buildAuthorizationRequestUri(transactionId, nonce)
        return Response.ok(
            CreatePresentationRequestResponse(
                transactionId = transactionId,
                authorizationRequestUri = uri,
                expiresAt = exchange.expiresAt,
            ),
        ).build()
    }

    @GET
    @Path("/presentation-requests/{id}")
    @RolesAllowed("ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN")
    @Authorize(action = "identity.eudi.poll")
    @Operation(summary = "Poll an OpenID4VP exchange for the wallet's resolved presentation result")
    suspend fun getPresentationExchange(@PathParam("id") id: String): Response {
        val exchange = exchangeStore.find(id, Instant.now(clock))
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity("""{"error":"unknown transaction"}""").build()
        val result = exchange.result
        val verified = result?.toVerifyResponse()
        return Response.ok(
            PresentationExchangeStatusResponse(
                transactionId = exchange.transactionId,
                status = exchange.status.name,
                decision = verified?.decision,
                partyId = verified?.partyId,
                caseId = verified?.caseId,
                verifiedClaims = result?.claims?.toResponse(),
                loa = verified?.loa,
            ),
        ).build()
    }

    @GET
    @Path("/request-objects/{id}")
    @PermitAll
    @Produces("application/oauth-authz-req+jwt")
    @Operation(summary = "Signed Request Object (JAR) a wallet dereferences via request_uri (ADR-0094)")
    suspend fun requestObject(@PathParam("id") id: String): Response {
        val exchange = exchangeStore.find(id, Instant.now(clock))
            ?.takeIf { it.status == PresentationExchangeStore.Status.PENDING }
            ?: return Response.status(Response.Status.NOT_FOUND).entity("""{"error":"unknown request"}""").build()
        val jwt = requestFactory.buildRequestObject(id, exchange.nonce)
            ?: return Response.status(
                Response.Status.NOT_FOUND,
            ).entity("""{"error":"signed requests unavailable"}""").build()
        return Response.ok(jwt).build()
    }

    private fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    private companion object {
        const val NONCE_BYTES = 32
    }
}
