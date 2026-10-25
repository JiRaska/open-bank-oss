// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.ratelimit

import com.openbank.vop.application.port.out.VopMetricsPort
import com.openbank.vop.application.port.out.VopRateLimitOutcome
import io.quarkus.logging.Log
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.resteasy.reactive.server.ServerRequestFilter

/**
 * Runs just after [Priorities.AUTHORIZATION]: late enough that authentication has produced a
 * principal to key the window on, and that the OPA `@Authorize` decision has already rejected
 * unauthorized callers — so one caller cannot burn another's window by being denied.
 */
private const val VOP_RATE_LIMIT_PRIORITY = Priorities.AUTHORIZATION + 10

/**
 * Per-requester rate limit on the VoP endpoint (ADR-0171; threat model §4.1).
 *
 * **Why this is a security control, not throughput management.** VoP truthfully answers "does
 * this name hold this IBAN?" to any authenticated caller — that is the regulation's purpose, so
 * authorization cannot bound it. Rate is the only thing separating a payer checking a payee from
 * an attacker enumerating who banks here. The response asymmetry (never echo a name on NO_MATCH)
 * limits what each wrong guess reveals; this limits how many guesses are possible.
 *
 * It also caps the amplification: every verify fans out to two money-path services
 * (account-service, party-service), so an unthrottled VoP is an amplification vector into them.
 *
 * **Fails CLOSED — and that is consistent with VoP failing open.** If Valkey is unreachable we
 * cannot prove a caller is under the limit, so we reject with 429 rather than wave them through.
 * That sounds like it contradicts ADR-0171 §3, but it does not: a 429 does not fail the payment.
 * The caller renders `no_data`, the payer gets a warning, and the payment still flows — VoP's
 * fail-open behaviour reached by a different route. The alternative (fail open on the limiter)
 * would mean a Valkey outage silently removes the only enumeration control, which is a real
 * security hole traded for no payment-availability gain.
 *
 * **Reactive, not `@Blocking`.** A `ContainerRequestFilter` runs on the Vert.x event loop and
 * `@Blocking` on it is silently ignored — measured: the blocking Valkey call threw
 * `IllegalStateException` on every request and the fail-closed catch below answered 429 to the
 * entire service, permanently (see [VopRateLimiter]). `@ServerRequestFilter` returning
 * `Uni<Response?>` is the non-blocking form, so the limiter round-trip stays on the event loop.
 */
class VopRateLimitFilter {

    @Inject
    lateinit var rateLimiter: VopRateLimiter

    @Inject
    lateinit var identity: SecurityIdentity

    @Inject
    lateinit var metrics: VopMetricsPort

    @ConfigProperty(name = "openbank.vop.rate-limit.requests-per-minute", defaultValue = DEFAULT_LIMIT_STR)
    var limitPerMinute: Int = DEFAULT_LIMIT

    @ConfigProperty(name = "openbank.vop.rate-limit.enabled", defaultValue = "true")
    var enabled: Boolean = true

    /** Emits null to continue the chain, or a 429 [Response] to abort — never blocks a thread. */
    @ServerRequestFilter(priority = VOP_RATE_LIMIT_PRIORITY)
    fun checkLimit(ctx: ContainerRequestContext): Uni<Response?> {
        if (!enabled) return Uni.createFrom().nullItem()
        // Management/health endpoints carry no principal and are not an oracle.
        if (ctx.uriInfo.path.startsWith("/q/")) return Uni.createFrom().nullItem()

        // .principal.name (preferred_username) — the same identity format the evidence row and the
        // OPA decision use, so a per-requester anomaly review can join them.
        val requesterId = identity.principal?.name?.takeIf { it.isNotBlank() }
        if (requesterId == null) {
            // No principal: the endpoint requires a role, so this is a request that will be
            // rejected anyway. Let the security layer produce the 401/403 rather than masking it
            // as a 429.
            return Uni.createFrom().nullItem()
        }

        return failClosedOnStoreError(requesterId)
            .invoke { outcome -> metrics.rateLimitDecision(outcome) }
            .map { outcome -> if (outcome == VopRateLimitOutcome.ALLOWED) null else tooManyRequests() }
    }

    /**
     * Deliberately broad: the Valkey client can surface a connection, timeout, serialization or
     * pool error, and every one of them means the same thing — we cannot prove this caller is
     * under the limit. Narrowing the catch would let an unanticipated client exception escape as a
     * 500 and, worse, skip the limit entirely on the retry path.
     *
     * Returns the outcome rather than a boolean so the metric can tell the two rejections apart:
     * `throttled` is one caller hitting the limit, `store_unavailable` is *every* caller being
     * rejected because Valkey is down. Both produce a 429 and are indistinguishable on the wire.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun failClosedOnStoreError(requesterId: String): Uni<VopRateLimitOutcome> =
        rateLimiter.isAllowed(requesterId, limitPerMinute)
            .map { allowed -> if (allowed) VopRateLimitOutcome.ALLOWED else VopRateLimitOutcome.THROTTLED }
            .onFailure().recoverWithItem { failure ->
                // Fail closed — see the class doc. A 429 degrades to `no_data` at the caller; it
                // does not block the payment. The recovery comes AFTER the boolean mapping so a
                // store failure is reported as STORE_UNAVAILABLE, not collapsed into THROTTLED.
                Log.errorf(
                    failure,
                    "VoP rate-limit store unavailable; rejecting requester=%s (fail-closed)",
                    requesterId,
                )
                VopRateLimitOutcome.STORE_UNAVAILABLE
            }

    private fun tooManyRequests(): Response = Response.status(HTTP_TOO_MANY_REQUESTS)
        .header("X-RateLimit-Limit", limitPerMinute)
        .header("X-RateLimit-Window", "60s")
        .header("Retry-After", RETRY_AFTER_SECONDS)
        .entity(
            mapOf(
                "code" to "RATE_LIMIT_EXCEEDED",
                "message" to "Too many verification requests. Limit: $limitPerMinute/min.",
            ),
        )
        .build()

    companion object {
        /**
         * A payer checking payees does single-digit verifies per minute; an operator working a
         * payment queue might do a few dozen. 60/min leaves generous headroom for real work while
         * making enumeration of any meaningful IBAN space impractical. A judgement call with no
         * production data behind it — tune from the outcome metrics, not from taste.
         */
        private const val DEFAULT_LIMIT = 60
        const val DEFAULT_LIMIT_STR = "60"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val RETRY_AFTER_SECONDS = 60
    }
}
