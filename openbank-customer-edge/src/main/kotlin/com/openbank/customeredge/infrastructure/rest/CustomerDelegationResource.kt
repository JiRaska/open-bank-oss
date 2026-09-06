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
import jakarta.ws.rs.DELETE
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
 * Customer-facing delegated-access routes (ADR-0232 D6), proxying `openbank-delegation-service`
 * with the edge M2M token. Without this class the service is merged but unreachable: the app
 * authenticates against the `openbank-customers` realm, which upstream services do not accept.
 *
 * **The party identity is taken from the token on EVERY route and never from the client.** That is
 * the whole security property of this file, and it is why the routes do not simply mirror the
 * upstream paths: upstream exposes `/grantor/{partyId}` and takes `granteePartyId` as a query
 * parameter, both of which a customer client must not be able to choose. Here the caller says
 * *which side of the relationship they are asking about* ("shared by me" / "shared with me") and
 * the edge supplies *who they are*.
 *
 * delegation-service independently re-checks the same thing via `X-Customer-Party-Id`
 * (`requireCallerIs`, added in #3164) — this is the outer half of that pair, not a replacement for
 * it. The upstream check is what makes a direct call safe; this one is what makes the customer
 * realm work at all.
 *
 * A separate resource from [CustomerEdgeResource] to keep that (already `@Suppress("LargeClass")`)
 * class from growing further, matching [CustomerDocumentResource]; party resolution reuses its
 * `resolvePartyIdClaim` companion helper.
 */
@Path("/customer/v1/delegations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
// TooManyFunctions fires AT 11, and `activity` is the eleventh. The alternative was to drop the
// `refuse`/`forbidden` consolidation this class already made to stay under it, or to put the
// grantor's activity view in a twelfth file away from the grants it is about — neither buys
// anything a reader wants. The routes are one thin proxy each; the count is not the complexity.
@Suppress("TooManyFunctions")
class CustomerDelegationResource(private val upstream: UpstreamClient) {

    @Inject
    lateinit var jwt: JsonWebToken

    @ConfigProperty(
        name = "openbank.edge.delegation-service-url",
        defaultValue = "http://delegation-service.delegation.svc:8126",
    )
    lateinit var delegationServiceUrl: String

    @ConfigProperty(name = "openbank.edge.audit-service-url")
    lateinit var auditServiceUrl: String

    private val json = ObjectMapper()

    /**
     * Grants the caller has ISSUED ("Sdílím"). The upstream path is party-scoped and the party comes
     * from the token, so this can only ever list the caller's own grants.
     */
    @GET
    @Path("/shared-by-me")
    @Blocking
    fun sharedByMe(): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/grantor/$partyId", partyId)
    }

    /** Grants the caller has RECEIVED ("Sdíleno se mnou"), including offers awaiting a response. */
    @GET
    @Path("/shared-with-me")
    @Blocking
    fun sharedWithMe(): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/grantee/$partyId", partyId)
    }

    /**
     * "What did they do with my account" — the grantor's transparency view (ADR-0232 D5,
     * #2990 AC10). Every action a delegate took under a grant the CALLER issued.
     *
     * Sourced from audit-service, not from delegation-service: a grant records what someone *may*
     * do, and the question here is what they *did*. The tamper-evident chain is the only place
     * that is written down, and it is written at the edge because the edge is the only tier that
     * still holds the real customer identity.
     *
     * The grantor is the token's party and cannot be chosen by the client, same property as every
     * other route in this file. `?delegatePartyId=` and `?delegationId=` narrow the view and are
     * safe to pass through: they can only ever REMOVE rows from a set already scoped to the
     * caller, so a guessed value yields an empty list and no oracle.
     *
     * Deliberately NOT merged into `/privacy/access-log`. That view answers "what happened to my
     * party record"; on a delegated action the grantor is neither the actor nor the aggregate, so
     * the two queries have no rows in common and folding them together would hide exactly the
     * distinction — someone else acting — that this view exists to surface.
     */
    @GET
    @Path("/activity")
    @Blocking
    fun activity(
        @QueryParam("delegatePartyId") delegatePartyId: String?,
        @QueryParam("delegationId") delegationId: String?,
        @QueryParam("limit") limit: Int?,
    ): Response {
        val partyId = partyId()

        // URL-encoded, not interpolated raw: an unencoded `&` in a client-supplied value would
        // append query parameters of the caller's choosing to an upstream call made with the
        // edge's own M2M identity.
        fun enc(v: String) = URLEncoder.encode(v, StandardCharsets.UTF_8)
        val query = buildList {
            delegatePartyId?.takeIf { it.isNotBlank() }?.let { add("delegatePartyId=${enc(it)}") }
            delegationId?.takeIf { it.isNotBlank() }?.let { add("delegationId=${enc(it)}") }
            limit?.let { add("limit=${it.coerceIn(1, MAX_ACTIVITY_PAGE)}") }
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return upstream.get("$auditServiceUrl/api/v1/audit/on-behalf-of/$partyId$query", partyId)
    }

    /**
     * One grant the caller is a party to. Upstream answers 404 when the caller is neither grantor
     * nor grantee, so a guessed id yields no existence oracle and the edge adds no check of its own.
     *
     * Declared AFTER `/activity`: JAX-RS prefers a literal segment over a template, but keeping the
     * two adjacent in source order is what makes that non-collision visible to the next reader.
     */
    @GET
    @Path("/{id}")
    @Blocking
    fun getById(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.get("$delegationServiceUrl$UPSTREAM/$id", partyId)
    }

    /**
     * Validate the complete draft before the app starts SCA. The authoritative service repeats
     * every check during [offer], so preview creates no authority and cannot be used as a stale
     * authorization decision. The edge still derives the grantor from the customer token.
     */
    @POST
    @Path("/preview")
    @Blocking
    fun preview(body: String?): Response {
        val partyId = partyId()
        val node = runCatching { json.readTree(body ?: "{}") as? ObjectNode }.getOrNull()
            ?: return refuse(Response.Status.BAD_REQUEST, "Body must be a JSON object")
        val declared = node.get(FIELD_GRANTOR)?.asText()?.takeIf { it.isNotBlank() }
        if (declared != null && declared != partyId) {
            return refuse(Response.Status.FORBIDDEN, "grantorPartyId must be the authenticated party")
        }
        node.put(FIELD_GRANTOR, partyId)
        node.remove(FIELD_GRANT_SCA_SESSION)
        return upstream.post("$delegationServiceUrl$UPSTREAM/preview", partyId, json.writeValueAsString(node))
    }

    /**
     * Offer a grant over one of the caller's own resources (SCA-bound, purpose `DELEGATION_GRANT`).
     *
     * `grantorPartyId` is FORCED to the token's party. A body naming a different grantor is
     * rejected with 403 rather than silently rewritten: a client that sends the wrong id has a bug,
     * and quietly issuing a grant the user did not ask for is the worse of the two outcomes. An
     * absent field is filled in, since the app has no reason to send it at all.
     *
     * `dailyLimit`/`monthlyLimit` USED to be refused here, on the ground that nothing in the
     * platform counted cumulative spend against a grant, so a ceiling set through this route would
     * be stored, echoed back and never applied to a single payment. That premise is no longer true:
     * delegation-service owns the authoritative reservation counter (ADR-0249 D3), and the edge now
     * reserves against it before initiating a delegated payment, confirming on acceptance and
     * releasing on failure.
     *
     * Keeping the refusal past that point turned a safeguard into a total deadlock. delegation-
     * service REQUIRES a cumulative ceiling on any grant carrying `ACCOUNT_INITIATE_PAYMENT`
     * (ADR-0249 D5 — no unlimited access to someone else's account by omission), so with a ceiling
     * this route answered 400 CUMULATIVE_LIMIT_UNSUPPORTED and without one upstream answered 400
     * SPEND_WITHOUT_CEILING. A payment-capable grant was unconstructible through the customer
     * channel — which silently made `POST /cards/delegated` unreachable too, since an
     * additional-cardholder card requires exactly such a grant to exist.
     *
     * The body is therefore pass-through in full: grantee, resource, capabilities,
     * perTransactionLimit, cumulative ceilings and SCA session. delegation-service owns that
     * validation, verifies resource ownership, and is the single binding gate for the ceiling rules.
     */
    @POST
    @Blocking
    fun offer(body: String?): Response {
        val partyId = partyId()
        val node = runCatching { json.readTree(body ?: "{}") as? ObjectNode }.getOrNull()
            ?: return refuse(Response.Status.BAD_REQUEST, "Body must be a JSON object")
        val declared = node.get(FIELD_GRANTOR)?.asText()?.takeIf { it.isNotBlank() }
        if (declared != null && declared != partyId) {
            return refuse(Response.Status.FORBIDDEN, "grantorPartyId must be the authenticated party")
        }
        node.put(FIELD_GRANTOR, partyId)
        return upstream.post("$delegationServiceUrl$UPSTREAM", partyId, json.writeValueAsString(node))
    }

    /**
     * Accept an offered grant. `granteePartyId` is the token's party, so the caller can only ever
     * accept an offer addressed to themselves; `scaSessionId` is the grantee's OWN challenge
     * (purpose `DELEGATION_ACCEPT`) and is the one thing the client supplies.
     */
    @POST
    @Path("/{id}/accept")
    @Blocking
    fun accept(@PathParam("id") id: UUID, @QueryParam("scaSessionId") scaSessionId: UUID?): Response {
        val partyId = partyId()
        if (scaSessionId == null) return refuse(Response.Status.BAD_REQUEST, "scaSessionId is required")
        return upstream.post(
            "$delegationServiceUrl$UPSTREAM/$id/accept?granteePartyId=$partyId&scaSessionId=$scaSessionId",
            partyId,
            "",
        )
    }

    /** Decline an offered grant. No SCA: refusing access never needs a step-up. */
    @POST
    @Path("/{id}/decline")
    @Blocking
    fun decline(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.post("$delegationServiceUrl$UPSTREAM/$id/decline?granteePartyId=$partyId", partyId, "")
    }

    /** Give back an active grant the caller holds. Same reasoning as decline — no SCA to drop access. */
    @POST
    @Path("/{id}/renounce")
    @Blocking
    fun renounce(@PathParam("id") id: UUID): Response {
        val partyId = partyId()
        return upstream.post("$delegationServiceUrl$UPSTREAM/$id/renounce?granteePartyId=$partyId", partyId, "")
    }

    /**
     * Revoke a grant the caller issued — unilateral and instant, which is the property the UI
     * promises. `revokedBy` is NOT sent: upstream derives the actor from `X-Customer-Party-Id` for
     * customer-scoped calls, and the query parameter it still accepts is the bank-initiated path,
     * role-gated and narrowed by `delegation_rest_ext.rego`. Passing it from here would re-open the
     * hole #3164 closed, where the actor was caller-supplied.
     */
    @DELETE
    @Path("/{id}")
    @Blocking
    fun revoke(@PathParam("id") id: UUID, @QueryParam("reason") reason: String?): Response {
        val partyId = partyId()
        val body = json.writeValueAsString(mapOf("reason" to (reason?.takeIf { it.isNotBlank() } ?: DEFAULT_REASON)))
        return upstream.delete("$delegationServiceUrl$UPSTREAM/$id", partyId, body)
    }

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

    // One helper rather than a forbidden()/badRequest() pair: detekt's TooManyFunctions fires AT
    // the threshold (11), not above it, and a second one-line wrapper is the cheapest thing to give up.
    private fun refuse(status: Response.Status, message: String, code: String? = null): Response =
        Response.status(status)
            .entity(mapOf("error" to message) + (code?.let { mapOf("code" to it) } ?: emptyMap()))
            .build()

    private companion object {
        const val UPSTREAM = "/api/v1/delegations"
        const val FIELD_GRANTOR = "grantorPartyId"
        const val FIELD_GRANT_SCA_SESSION = "grantScaSessionId"
        const val DEFAULT_REASON = "Revoked by grantor"

        /** Constraints the schema still names but no service enforces. See [offer]. */

        /** Matches audit-service's own customer-facing page cap; a larger value is clamped there too. */
        const val MAX_ACTIVITY_PAGE = 500
    }
}
