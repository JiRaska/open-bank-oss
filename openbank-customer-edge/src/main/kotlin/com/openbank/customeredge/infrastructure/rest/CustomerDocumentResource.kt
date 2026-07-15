// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.UUID

/**
 * Customer-facing document & signature routes (ADR-0169 D1), proxying `openbank-document-service`
 * with the edge M2M token. Every route is ownership-scoped: the caller can only ever touch their
 * OWN party's documents/ceremonies. `partyRef` is taken from the token — never from the client —
 * on writes, and reads verify the fetched resource's `partyRef`/signer matches the caller (IDOR is
 * the primary risk, ADR-0065).
 *
 * A separate resource from [CustomerEdgeResource] to keep that (already `@Suppress("LargeClass")`)
 * class from growing further; party resolution reuses its `resolvePartyIdClaim` companion helper.
 */
@Path("/customer/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerDocumentResource(private val upstream: UpstreamClient) {

    @Inject
    lateinit var jwt: JsonWebToken

    @ConfigProperty(name = "openbank.edge.document-service-url")
    lateinit var documentServiceUrl: String

    private val json = ObjectMapper()

    /**
     * Get-or-create the caller's onboarding framework agreement in the requested language
     * (ADR-0169 D3). `partyRef` is forced to the caller's token — the client only chooses `lang`.
     */
    @POST
    @Path("/documents/agreements")
    @Blocking
    fun ensureAgreement(body: String?): Response {
        val partyId = partyId()
        val lang = runCatching { json.readTree(body ?: "{}").get("lang")?.asText() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_LANG
        val upstreamBody = json.writeValueAsString(mapOf("partyRef" to partyId, "lang" to lang))
        return upstream.post("$documentServiceUrl/api/v1/documents/onboarding-agreement", partyId, upstreamBody)
    }

    /** Stream a document's PDF bytes — only if it belongs to the caller. */
    @GET
    @Path("/documents/{id}/content")
    @Blocking
    fun documentContent(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        val meta = upstream.get("$documentServiceUrl/api/v1/documents/$id", partyId)
        if (meta.status != OK) return notFound()
        if (ownerPartyOf(meta) != partyId) return notFound() // do not leak existence to a non-owner
        return upstream.getRaw("$documentServiceUrl/api/v1/documents/$id/content", partyId, MediaType.WILDCARD)
    }

    /** A signature ceremony's status — only if the caller is one of its signers. */
    @GET
    @Path("/signature-ceremonies/{id}")
    @Blocking
    fun ceremony(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        val ceremony = upstream.get("$documentServiceUrl/api/v1/signature-ceremonies/$id", partyId)
        if (ceremony.status != OK) return notFound()
        if (!signersOf(ceremony).contains(partyId)) return notFound()
        return ceremony
    }

    /**
     * Record the caller's signing decision on a ceremony (ADR-0169). `partyRef` is forced to the
     * caller's token; `evidenceRef` (the SCA challenge id) is passed through and verified upstream
     * by document-service's `SignerVerificationPort` against sca-service (ADR-0021).
     */
    @POST
    @Path("/signature-ceremonies/{id}/decisions")
    @Blocking
    fun recordDecision(@PathParam("id") id: UUID, body: String?): Response {
        val partyId = partyId()
        val ceremony = upstream.get("$documentServiceUrl/api/v1/signature-ceremonies/$id", partyId)
        if (ceremony.status != OK) return notFound()
        if (!signersOf(ceremony).contains(partyId)) return notFound()

        val inbound = runCatching { json.readTree(body ?: "{}") }.getOrNull() ?: return badRequest("Invalid JSON")
        val decision = inbound.get("decision")?.asText()?.takeIf { it.isNotBlank() }
            ?: return badRequest("decision is required")
        val forced = json.writeValueAsString(
            buildMap<String, Any?> {
                put("partyRef", partyId) // never trust a client-supplied signer identity
                put("decision", decision)
                inbound.get("evidenceRef")?.asText()?.takeIf { it.isNotBlank() }?.let { put("evidenceRef", it) }
            },
        )
        return upstream.post("$documentServiceUrl/api/v1/signature-ceremonies/$id/decisions", partyId, forced)
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────

    private fun partyId(): String = CustomerEdgeResource.resolvePartyIdClaim(
        partyIdClaim = jwt.getClaim<String>("party_id"),
        sub = jwt.subject,
    ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")

    private fun ownerPartyOf(response: Response): String? =
        runCatching { json.readTree(response.entity as? String ?: return null).get("partyRef")?.asText() }.getOrNull()

    private fun signersOf(response: Response): Set<String> = runCatching {
        json.readTree(response.entity as? String ?: return emptySet())
            .get("signers")?.mapNotNull { it.get("partyRef")?.asText() }?.toSet()
    }.getOrNull().orEmpty()

    private fun notFound() = Response.status(NOT_FOUND)
        .entity("""{"error":"Not found"}""").type(MediaType.APPLICATION_JSON).build()

    private fun badRequest(message: String) = Response.status(BAD_REQUEST)
        .entity("""{"error":"$message"}""").type(MediaType.APPLICATION_JSON).build()

    private companion object {
        const val DEFAULT_LANG = "cs"
        const val OK = 200
        const val NOT_FOUND = 404
        const val BAD_REQUEST = 400
    }
}
