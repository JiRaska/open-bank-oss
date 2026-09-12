// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Business onboarding for the customer app (ADR-0284 D6), proxying `openbank-kyb-service` with
 * the edge M2M token. **The initiator/signer identity is the token's HUMAN on every route** —
 * never the acting-for header (you onboard a company as yourself, not as another company) and
 * never a body field: a body naming a different `initiatorPartyId` is refused here before
 * kyb-service refuses it again on `X-Customer-Party-Id`.
 *
 * `/lookup` and `/schemes` are the entry screen — they create nothing and need no case.
 */
@Path("/customer/v1/business")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
@Suppress("TooManyFunctions") // one thin proxy per case transition
class CustomerBusinessResource(
    private val upstream: UpstreamClient,
    private val partyMergeResolver: PartyMergeResolver,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.edge.kyb-service-url", defaultValue = "http://kyb-service.kyb.svc:8157")
    lateinit var kybServiceUrl: String

    @GET
    @Path("/schemes")
    @Blocking
    fun schemes(@QueryParam("country") country: String?): Response {
        // ISO 3166-1 alpha-2 or nothing. Same reasoning as the invitation token below.
        val filter = country?.takeIf { it.isNotBlank() }
        require(filter == null || COUNTRY.matches(filter)) {
            "country must be an ISO 3166-1 alpha-2 code"
        }
        val q = filter?.let { "?country=${enc(it)}" }.orEmpty()
        return upstream.get("$kybServiceUrl$UPSTREAM/schemes$q", human().toString())
    }

    /**
     * Find a company by NAME rather than by identifier (#9707). The entry screen for a founder who
     * does not know their own IČO — it lives on an invoice, not in anyone's head.
     *
     * Read-only over a public register and creates nothing, like `/lookup` and `/schemes`.
     *
     * **What actually protects the upstream URL is [enc], and it is the only thing.** `country` is
     * additionally pinned to a two-letter shape and `limit` is an integer in a bounded range, so
     * neither can carry a metacharacter — but `name` and `city` are free text by nature: they are
     * length-bounded and percent-encoded, not shape-checked, because a company name legitimately
     * contains `&`, `/` and `#`. Unlike [claim] below, this route uses [UpstreamClient.get], whose
     * host regex constrains the authority and permits any path or query; so the encoding is
     * load-bearing rather than a second line of defence. `CustomerBusinessResourceTest` asserts the
     * encoded form of both parameters for exactly that reason.
     */
    @GET
    @Path("/search")
    @Blocking
    fun search(
        @QueryParam("country") country: String?,
        @QueryParam("name") name: String?,
        @QueryParam("city") city: String?,
        @QueryParam("limit") limit: Int?,
    ): Response {
        // Nullable + requireNotNull, never a non-null JAX-RS parameter: an absent one would be
        // injected as null and answer 500 (root CLAUDE.md, gate `nonnull-jaxrs-param-ratchet`).
        requireNotNull(country) { "query parameter 'country' is required" }
        requireNotNull(name) { "query parameter 'name' is required" }
        require(COUNTRY.matches(country)) { "country must be an ISO 3166-1 alpha-2 code" }
        val term = name.trim()
        require(term.length in MIN_TERM..MAX_TERM) { "name must be between $MIN_TERM and $MAX_TERM characters" }
        val town = city?.trim()?.takeIf { it.isNotEmpty() }
        require(town == null || town.length <= MAX_TERM) { "city must be at most $MAX_TERM characters" }
        require(limit == null || limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }

        val query = buildString {
            append("?country=").append(enc(country))
            append("&name=").append(enc(term))
            town?.let { append("&city=").append(enc(it)) }
            limit?.let { append("&limit=").append(it) }
        }
        return upstream.get("$kybServiceUrl$UPSTREAM/registry/search$query", human().toString())
    }

    @POST
    @Path("/lookup")
    @Blocking
    fun lookup(body: String): Response = upstream.post("$kybServiceUrl$UPSTREAM/lookup", human().toString(), body, null)

    /** Start a case. The initiator is filled in from the token; a conflicting one in the body is a 403. */
    @POST
    @Path("/onboarding")
    @Blocking
    fun start(body: String): Response {
        val me = human()
        val node = parse(body) ?: return badRequest("body must be a JSON object")
        val claimed = node.path("initiatorPartyId").takeIf { it.isTextual }?.asText()
        if (claimed != null &&
            claimed != me.toString()
        ) {
            return forbidden("initiatorPartyId is not the authenticated customer")
        }
        node.put("initiatorPartyId", me.toString())
        return upstream.post(
            "$kybServiceUrl$UPSTREAM/cases",
            me.toString(),
            objectMapper.writeValueAsString(node),
            null,
        )
    }

    /** Cases the caller initiated or is a signer on. */
    @GET
    @Path("/onboarding")
    @Blocking
    fun mine(): Response {
        val me = human()
        return upstream.get("$kybServiceUrl$UPSTREAM/cases?partyId=$me", me.toString())
    }

    @GET
    @Path("/onboarding/{id}")
    @Blocking
    fun get(@PathParam("id") id: UUID): Response = upstream.get("$kybServiceUrl$UPSTREAM/cases/$id", human().toString())

    @POST
    @Path("/onboarding/{id}/initiator")
    @Blocking
    fun matchInitiator(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/initiator", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/cosigners")
    @Blocking
    fun inviteCosigners(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/cosigners", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/sign")
    @Blocking
    fun sign(@PathParam("id") id: UUID, body: String): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/sign", human().toString(), body, null)

    @POST
    @Path("/onboarding/{id}/abandon")
    @Blocking
    fun abandon(@PathParam("id") id: UUID): Response =
        upstream.post("$kybServiceUrl$UPSTREAM/cases/$id/abandon", human().toString(), "{}", null)

    /** An invited co-signer, now identity-verified as themselves, claims the invitation. The party is the token's. */
    @POST
    @Path("/invitations/{token}/claim")
    @Blocking
    fun claim(@PathParam("token") token: String): Response {
        val me = human()
        // The token is the only caller-supplied value this class ever puts in a URL PATH, so it
        // is checked for shape before it is used. postToService constructs the allowed authority
        // and this path as separate URI components, so the token cannot influence a host, query,
        // fragment or user-info. An invitation token is opaque and URL-safe by construction, so
        // anything else is a malformed request, not a request to forward.
        require(TOKEN.matches(token)) { "invitation token has an unexpected shape" }
        return upstream.postToService(
            kybServiceUrl,
            "$UPSTREAM/invitations/$token/claim",
            me.toString(),
            """{"partyId":"$me"}""",
            null,
        )
    }

    private fun human(): UUID {
        val claim = CustomerEdgeResource.resolvePartyIdClaim(jwt.getClaim<String>("party_id"), jwt.subject)
            ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        val claimed = runCatching {
            UUID.fromString(claim)
        }.getOrElse { throw ForbiddenException("party_id claim is not a UUID") }
        return partyMergeResolver.resolve(claimed)
    }

    private fun parse(body: String): ObjectNode? = runCatching {
        objectMapper.readTree(body) as? ObjectNode
    }.getOrNull()

    private fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)

    private fun forbidden(message: String) =
        Response.status(Response.Status.FORBIDDEN).entity(mapOf("error" to message)).build()

    private fun badRequest(message: String) = Response.status(Response.Status.BAD_REQUEST).entity(
        mapOf(
            "error" to message,
        ),
    ).build()

    // Not private: the search bounds are asserted by CustomerBusinessResourceTest, and a test that
    // hard-codes the number instead would keep passing when the bound moves.
    internal companion object {
        const val UPSTREAM = "/api/v1/kyb"

        /** An invitation token is opaque, URL-safe and bounded; anything else is malformed. */
        val TOKEN = Regex("^[A-Za-z0-9_-]{8,128}$")
        val COUNTRY = Regex("^[A-Za-z]{2}$")

        /**
         * [MIN_TERM] and [MAX_LIMIT] mirror kyb-service's own `RegistrySearchQuery` exactly, so the
         * two sides cannot disagree about what is too short to be worth asking a public register,
         * or about how many rows may come back.
         *
         * [MAX_TERM] mirrors NOTHING — kyb-service requires only that the name is not blank. It is
         * a bound on what this edge will put in a URL, not a claim about what the register accepts,
         * and it is set well above any real company name for that reason: at 100 it silently made
         * long-named entities unsearchable, and Czech cooperative and association names run past
         * that routinely.
         */
        const val MIN_TERM = 3
        const val MAX_TERM = 500
        const val MAX_LIMIT = 50
    }
}
