// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Instant

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
class OnboardingResource(
    private val upstream: UpstreamClient,
    private val enrollmentTicketService: EnrollmentTicketService,
) {

    companion object {
        private const val MAX_BODY_BYTES = 4_096
        private const val MAX_LEGAL_NAME_LENGTH = 500
        private const val STATUS_PAYLOAD_TOO_LARGE = 413
        private val VALID_PARTY_TYPES = setOf("INDIVIDUAL", "LEGAL_ENTITY", "SOLE_TRADER")

        private const val TERMS_TTL_MS = 5 * 60 * 1000L
        private const val STATUS_OK = 200
        private const val STATUS_BAD_GATEWAY = 502

        // The X-Customer-Party-Id header value for the pre-party terms call — document-service's
        // template routes don't scope by party, but UpstreamClient always sends the header.
        private const val ONBOARDING_PARTY_PLACEHOLDER = "onboarding-anonymous"
    }

    // Instance field (the resource is a Quarkus singleton): per-lang cache of the serialized
    // /terms response so an anonymous burst can't fan out to document-service.
    private val termsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    @Inject
    lateinit var jsonMapper: ObjectMapper

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var customerEdge: CustomerEdgeResource

    @ConfigProperty(name = "openbank.edge.party-service-url")
    lateinit var partyServiceUrl: String

    @ConfigProperty(name = "openbank.edge.document-service-url")
    lateinit var documentServiceUrl: String

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
        // ADR-0066 F2 (native passkey, variant B1): a short-lived HMAC ticket the app can present
        // as a bearer credential to WebAuthnResource's register/begin+complete BEFORE any
        // Keycloak session exists — see EnrollmentTicketService's KDoc for why it can't just be a
        // Keycloak JWT. Harmless to always issue: it does nothing unless AppConfig.useNativePasskey
        // on the app side actually consumes it.
        val enrollmentTicket = enrollmentTicketService.issue(partyId)
        return Response.status(201)
            .entity("""{"partyId":"$partyId","status":"PENDING_ACTIVATION","enrollmentTicket":"$enrollmentTicket"}""")
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    @POST
    @Path("/register")
    @RolesAllowed("ROLE_CUSTOMER")
    @Blocking
    fun registerParty(body: String): Response = customerEdge.registerParty(body)

    /**
     * The PUBLISHED contractual documents shown on the onboarding consent step (framework
     * agreement + general terms), before any credential exists — informed consent requires the
     * text to be READABLE at the moment the user ticks "I agree", and at that point there is no
     * account, no token and no personalised document yet (the personalised agreement is created
     * and signed after `account.created`, ADR-0169/0170).
     *
     * Same security class as [startOnboarding] above (the already-public onboarding surface,
     * behind the same ingress rate limit) — deliberately NOT a new exposure category: read-only,
     * serves only PUBLISHED template text (a bank's public terms — no PII, no party data, no
     * template internals beyond what every prospective customer must be able to read anyway).
     *
     * Upstream call uses the edge M2M token against document-service's template list (the same
     * ROLE_OPERATOR-gated route the admin cockpit reads); responses are cached for [TERMS_TTL_MS]
     * per lang so an anonymous burst can't fan out to document-service.
     */
    @GET
    @Path("/terms")
    @PermitAll
    @Blocking
    fun onboardingTerms(@QueryParam("lang") lang: String?): Response {
        val l = if (lang?.lowercase() == "en") "EN" else "CS"
        val now = Instant.now().toEpochMilli()
        termsCache[l]?.let { (at, body) ->
            if (now - at < TERMS_TTL_MS) {
                return Response.ok(body).type(MediaType.APPLICATION_JSON).build()
            }
        }

        val wanted = listOf("RAMCOVA_SMLOUVA_$l", "VOP_$l")
        val resp = upstream.get(
            "$documentServiceUrl/api/v1/documents/templates?limit=100",
            ONBOARDING_PARTY_PLACEHOLDER,
        )
        val raw = (resp.entity as? String).orEmpty()
        if (resp.status != STATUS_OK) {
            return Response.status(STATUS_BAD_GATEWAY)
                .entity("""{"error":"document templates unavailable"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val docs = runCatching {
            jsonMapper.readTree(raw)
                .filter { it.get("status")?.asText() == "PUBLISHED" && it.get("code")?.asText() in wanted }
                // Stable order: agreement first, then terms — the consent screen renders in order.
                .sortedBy { wanted.indexOf(it.get("code")?.asText()) }
                .map {
                    mapOf(
                        "code" to it.get("code")?.asText(),
                        "name" to it.get("name")?.asText(),
                        "version" to it.get("version")?.asText(),
                        "publishedAt" to it.get("createdAt")?.asText(),
                        "html" to it.get("bodyHtml")?.asText(),
                    )
                }
        }.getOrElse {
            return Response.status(STATUS_BAD_GATEWAY)
                .entity("""{"error":"document templates unreadable"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val body = jsonMapper.writeValueAsString(mapOf("documents" to docs))
        termsCache[l] = now to body
        return Response.ok(body).type(MediaType.APPLICATION_JSON).build()
    }
}
