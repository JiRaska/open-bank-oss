// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.idempotency

import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.impl.RedisIdempotencyStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [IdempotencyStore] (mirrors `openbank-account-service`'s
 * `IdempotencyConfig` — see [RedisIdempotencyStore]'s KDoc for why this can't be a libs-side bean).
 *
 * `quarkus.redis.hosts` was already configured for this service before this PR (see
 * `application.yaml`) but nothing consumed it yet — [AnnualStatementDeliveryUseCase]'s
 * (accountId, year) replay guard is the first user.
 */
@ApplicationScoped
class IdempotencyConfig {
    @Produces
    @ApplicationScoped
    fun idempotencyStore(redis: ReactiveRedisDataSource, clock: Clock): IdempotencyStore =
        RedisIdempotencyStore(redis, clock)
}
