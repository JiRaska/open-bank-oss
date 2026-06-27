// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * Public onboarding entry point (ADR-0069).
 *
 * This is a SEPARATE resource class from [CustomerEdgeResource] precisely because it
 * has NO class-level `@RolesAllowed`. On [CustomerEdgeResource] the class-level
 * `@RolesAllowed("ROLE_CUSTOMER")` pre-empts a method-level `@PermitAll`: Quarkus
 * issues a 401 OIDC challenge (`www-authenticate: Bearer`) before the method
 * annotation is evaluated, even with lazy authentication
 * (`quarkus.http.auth.proactive=false`). Hosting the unauthenticated start route in
 * its own un-annotated class is the only reliable way to make it truly public, so the
 * retail app can register before it has a Keycloak session.
 *
 * Security: the route is intentionally anonymous. It creates a PENDING_ACTIVATION
 * party via the edge M2M operator token (party-service requires ROLE_OPERATOR). The
 * per-IP rate limit on the ingress (limit-rps) is the first line of abuse defence;
 * bot/spam hardening (e.g. proof-of-work or email-verification gate) is tracked as an
 * ADR-0069 Phase 2 follow-up.
 *
 * Request body: {"partyType": "INDIVIDUAL", "legalName": "...", "taxId": "..."}
 * Response:     {"partyId": "...", "status": "PENDING_ACTIVATION"}
 */
@Path("/customer/v1/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OnboardingResource(private val upstream: UpstreamClient) {

    companion object {
        private const val MAX_BODY_BYTES = 4_096
        private const val MAX_LEGAL_NAME_LENGTH = 500
        private const val STATUS_PAYLOAD_TOO_LARGE = 413
        private val VALID_PARTY_TYPES = setOf("INDIVIDUAL", "LEGAL_ENTITY", "SOLE_TRADER")
    }

    @Inject
    lateinit var jsonMapper: ObjectMapper

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var customerEdge: CustomerEdgeResource

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @POST
    @Path("/start")
    @PermitAll
    @Blocking
    fun startOnboarding(body: String): Response {
        if (body.length > MAX_BODY_BYTES) {
            return Response.status(STATUS_PAYLOAD_TOO_LARGE)
                .entity("""{"error":"Request body too large"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val node = runCatching { jsonMapper.readTree(body) }.getOrElse {
            return Response.status(400)
                .entity("""{"error":"Invalid JSON"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val partyType = node.get("partyType")?.asText()?.trim()
        val legalName = node.get("legalName")?.asText()?.trim()
        if (partyType.isNullOrBlank() || legalName.isNullOrBlank()) {
            return Response.status(400)
                .entity("""{"error":"Required fields: partyType, legalName"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        if (partyType !in VALID_PARTY_TYPES) {
            return Response.status(400)
                .entity("""{"error":"partyType must be one of: INDIVIDUAL, LEGAL_ENTITY, SOLE_TRADER"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        if (legalName.length > MAX_LEGAL_NAME_LENGTH) {
            return Response.status(400)
                .entity("""{"error":"legalName must not exceed 500 characters"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        // Create party via M2M (party-service requires ROLE_OPERATOR; edge M2M has it).
        // postAnonymous already buffers the upstream body into a String entity, so read it
        // back via getEntity() — readEntity() is a client-side API and throws on a
        // server-built Response.
        val partyResponse = upstream.postAnonymous("$partyServiceUrl/api/v1/parties", body)
        val partyJson = (partyResponse.entity as? String).orEmpty()
        if (partyResponse.status != 201) {
            return Response.status(partyResponse.status)
                .entity(partyJson)
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        // Extract partyId from response and wrap for the mobile client
        val partyId = runCatching { jsonMapper.readTree(partyJson).get("id")?.asText() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: return Response.status(502)
                .entity("""{"error":"party-service did not return an id"}""")
                .type(MediaType.APPLICATION_JSON)
                .build()
        return Response.status(201)
            .entity("""{"partyId":"$partyId","status":"PENDING_ACTIVATION"}""")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    @POST
    @Path("/register")
    @RolesAllowed("ROLE_CUSTOMER")
    @Blocking
    fun registerParty(body: String): Response = customerEdge.registerParty(body)
}
