// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.ratelimit

import com.openbank.vop.application.port.out.VopMetricsPort
import com.openbank.vop.application.port.out.VopRateLimitOutcome
import io.quarkus.logging.Log
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
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
 * Runs at [Priorities.AUTHORIZATION] + 10 — after authentication, so there is a principal to key
 * on, and after the OPA `@Authorize` decision, so an unauthorized caller cannot consume another
 * caller's window.
 *
 * **Non-blocking, end to end (#13049).** This is a RESTEasy Reactive `@ServerRequestFilter`
 * returning `Uni<Response?>`, and [VopRateLimiter] uses the reactive Valkey client. It used to be a
 * `@Blocking` `ContainerRequestFilter` over the blocking client: the annotation did not move the
 * filter off the event loop, every `INCR` threw `The current thread cannot be blocked`, and the
 * fail-closed path answered 429 to every verify while Valkey was healthy. `VopRateLimitHttpIT` is
 * the test that can see that; the unit test, which mocks the limiter, cannot.
 */
@ApplicationScoped
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

    @ServerRequestFilter(priority = VOP_RATE_LIMIT_PRIORITY)
    fun filter(ctx: ContainerRequestContext): Uni<Response?> {
        if (!enabled) return PASS
        // Management/health endpoints carry no principal and are not an oracle.
        if (ctx.uriInfo.path.startsWith("/q/")) return PASS

        // .principal.name (preferred_username) — the same identity format the evidence row and the
        // OPA decision use, so a per-requester anomaly review can join them.
        val requesterId = identity.principal?.name?.takeIf { it.isNotBlank() }
            // No principal: the endpoint requires a role, so this is a request that will be
            // rejected anyway. Let the security layer produce the 401/403 rather than masking it
            // as a 429.
            ?: return PASS

        return failClosedOnStoreError(requesterId).map { outcome ->
            metrics.rateLimitDecision(outcome)
            if (outcome == VopRateLimitOutcome.ALLOWED) null else tooManyRequests()
        }
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

    /**
     * Deliberately broad: the Valkey client can surface a connection, timeout, serialization or
     * pool error, and every one of them means the same thing — we cannot prove this caller is
     * under the limit. Narrowing the recovery would let an unanticipated client failure escape as a
     * 500 and, worse, skip the limit entirely on the retry path. A limiter that throws while
     * assembling the Uni is folded into the same recovery by `deferred`.
     *
     * Returns the outcome rather than a boolean so the metric can tell the two rejections apart:
     * `throttled` is one caller hitting the limit, `store_unavailable` is *every* caller being
     * rejected because Valkey is down. Both produce a 429 and are indistinguishable on the wire.
     */
    private fun failClosedOnStoreError(requesterId: String): Uni<VopRateLimitOutcome> =
        Uni.createFrom().deferred { rateLimiter.isAllowed(requesterId, limitPerMinute) }
            .map { allowed -> if (allowed) VopRateLimitOutcome.ALLOWED else VopRateLimitOutcome.THROTTLED }
            .onFailure().recoverWithItem { e ->
                // Fail closed — see the class doc. A 429 degrades to `no_data` at the caller; it
                // does not block the payment.
                Log.errorf(e, "VoP rate-limit store unavailable; rejecting requester=%s (fail-closed)", requesterId)
                VopRateLimitOutcome.STORE_UNAVAILABLE
            }

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
        private val PASS: Uni<Response?> = Uni.createFrom().nullItem()
    }
}
