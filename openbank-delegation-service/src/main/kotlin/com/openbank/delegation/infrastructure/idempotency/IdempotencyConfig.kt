// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.idempotency

import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.impl.RedisIdempotencyStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

@ApplicationScoped
class IdempotencyConfig {
    @Produces
    @ApplicationScoped
    fun idempotencyStore(redis: ReactiveRedisDataSource, clock: Clock): IdempotencyStore =
        RedisIdempotencyStore(redis, clock)
}
