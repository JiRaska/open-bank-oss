// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.ratelimit

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fixed-window per-requester rate limiter for VoP, backed by Valkey — the same shape as
 * customer-edge's per-party limiter (ADR-0132).
 *
 * This is the control the threat model calls load-bearing rather than hygiene
 * (`docs/threat-models/openbank-vop-service.md` §4.1). VoP is, by construction, an oracle over
 * account-holder names: any authenticated caller may ask "does name X hold IBAN Y?" and get a
 * truthful answer, because that is exactly what IPR Art. 5c mandates. Authentication does not
 * bound that — a payer legitimately checks payees they do not own — so the *rate* is what
 * separates paying from enumerating.
 *
 * **Valkey-backed, not in-process, deliberately.** vop-service runs multiple replicas; a local
 * counter would give an attacker `limit × replicas` and would reset on every pod roll. The
 * shared window is what makes the limit mean anything.
 *
 * **Reactive, not blocking — a measured fix, not a style choice.** The blocking
 * `RedisDataSource` answered 429 to EVERY request in this service's real boots: request filters
 * run on the Vert.x event loop, `@Blocking` on a `ContainerRequestFilter` is silently ignored,
 * and the blocking `INCR` threw `IllegalStateException: The current thread cannot be blocked` on
 * the very first call. The filter's fail-closed catch then turned that into a permanent
 * `store_unavailable` rejection — the enumeration control was never actually checking anyone.
 * The E2E suite (`VopVerificationE2E`) pinned it: every verify answered 429. The reactive
 * datasource keeps the whole filter chain on the event loop.
 *
 * Note the shared-state cost this accepts: the platform audit flags Valkey as a money-path single
 * point (idempotency store). See [VopRateLimitFilter] for why an outage is nonetheless safe here.
 */
@ApplicationScoped
class VopRateLimiter(redis: ReactiveRedisDataSource, private val clock: Clock) {

    private val values: ReactiveValueCommands<String, String> = redis.value(String::class.java)
    private val keys: ReactiveKeyCommands<String> = redis.key(String::class.java)

    /**
     * Returns true if [requesterId] is under the limit for the current minute window. Increments
     * the counter — each call consumes one slot.
     *
     * A Valkey failure surfaces as a FAILED Uni, not a swallowed default: the caller decides the
     * failure semantics (fail closed) rather than this class silently choosing one.
     */
    fun isAllowed(requesterId: String, limitPerMinute: Int): Uni<Boolean> {
        val key = bucketKey(requesterId)
        return values.incr(key).flatMap { count ->
            val underLimit = count <= limitPerMinute
            if (count == 1L) {
                // First touch of a fresh window arms its TTL; the answer rides along either way.
                keys.expire(key, WINDOW_TTL).map { underLimit }
            } else {
                Uni.createFrom().item(underLimit)
            }
        }
    }

    private fun bucketKey(requesterId: String): String {
        val bucket = Instant.now(clock).epochSecond / WINDOW_SECONDS
        return "vop:rate-limit:$requesterId:$bucket"
    }

    companion object {
        private const val WINDOW_SECONDS = 60L

        /** 60s window + 10s grace for clock skew across replicas. */
        private val WINDOW_TTL: Duration = Duration.ofSeconds(70)
    }
}
