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
class CustomerDocumentResource(private val upstream: UpstreamClient, private val grants: DelegationGrants) {

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

    /**
     * List the caller's documents (ADR-0169 D1). `partyRef` is forced to the token, so the upstream
     * query can only ever return the caller's own rows. The upstream projection carries object-store
     * coordinates (`storageKey`, `sha256`) that a customer client has no business seeing, so the
     * response is re-projected onto a customer-safe field set rather than proxied verbatim.
     */
    @GET
    @Path("/documents")
    @Blocking
    fun listDocuments(): Response {
        val partyId = partyId()
        val upstreamResp = upstream.get("$documentServiceUrl/api/v1/documents?partyRef=$partyId", partyId)
        if (upstreamResp.status != OK) return upstreamResp
        val body = upstreamResp.entity as? String ?: return Response.ok("[]").build()
        val docs = runCatching { json.readTree(body) }.getOrNull()
            ?: return Response.ok("[]").build()
        // document-service returns rows unordered; a document list is read newest-first, so sort here
        // rather than making every client do it.
        val mine = docs.filter { it.get("partyRef")?.asText() == partyId }
        // Template families that still have a live document, e.g. RAMCOVA_SMLOUVA for a party whose
        // RAMCOVA_SMLOUVA_CS was superseded by RAMCOVA_SMLOUVA_EN.
        val supersededFamilies = mine
            .filterNot { it.get("status")?.asText().equals(STATUS_ARCHIVED, ignoreCase = true) }
            .mapNotNull { it.get("templateCode")?.asText()?.let(::templateFamily) }
            .toSet()
        val safe = mine.sortedByDescending { it.get("createdAt")?.asText().orEmpty() }.mapNotNull { doc ->
            // Hide an ARCHIVED revision only when a live one of the same family replaced it —
            // otherwise it IS the customer's only copy. Blanket-hiding ARCHIVED looked right until
            // the sandbox showed every account agreement sitting in that state: the filter would
            // have silently removed a contract the customer has no other way to reach.
            val archived = doc.get("status")?.asText().equals(STATUS_ARCHIVED, ignoreCase = true)
            val family = doc.get("templateCode")?.asText()?.let(::templateFamily)
            if (archived && family in supersededFamilies) return@mapNotNull null
            buildMap<String, Any?> {
                put("id", doc.get("id")?.asText())
                put("templateCode", doc.get("templateCode")?.asText())
                put("templateVersion", doc.get("templateVersion")?.asText())
                put("contentType", doc.get("contentType")?.asText())
                put("sizeBytes", doc.get("sizeBytes")?.asLong())
                put("status", doc.get("status")?.asText())
                put("createdAt", doc.get("createdAt")?.asText())
            }
        }
        // Documents shared WITH the caller belong in the list too — without this a grantee accepts
        // a share and then has no way to reach what they were given. Appended and marked, never
        // blended: "sharedWithMe" is how the app can say whose document it is, so a delegate does
        // not come to believe they own what they were lent. Own documents are unaffected when
        // delegation-service is unreachable.
        val shared = sharedDocuments(upstream, json, documentServiceUrl, grants, partyId)
        val out = if (shared.isEmpty()) safe else safe + shared
        return Response.ok(json.writeValueAsString(out)).type(MediaType.APPLICATION_JSON).build()
    }

    /**
     * Stream a document's PDF bytes — to its owner, or to someone it was shared with.
     *
     * Delegated access over a DOCUMENT (ADR-0232) was until now a grant nobody could use: the app
     * could offer one and this route still answered 404, because ownership was the only question
     * asked. Same defect as accounts had before #4021, one level down — and reintroduced the
     * moment the app grew a share button on a document row.
     *
     * A non-owner needs an ACTIVE grant carrying OBJECT_READ on THIS document. Anything else is
     * still 404 rather than 403: a stranger must not learn that a document id exists.
     */
    @GET
    @Path("/documents/{id}/content")
    @Blocking
    fun documentContent(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        val meta = upstream.get("$documentServiceUrl/api/v1/documents/$id", partyId)
        if (meta.status != OK) return notFound()
        if (ownerPartyOf(meta) != partyId && !grants.has(partyId, "DOCUMENT", id.toString(), "OBJECT_READ")) {
            return notFound()
        }
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

    // ADR-0284 D4: an owner sharing a BUSINESS account (or reading a business document) does it
    // while acting for the entity, so the profile switch applies here exactly as on the main
    // resource. Fail-closed; a request without the header is the personal profile as before.
    @Inject
    lateinit var actingForResolver: ActingForResolver

    @jakarta.ws.rs.core.Context
    lateinit var requestHeaders: jakarta.ws.rs.core.HttpHeaders

    private fun partyId(): String {
        val claimed = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = jwt.getClaim<String>("party_id"),
            sub = jwt.subject,
        ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val human = runCatching {
            UUID.fromString(claimed)
        }.getOrElse { throw ForbiddenException("party_id claim is not a valid party UUID") }
        val actingFor = if (this::requestHeaders.isInitialized) {
            requestHeaders.getHeaderString(
                CustomerEdgeResource.ACTING_FOR_HEADER,
            )
        } else {
            null
        }
        val resolved = if (this::actingForResolver.isInitialized) actingForResolver.resolve(human, actingFor) else human
        return resolved.toString()
    }

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
        const val STATUS_ARCHIVED = "ARCHIVED"
    }
}

/** `RAMCOVA_SMLOUVA_CS` and `RAMCOVA_SMLOUVA_EN` are two languages of one contract, not two. */
private fun templateFamily(templateCode: String): String = templateCode.removeSuffix("_CS").removeSuffix("_EN")

private const val HTTP_OK = 200

/**
 * Documents someone else shared with this caller, projected onto the same customer-safe field set
 * the owner's own documents get, plus `sharedWithMe` so the app can say whose document it is.
 *
 * File-level rather than a member: it is a pure projection over a grant lookup and a fetch, and the
 * resource class is already at its function budget.
 */
private fun sharedDocuments(
    upstream: UpstreamClient,
    json: com.fasterxml.jackson.databind.ObjectMapper,
    documentServiceUrl: String,
    grants: DelegationGrants,
    partyId: String,
): List<Map<String, Any?>> = grants.activeResourceIds(partyId, "DOCUMENT", "OBJECT_READ")
    .mapNotNull { id ->
        upstream.get("$documentServiceUrl/api/v1/documents/$id", partyId)
            .takeIf { it.status == HTTP_OK }
    }
    .mapNotNull { r -> runCatching { json.readTree(r.entity?.toString() ?: "") }.getOrNull() }
    .map { doc ->
        buildMap<String, Any?> {
            put("id", doc.textOrNull("id"))
            put("templateCode", doc.textOrNull("templateCode"))
            put("templateVersion", doc.textOrNull("templateVersion"))
            put("contentType", doc.textOrNull("contentType"))
            put("sizeBytes", doc.get("sizeBytes")?.asLong())
            put("status", doc.textOrNull("status"))
            put("createdAt", doc.textOrNull("createdAt"))
            put("sharedWithMe", true)
        }
    }
