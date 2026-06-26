// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.domestic.infrastructure.idempotency

import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.impl.RedisIdempotencyStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [IdempotencyStore]. Wraps the shared
 * [RedisIdempotencyStore] implementation from openbank-libs.
 *
 * Why per-service `@Produces` instead of a libs-side `@ApplicationScoped @Default`:
 * the libs class needs a [ReactiveRedisDataSource], which only services that
 * configure `quarkus.redis.hosts` have. A libs-side bean would force every
 * service that depends on openbank-libs to also pull Redis, even pure-DB
 * services that never need it. See KDoc on [RedisIdempotencyStore].
 */
@ApplicationScoped
class IdempotencyConfig {
    @Produces
    @ApplicationScoped
    fun idempotencyStore(redis: ReactiveRedisDataSource, clock: Clock): IdempotencyStore = RedisIdempotencyStore(redis, clock)
}
