// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.openbank.customeredge.infrastructure.ratelimit.RateLimiter
import com.openbank.customeredge.infrastructure.rest.EdgeJson.decimalString
import com.openbank.customeredge.infrastructure.rest.EdgeJson.instant
import com.openbank.customeredge.infrastructure.rest.EdgeJson.text
import com.openbank.libs.authz.Authorize
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Member-get-member for the customer app, proxying `openbank-referral-service` with the edge M2M
 * token. The referrer (invites) and the referee (attributions) are ALWAYS the token's party
 * ([CustomerPartyResolver]); a party id in a request body is never read.
 *
 * **Privacy.** A referrer learns that an invite was used and whether it paid out — never WHO used
 * it. Every response here is a whitelist projection, so neither `refereePartyId` nor the invite
 * token (except once, to its issuer, at creation) can reach the app even if upstream sends them.
 */
@Path("/customer/v1/referrals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class CustomerReferralResource(
    private val upstream: UpstreamClient,
    private val parties: CustomerPartyResolver,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    @ConfigProperty(name = "openbank.edge.referral-service-url")
    lateinit var referralServiceUrl: String

    @Context
    lateinit var requestHeaders: HttpHeaders

    /**
     * The programme a customer can invite under right now: PUBLISHED and still inside its
     * attribution window. Upstream lists newest publication first, so the first open one wins.
     * 404 when none is open. Maker/checker identities are not exposed.
     */
    @GET
    @Path("/program")
    @Authorize(action = "customer.referrals.read")
    @Blocking
    fun program(): Response {
        val party = party()
        val response = upstream.get("$referralServiceUrl$API/programs", party.toString())
        if (response.status != Response.Status.OK.statusCode) return EdgeJson.upstreamFailure(response, SERVICE)
        val programs = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        val now = Instant.now(clock)
        val open = programs.firstOrNull {
            it.text("status") == "PUBLISHED" && it.instant("attributionWindowEndsAt")?.isAfter(now) == true
        } ?: return EdgeJson.error(NOT_FOUND, "no referral programme is currently open")
        return EdgeJson.ok(
            mapOf(
                "id" to open.text("id"),
                "rewardAmount" to open.decimalString("rewardAmount"),
                "currency" to open.text("currency"),
                "qualifyingEvent" to open.text("qualifyingEvent"),
                "attributionWindowEndsAt" to open.text("attributionWindowEndsAt"),
            ),
        )
    }

    /**
     * Issue an invite as the caller. The key is forwarded NAMESPACED by party: referral-service
     * treats invite idempotency keys as globally unique, so two customers who happened to pick the
     * same key would otherwise collide and one would be told their key "has already been used".
     *
     * A reused key is a 409 `IDEMPOTENCY_KEY_REUSED`, not a replay of the original invite: upstream
     * stores the token only as a hash, so there is no token to hand back a second time. The app must
     * use a fresh key per invite and treat a retry after a lost response as "list my invites".
     */
    @POST
    @Path("/invites")
    @Authorize(action = "customer.referrals.invite")
    @Blocking
    fun issueInvite(body: String?, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val key = idempotencyKey(idempotencyKey) ?: return missingOrLongKey(idempotencyKey)
        val programId = EdgeJson.parseObject(body)?.text("programId")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return EdgeJson.error(BAD_REQUEST, "programId must be a UUID")
        val party = party()
        val response = upstream.post(
            "$referralServiceUrl$API/programs/$programId/invites",
            party.toString(),
            EdgeJson.mapper.writeValueAsString(mapOf("referrerPartyId" to party.toString())),
            "$party:$key",
        )
        return when (response.status) {
            Response.Status.CREATED.statusCode, Response.Status.OK.statusCode ->
                EdgeJson.parse(response)?.takeIf { it.isObject }?.let {
                    EdgeJson.ok(
                        mapOf(
                            "id" to it.text("id"),
                            "token" to it.text("token"),
                            "expiresAt" to it.text("expiresAt"),
                            "status" to it.text("status"),
                        ),
                        Response.Status.CREATED.statusCode,
                    )
                } ?: badUpstream()
            NOT_FOUND -> EdgeJson.error(NOT_FOUND, "referral programme not found")
            CONFLICT -> {
                val reason = if (upstreamReason(response) == "IDEMPOTENCY_KEY_REUSED") {
                    "IDEMPOTENCY_KEY_REUSED"
                } else {
                    "PROGRAM_UNAVAILABLE"
                }
                EdgeJson.error(CONFLICT, "invite could not be issued", mapOf("reason" to reason))
            }
            else -> EdgeJson.upstreamFailure(response, SERVICE)
        }
    }

    /**
     * The caller's own invites, newest first (upstream's order). Projected field by field: no
     * referee identity and no token. An ISSUED invite past `expiresAt` is reported EXPIRED here as
     * well as upstream, because a stored row is never rewritten on expiry and the edge must not be
     * the place that tells a customer a dead invite is still open.
     */
    @GET
    @Path("/invites")
    @Authorize(action = "customer.referrals.read")
    @Blocking
    fun invites(): Response {
        val party = party()
        val response = upstream.get("$referralServiceUrl$API/parties/$party/invites", party.toString())
        if (response.status != Response.Status.OK.statusCode) return EdgeJson.upstreamFailure(response, SERVICE)
        val invites = EdgeJson.parse(response)?.takeIf { it.isArray } ?: return badUpstream()
        val now = Instant.now(clock)
        return EdgeJson.ok(invites.map { projectInvite(it, now) })
    }

    /**
     * Redeem an invite as the REFEREE. The referee is the token party; a `refereePartyId` (or any
     * other field) in the body is ignored. Outcomes: 200 ATTRIBUTED (also a replay by the same
     * customer), 404 unknown token, 410 expired, 409 with `reason` SELF / ALREADY_ATTRIBUTED /
     * REJECTED.
     *
     * Token guessing is bounded by a per-party hourly quota charged BEFORE the upstream call, so a
     * miss costs quota exactly like a hit. The token is sent in the upstream PATH, so the call goes
     * through [UpstreamClient.postToService], which never logs the path.
     */
    @POST
    @Path("/attributions")
    @Authorize(action = "customer.referrals.attribute")
    @Blocking
    fun attribute(body: String?, @HeaderParam("Idempotency-Key") idempotencyKey: String?): Response {
        val key = idempotencyKey(idempotencyKey) ?: return missingOrLongKey(idempotencyKey)
        val token = EdgeJson.parseObject(body)?.text("token")?.trim()?.takeIf { TOKEN.matches(it) }
            ?: return EdgeJson.error(BAD_REQUEST, "token is missing or malformed")
        val party = party()
        if (!rateLimiter.isWithinWindow(ATTRIBUTION_SCOPE, party.toString(), ATTRIBUTIONS_PER_HOUR, HOUR, QUOTA_TTL)) {
            return Response.status(TOO_MANY_REQUESTS)
                .header("Retry-After", HOUR)
                .entity(EdgeJson.mapper.writeValueAsString(mapOf("error" to "too many invite redemption attempts")))
                .type(MediaType.APPLICATION_JSON)
                .build()
        }
        val response = upstream.postToService(
            referralServiceUrl,
            "$API/invites/$token/attribute",
            party.toString(),
            EdgeJson.mapper.writeValueAsString(mapOf("refereePartyId" to party.toString())),
            key,
        )
        return when (response.status) {
            Response.Status.OK.statusCode -> EdgeJson.ok(mapOf("status" to "ATTRIBUTED"))
            NOT_FOUND -> EdgeJson.error(NOT_FOUND, "invite not found")
            CONFLICT -> when (upstreamReason(response)) {
                "EXPIRED" -> EdgeJson.error(GONE, "invite has expired")
                "SELF" -> conflict("SELF")
                "ALREADY_ATTRIBUTED" -> conflict("ALREADY_ATTRIBUTED")
                else -> conflict("REJECTED")
            }
            else -> EdgeJson.upstreamFailure(response, SERVICE)
        }
    }

    private fun conflict(reason: String) =
        EdgeJson.error(CONFLICT, "invite could not be redeemed", mapOf("reason" to reason))

    private fun idempotencyKey(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_IDEMPOTENCY_KEY_LENGTH }

    private fun missingOrLongKey(raw: String?): Response = if (raw.isNullOrBlank()) {
        EdgeJson.error(BAD_REQUEST, "Idempotency-Key header is required")
    } else {
        EdgeJson.error(BAD_REQUEST, "Idempotency-Key is too long")
    }

    private fun party(): UUID = parties.resolve(
        if (this::requestHeaders.isInitialized) {
            requestHeaders.getHeaderString(CustomerEdgeResource.ACTING_FOR_HEADER)
        } else {
            null
        },
    )

    private fun badUpstream() = EdgeJson.error(Response.Status.BAD_GATEWAY.statusCode, "unexpected $SERVICE response")

    private companion object {
        const val API = "/api/v1/referrals"
        const val SERVICE = "referral service"
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
        const val TOO_MANY_REQUESTS = 429

        /** 36 chars of party UUID + ':' are prepended upstream; referral's column is 255. */
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 200

        const val ATTRIBUTION_SCOPE = "referral-attribution"
        const val ATTRIBUTIONS_PER_HOUR = 10
        const val HOUR = 3_600L
        val QUOTA_TTL: Duration = Duration.ofSeconds(HOUR + 60)

        /** Invite tokens are base64url. Rejecting everything else keeps `/` and `.` out of the upstream path. */
        val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}

/**
 * The referrer-side whitelist projection of one upstream invite. File-level rather than a member so
 * the resource stays one thin method per route.
 */
private fun projectInvite(invite: JsonNode, now: Instant): Map<String, Any?> {
    val stored = invite.text("status")
    val expiresAt = invite.instant("expiresAt")
    val expired = stored == "ISSUED" && expiresAt != null && !expiresAt.isAfter(now)
    val reward = invite.path("reward").takeIf { it.isObject }
    return mapOf(
        "id" to invite.text("id"),
        "status" to if (expired) "EXPIRED" else stored,
        "createdAt" to invite.text("createdAt"),
        "expiresAt" to invite.text("expiresAt"),
        "attributedAt" to invite.text("attributedAt"),
        "reward" to reward?.let {
            mapOf(
                "status" to it.text("status"),
                "amount" to it.decimalString("amount"),
                "currency" to it.text("currency"),
                "requestedAt" to it.text("requestedAt"),
                "rewardedAt" to it.text("rewardedAt"),
            )
        },
    )
}

private fun upstreamReason(response: Response): String? = EdgeJson.parse(response)?.text("reason")
