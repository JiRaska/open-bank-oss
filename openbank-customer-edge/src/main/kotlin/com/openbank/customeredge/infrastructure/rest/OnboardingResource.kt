// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.onboarding.OnboardingFunnelPublisher
import com.openbank.customeredge.infrastructure.webauthn.EnrollmentTicketService
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
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
    private val funnelPublisher: com.openbank.customeredge.infrastructure.onboarding.OnboardingFunnelPublisher,
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

        private const val STATUS_CREATED = 201
        private const val STATUS_NOT_FOUND = 404

        // Only the general terms are servable here — the framework agreement is per-party and
        // signed one step later (ADR-0169/0170), never fetched anonymously.
        private val SERVABLE_TERMS_CODES = setOf("VOP_CS", "VOP_EN")

        // Funnel telemetry (/events): a small, closed body. Kept far under /start's limit because a
        // funnel event is a handful of short enum-ish fields, never a document.
        private const val MAX_FUNNEL_BODY_BYTES = 1_024
        private const val MAX_FUNNEL_ATTR_LENGTH = 64
        private val SESSION_ID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        // The ONLY caller-supplied attributes forwarded into the store (each length-capped). No free map.
        private val FUNNEL_ATTRIBUTE_KEYS = listOf("kycMethod", "reason", "platform", "appVersion")
    }

    // Instance fields (the resource is a Quarkus singleton): per-lang cache of the serialized
    // /terms response so an anonymous burst can't fan out to document-service, and the
    // rendered-PDF document id per immutable (code, version) so the terms render once.
    private val termsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val renderedTermsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

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
     * Business onboarding-funnel telemetry sink (ADR-0069 Phase 2).
     *
     * The retail app posts one event per meaningful onboarding transition so the admin cockpit can
     * measure where prospects drop off — most of which happen BEFORE a Keycloak session exists (the
     * welcome/identity/email/consent steps), which is exactly why this is a dedicated anonymous stream
     * and not RUM (OIDC-gated, consent-gated, non-queryable). See [OnboardingFunnelPublisher].
     *
     * Anonymous like [startOnboarding], behind the same ingress per-IP rate limit. Because it is an
     * un-authenticated write that ultimately lands in a 10-year store, the body is size-capped and both
     * `step` and `action` are validated against closed allow-lists — an unknown value is a 400 with no
     * emission, so an abuser cannot inflate the warehouse with junk cardinality. `sessionId` is a
     * pseudonymous, client-generated onboarding id (never PII). Always answers 202 on a well-formed
     * body: telemetry is best-effort and must never gate onboarding, so a downstream Kafka issue is a
     * server-side ERROR log, not a client-visible failure.
     *
     * Body: {"sessionId":"...","step":"AGREEMENT","action":"HOLD_ABANDONED",
     *        "kycMethod":"BANKID"?,"reason":"..."?,"platform":"ios"?,"appVersion":"..."?}
     */
    @POST
    @Path("/events")
    @PermitAll
    @Blocking
    fun recordFunnelEvent(body: String): Response {
        if (body.length > MAX_FUNNEL_BODY_BYTES) {
            return Response.status(STATUS_PAYLOAD_TOO_LARGE)
                .entity("""{"error":"Request body too large"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val node = runCatching { jsonMapper.readTree(body) }.getOrElse {
            return Response.status(400)
                .entity("""{"error":"Invalid JSON"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val sessionId = node.get("sessionId")?.asText()?.trim().orEmpty()
        val step = node.get("step")?.asText()?.trim()?.uppercase().orEmpty()
        val action = node.get("action")?.asText()?.trim()?.uppercase().orEmpty()
        if (!SESSION_ID_PATTERN.matches(sessionId)) {
            return Response.status(400)
                .entity("""{"error":"sessionId must be a UUID"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        if (step !in OnboardingFunnelPublisher.VALID_STEPS || action !in OnboardingFunnelPublisher.VALID_ACTIONS) {
            return Response.status(400)
                .entity("""{"error":"unknown step or action"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        // Only a fixed, low-cardinality set of attributes is forwarded, each length-capped — the body is
        // anonymous and long-lived, so we never echo an arbitrary caller-supplied map into the store.
        val attributes = FUNNEL_ATTRIBUTE_KEYS.associateWith { key ->
            node.get(key)?.asText()?.trim()?.take(MAX_FUNNEL_ATTR_LENGTH)?.takeIf { it.isNotEmpty() }
        }
        funnelPublisher.emit(sessionId, step, action, attributes)
        return Response.status(202).build()
    }

    /**
     * The PUBLISHED general terms (VOP) the onboarding consent step asks the user to agree to.
     * Informed consent requires the text to be READABLE at the moment the tick happens, and at
     * that point there is no account, no token and no personalised document yet.
     *
     * Deliberately ONLY the terms — NOT the framework agreement. The agreement is a document the
     * user *signs*, and it is created per-party and signed one step later, after `account.created`
     * (ADR-0169/0170). Listing it here too made the consent step look like a second signing
     * screen and duplicated what the sign step already shows.
     *
     * Metadata only (code/name/version/publishedAt) — the readable bytes come from
     * [onboardingTermsContent] as a real PDF, the same artifact class the sign step renders.
     *
     * Same security class as [startOnboarding] above (the already-public onboarding surface,
     * behind the same ingress rate limit) — deliberately NOT a new exposure category: read-only,
     * serves only PUBLISHED terms (a bank's public terms — no PII, no party data).
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

        val wanted = "VOP_$l"
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
                .filter { it.get("status")?.asText() == "PUBLISHED" && it.get("code")?.asText() == wanted }
                .map {
                    mapOf(
                        "code" to it.get("code")?.asText(),
                        "name" to it.get("name")?.asText(),
                        "version" to it.get("version")?.asText(),
                        "publishedAt" to it.get("createdAt")?.asText(),
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

    /**
     * The PUBLISHED terms as a real PDF — the same artifact class the sign step renders, minus
     * any signature field (nothing is signed here; the user only reads before ticking consent).
     *
     * [code] is restricted to [SERVABLE_TERMS_CODES]: the framework agreement is per-party and
     * belongs to the authenticated sign step, so it can never be pulled through this anonymous
     * route even by guessing its template code.
     *
     * document-service has no stateless render — `POST /documents/render` always persists the
     * rendered artifact. A published template version is immutable, so the render is cached by
     * (code, version) and the rendered document id reused: one artifact per published version,
     * not one per curious visitor.
     */
    @GET
    @Path("/terms/{code}/content")
    @PermitAll
    @Blocking
    fun onboardingTermsContent(@PathParam("code") code: String): Response {
        val wanted = code.uppercase()
        if (wanted !in SERVABLE_TERMS_CODES) {
            return Response.status(STATUS_NOT_FOUND)
                .entity("""{"error":"unknown terms document"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }

        val listResp = upstream.get(
            "$documentServiceUrl/api/v1/documents/templates?limit=100",
            ONBOARDING_PARTY_PLACEHOLDER,
        )
        if (listResp.status != STATUS_OK) {
            return Response.status(STATUS_BAD_GATEWAY)
                .entity("""{"error":"document templates unavailable"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }
        val version = runCatching {
            jsonMapper.readTree((listResp.entity as? String).orEmpty())
                .firstOrNull {
                    it.get("code")?.asText() == wanted && it.get("status")?.asText() == "PUBLISHED"
                }?.get("version")?.asText()
        }.getOrNull()
            ?: return Response.status(STATUS_NOT_FOUND)
                .entity("""{"error":"no published version of this document"}""")
                .type(MediaType.APPLICATION_JSON).build()

        val documentId = renderedTermsCache.computeIfAbsent("$wanted@$version") {
            val renderBody = jsonMapper.writeValueAsString(
                mapOf("templateCode" to wanted, "contentType" to "application/pdf"),
            )
            val rendered = upstream.post(
                "$documentServiceUrl/api/v1/documents/render",
                ONBOARDING_PARTY_PLACEHOLDER,
                renderBody,
            )
            if (rendered.status != STATUS_CREATED) return@computeIfAbsent ""
            runCatching {
                jsonMapper.readTree((rendered.entity as? String).orEmpty()).get("id")?.asText()
            }.getOrNull().orEmpty()
        }
        if (documentId.isBlank()) {
            renderedTermsCache.remove("$wanted@$version") // don't cache a failed render
            return Response.status(STATUS_BAD_GATEWAY)
                .entity("""{"error":"could not render the terms"}""")
                .type(MediaType.APPLICATION_JSON).build()
        }

        // Accept MUST be wildcard, not application/pdf: document-service's content route is
        // @Produces(APPLICATION_OCTET_STREAM), so a narrow Accept makes RESTEasy fail method
        // selection and answer 404 "Unable to find matching target resource method" instead of
        // the bytes. Mirrors CustomerDocumentResource.documentContent, the working precedent.
        return upstream.getRaw(
            "$documentServiceUrl/api/v1/documents/$documentId/content",
            ONBOARDING_PARTY_PLACEHOLDER,
            MediaType.WILDCARD,
        )
    }
}
