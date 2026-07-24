// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.feedback.FeedbackPublisher
import com.openbank.customeredge.infrastructure.feedback.FeedbackScreenshotStore
import com.openbank.customeredge.infrastructure.feedback.FeedbackSubmission
import com.openbank.customeredge.infrastructure.ratelimit.RateLimiter
import com.openbank.libs.authz.Authorize
import com.openbank.libs.domain.identifiers.Ids
import io.smallrye.common.annotation.Blocking
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * In-app screen feedback (ADR-0192) — `POST /customer/v1/feedback`.
 *
 * The app's global feedback rail sends a screen id, a category, a free-text comment and
 * (optionally) a screenshot the user explicitly previewed and confirmed. The edge stores the
 * image in object storage, then publishes METADATA + the object key to `openbank.feedback.events`,
 * which analytics-sink lands in the ClickHouse bronze layer next to the onboarding funnel. No new
 * deployable service, and the image never enters the event stream.
 *
 * A separate resource class from [CustomerEdgeResource] for the same reason
 * [CustomerDocumentResource] is: that class is already `@Suppress("LargeClass")`. Party resolution
 * reuses its `resolvePartyIdClaim` companion helper.
 *
 * Security / privacy posture:
 *  - `partyId` comes from the bearer token, never the body — a client cannot file feedback as
 *    someone else, and cannot choose whose data an erasure request will later find.
 *  - The screenshot is validated by BYTES (PNG signature), not by the field name, so
 *    `screenshotPngBase64` is not an arbitrary-upload channel into the bucket.
 *  - Every free-text/enum field is length- or allow-list-bounded before anything is emitted into
 *    a 10-year warehouse.
 *  - A dedicated per-party hourly quota (ADR-0192: "rate limiting at the edge is part of the
 *    endpoint contract from day one") sits on top of the global per-minute [RateLimiter] budget —
 *    100 req/min is no defence at all against someone parking 2 MB screenshots in the bucket.
 */
@Path("/customer/v1/feedback")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ROLE_CUSTOMER")
class FeedbackResource(
    private val screenshotStore: FeedbackScreenshotStore,
    private val publisher: FeedbackPublisher,
    private val rateLimiter: RateLimiter,
) {

    @Inject
    lateinit var jwt: JsonWebToken

    @Inject
    lateinit var objectMapper: ObjectMapper

    @ConfigProperty(name = "openbank.feedback.submissions-per-hour", defaultValue = DEFAULT_HOURLY_QUOTA_STR)
    var submissionsPerHour: Int = DEFAULT_HOURLY_QUOTA

    /**
     * Accept one feedback submission. Answers 202 with a support-quotable `reference`.
     *
     * 202 rather than 201: nothing is queryable at a URL afterwards — the submission is handed to
     * the analytics pipeline, and the reference exists so a customer can quote it to support.
     */
    @POST
    @Authorize(action = "customer.feedback.submit", resource = "")
    @Blocking
    fun submit(body: String): Response {
        val partyId = partyId()
        val request = when (val parsed = parse(body)) {
            is Parsed.Rejected -> return parsed.response
            is Parsed.Ok -> parsed.request
        }
        // Quota is charged AFTER validation so a malformed request cannot burn a legitimate user's
        // hourly budget, and BEFORE the object-store write so a flood cannot fill the bucket.
        if (!rateLimiter.isWithinWindow(QUOTA_SCOPE, partyId, submissionsPerHour, QUOTA_WINDOW_SECONDS, QUOTA_TTL)) {
            return quotaExceeded()
        }

        // Ids.newId() (UUIDv7, ADR-0106) — this id is durable: it names the S3 object and, through
        // the reference, the warehouse row an erasure request has to find.
        val feedbackId = Ids.newId()
        val reference = reference(feedbackId)
        val stored = request.screenshot
            ?.let { screenshotStore.store(feedbackId, reference, it) }
            ?: FeedbackScreenshotStore.Result(null, FeedbackScreenshotStore.STATUS_NONE)

        publisher.emit(
            FeedbackSubmission(
                reference = reference,
                partyId = partyId,
                screenId = request.screenId,
                category = request.category,
                comment = request.comment,
                platform = request.platform,
                appVersion = request.appVersion,
                screenshotKey = stored.key,
                screenshotBytes = request.screenshot?.size ?: 0,
                screenshotStatus = stored.status,
            ),
        )
        return Response.status(STATUS_ACCEPTED)
            .entity(mapOf("reference" to reference))
            .type(MediaType.APPLICATION_JSON)
            .build()
    }

    private class ValidRequest(
        val screenId: String,
        val category: String,
        val comment: String,
        val platform: String?,
        val appVersion: String?,
        val screenshot: ByteArray?,
    )

    private sealed interface Parsed {
        class Ok(val request: ValidRequest) : Parsed
        class Rejected(val response: Response) : Parsed
    }

    /**
     * Parse and vet the whole request. Every field is bounded here — the app applies the same caps
     * client-side, but a client-side cap is a UX affordance, never a server-side guarantee.
     */
    private fun parse(body: String): Parsed {
        // Size gate BEFORE parsing: a 2 MB PNG is ~2.7 MB of base64, and Jackson should never be
        // handed an unbounded body just to find out it was too big.
        if (body.length > MAX_BODY_CHARS) return reject(STATUS_PAYLOAD_TOO_LARGE, "Request body too large")
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return reject(STATUS_BAD_REQUEST, "Invalid JSON")

        val screenId = node.text("screenId")?.take(MAX_SCREEN_ID_LEN)
            ?: return reject(STATUS_BAD_REQUEST, "screenId is required")
        if (!SCREEN_ID_PATTERN.matches(screenId)) {
            return reject(STATUS_BAD_REQUEST, "screenId must match $SCREEN_ID_PATTERN")
        }
        val category = node.text("category")?.uppercase()
        if (category == null || category !in FeedbackPublisher.VALID_CATEGORIES) {
            return reject(STATUS_BAD_REQUEST, "category must be one of: BUG, IDEA, CONFUSING")
        }
        val comment = node.text("comment")?.take(MAX_COMMENT_LEN).orEmpty()

        val screenshot = when (val decoded = decodeScreenshot(node)) {
            is ScreenshotResult.Invalid -> return reject(decoded.status, decoded.message)
            is ScreenshotResult.Ok -> decoded.png
        }
        // An empty submission carries no signal but still costs a warehouse row.
        if (comment.isBlank() && screenshot == null) {
            return reject(STATUS_BAD_REQUEST, "comment or screenshotPngBase64 is required")
        }
        return Parsed.Ok(
            ValidRequest(
                screenId = screenId,
                category = category,
                comment = comment,
                platform = node.text("platform")?.take(MAX_SHORT_FIELD_LEN),
                appVersion = node.text("appVersion")?.take(MAX_SHORT_FIELD_LEN),
                screenshot = screenshot,
            ),
        )
    }

    private fun quotaExceeded(): Response = Response.status(STATUS_TOO_MANY_REQUESTS)
        .header("X-RateLimit-Limit", submissionsPerHour)
        .header("X-RateLimit-Window", "3600s")
        .header("Retry-After", QUOTA_WINDOW_SECONDS)
        .entity(
            mapOf(
                "code" to "FEEDBACK_RATE_LIMIT_EXCEEDED",
                "message" to "Too many feedback submissions. Limit: $submissionsPerHour/hour.",
            ),
        )
        .build()

    // Not data classes on purpose: one carries a ByteArray, where generated equals/hashCode would
    // compare identities and quietly mislead any future caller.
    private sealed interface ScreenshotResult {
        class Ok(val png: ByteArray?) : ScreenshotResult
        class Invalid(val status: Int, val message: String) : ScreenshotResult
    }

    /**
     * Decode and vet the optional screenshot. Rejects anything that is not strict base64, is
     * larger than the decoded cap, or is not actually a PNG — the field name is a client claim,
     * the magic bytes are the check.
     */
    private fun decodeScreenshot(node: JsonNode): ScreenshotResult {
        val base64 = node.text("screenshotPngBase64") ?: return ScreenshotResult.Ok(null)
        val png = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
            ?: return ScreenshotResult.Invalid(STATUS_BAD_REQUEST, "screenshotPngBase64 is not valid base64")
        if (png.size > MAX_SCREENSHOT_BYTES) {
            return ScreenshotResult.Invalid(STATUS_PAYLOAD_TOO_LARGE, "screenshot exceeds 2 MB")
        }
        if (!FeedbackScreenshotStore.isPng(png)) {
            return ScreenshotResult.Invalid(STATUS_BAD_REQUEST, "screenshot is not a PNG")
        }
        return ScreenshotResult.Ok(png)
    }

    /** Party identity from the token — same claim precedence as every other customer route. */
    private fun partyId(): String {
        val raw = CustomerEdgeResource.resolvePartyIdClaim(
            partyIdClaim = jwt.getClaim<String>("party_id"),
            sub = jwt.subject,
        ) ?: throw ForbiddenException("Missing party_id/sub claim in customer token")
        // Normalising through UUID also rejects a claim that is not a party id at all, so a
        // malformed token can never widen the Redis quota key space or the warehouse's party column.
        return runCatching { UUID.fromString(raw).toString() }
            .getOrElse { throw ForbiddenException("party_id claim is not a valid party UUID: $raw") }
    }

    private fun reject(code: Int, message: String): Parsed = Parsed.Rejected(
        Response.status(code)
            .entity(mapOf("error" to message))
            .type(MediaType.APPLICATION_JSON)
            .build(),
    )

    companion object {
        // A screenshot is capped at 2 MB decoded (ADR-0192); base64 inflates by 4/3, plus slack
        // for the JSON envelope and the free-text comment.
        private const val MAX_SCREENSHOT_BYTES = 2 * 1024 * 1024
        private const val MAX_BODY_CHARS = 3 * 1024 * 1024

        // Mirrors the caps the app already enforces client-side (FeedbackApi.kt) — restated here
        // because a client-side cap is a UX affordance, never a server-side guarantee.
        private const val MAX_SCREEN_ID_LEN = 64
        private const val MAX_COMMENT_LEN = 2000
        private const val MAX_SHORT_FIELD_LEN = 32

        // A screen id is a nav route ("home", "payments/new"), not free text: keeping it to a
        // route charset stops it becoming a second comment field with unbounded cardinality in
        // the warehouse's GROUP BY.
        private val SCREEN_ID_PATTERN = Regex("^[A-Za-z0-9._/-]{1,64}$")

        // Per-party feedback quota. Generous for any human reporting real problems, but it bounds
        // how much a single token can push into object storage.
        private const val DEFAULT_HOURLY_QUOTA = 10
        const val DEFAULT_HOURLY_QUOTA_STR = "10"
        private const val QUOTA_SCOPE = "feedback-quota"
        private const val QUOTA_WINDOW_SECONDS = 3600L

        // Window + 5 min grace for clock skew, same shape as the per-minute limiter's 60s + 10s.
        private val QUOTA_TTL: Duration = Duration.ofSeconds(QUOTA_WINDOW_SECONDS + 300)

        private const val STATUS_ACCEPTED = 202
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_PAYLOAD_TOO_LARGE = 413
        private const val STATUS_TOO_MANY_REQUESTS = 429

        // Short, unambiguous (no vowels -> no accidental words), and quotable over the phone.
        private const val REFERENCE_HEX_LEN = 12

        // Taken from the TAIL of the id, not the head: Ids.newId() is UUIDv7, whose leading 48 bits
        // are a millisecond timestamp — a head-derived reference would collide for two submissions
        // in the same millisecond and leak nothing but the clock. The tail is the random part.
        private fun reference(feedbackId: UUID): String =
            "FB-" + feedbackId.toString().replace("-", "").takeLast(REFERENCE_HEX_LEN).uppercase()

        /** Trimmed, non-blank text node value, or null. */
        private fun JsonNode.text(field: String): String? =
            get(field)?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
