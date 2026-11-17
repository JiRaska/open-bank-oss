// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.ratelimit

import io.quarkus.redis.datasource.ReactiveRedisDataSource
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
 * Note the shared-state cost this accepts: the platform audit flags Valkey as a money-path single
 * point (idempotency store). See [VopRateLimitFilter] for why an outage is nonetheless safe here.
 *
 * **Reactive, never blocking.** The limit is checked from a RESTEasy Reactive request filter, which
 * runs on the Vert.x event loop. The blocking `RedisDataSource` throws `IllegalStateException: The
 * current thread cannot be blocked` there — and because the filter fails closed, that turned every
 * verify into a 429 on sandbox (#13049) while Valkey was perfectly healthy. A `@Blocking`
 * annotation on a `ContainerRequestFilter` does not move it off the event loop.
 */
@ApplicationScoped
class VopRateLimiter(redis: ReactiveRedisDataSource, private val clock: Clock) {

    private val values = redis.value(String::class.java)
    private val keys = redis.key(String::class.java)

    /**
     * Emits true if [requesterId] is under the limit for the current minute window. Increments
     * the counter — each subscription consumes one slot.
     *
     * Fails with whatever the Valkey client fails with when the store is unreachable; the caller
     * decides the failure semantics rather than this class silently choosing one.
     */
    fun isAllowed(requesterId: String, limitPerMinute: Int): Uni<Boolean> {
        val bucket = Instant.now(clock).epochSecond / WINDOW_SECONDS
        val key = "vop:rate-limit:$requesterId:$bucket"
        return values.incr(key)
            .call { count -> if (count == 1L) keys.expire(key, WINDOW_TTL) else Uni.createFrom().voidItem() }
            .map { count -> count <= limitPerMinute }
    }

    companion object {
        private const val WINDOW_SECONDS = 60L

        /** 60s window + 10s grace for clock skew across replicas. */
        private val WINDOW_TTL: Duration = Duration.ofSeconds(70)
    }
}
