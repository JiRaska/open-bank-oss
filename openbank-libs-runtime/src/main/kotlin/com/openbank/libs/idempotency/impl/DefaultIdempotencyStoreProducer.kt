// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.arc.DefaultBean
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Shared [@DefaultBean][DefaultBean] producer for [IdempotencyStore], wrapping
 * [RedisIdempotencyStore]. Replaces the identical per-service `IdempotencyConfig`
 * that 8 services carried verbatim (account, aml, consent, lending, psd2, sca,
 * sepa-payment, tpp-registry).
 *
 * `@DefaultBean` means this producer only supplies [IdempotencyStore] when no
 * other bean of that type exists — a service can still declare its own
 * `@Produces IdempotencyStore` (as delegation-service and document-service do,
 * carrying extra KDoc/context this class doesn't need) to override it.
 *
 * The `redis` parameter is deliberately `Instance<ReactiveRedisDataSource>`, not
 * a direct `ReactiveRedisDataSource`. Measured 2026-09-26: a direct parameter
 * makes ArC validate that injection point for every service that merely has
 * this class on the classpath (via openbank-libs-runtime) — regardless of
 * whether the service ever injects [IdempotencyStore] — and
 * `:openbank-audit-service:quarkusBuild` (no `quarkus-redis-client` dependency,
 * no [IdempotencyStore] usage anywhere) failed build with
 * `UnsatisfiedResolutionException: Unsatisfied dependency for type
 * io.quarkus.redis.datasource.ReactiveRedisDataSource`. `Instance<T>` is itself
 * always resolvable, deferring the actual lookup to [get], so it does not
 * impose that requirement — this is exactly the failure mode
 * [RedisIdempotencyStore]'s KDoc already warned a libs-side `@Default` bean
 * would hit, and why the per-service copies existed in the first place.
 *
 * A service that DOES inject [IdempotencyStore] but has not configured Redis
 * (`quarkus.redis.hosts` / `quarkus-redis-client`) now fails at PRODUCER-CALL
 * time with a clear [IllegalStateException] instead of a build-time CDI error —
 * still a hard failure, just one that surfaces at boot instead of compile, which
 * is an acceptable trade for not breaking every Redis-less service's build.
 *
 * Verified 2026-09-26 with this `Instance<>` form:
 * `:openbank-audit-service:quarkusBuild` and `:openbank-kyc-service:quarkusBuild`
 * (no `quarkus-redis-client` dependency, no [IdempotencyStore] injection
 * anywhere) both build clean; `:openbank-account-service:quarkusBuild` (Redis
 * configured, [IdempotencyStore] injected, its own `IdempotencyConfig.kt`
 * removed) also builds clean and boots with this producer supplying the store.
 */
@ApplicationScoped
class DefaultIdempotencyStoreProducer {
    @Produces
    @DefaultBean
    @ApplicationScoped
    fun idempotencyStore(redis: Instance<ReactiveRedisDataSource>, clock: Clock): IdempotencyStore {
        check(redis.isResolvable) {
            "IdempotencyStore requires a Redis client - configure quarkus.redis.hosts " +
                "and add the quarkus-redis-client dependency, or supply your own " +
                "@Produces IdempotencyStore to override this default bean."
        }
        return RedisIdempotencyStore(redis.get(), clock)
    }
}
