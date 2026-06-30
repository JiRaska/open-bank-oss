// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.feature

import com.openbank.libs.domain.feature.OnlineFeatureStore
import com.openbank.libs.feature.online.RedisOnlineFeatureStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces

/**
 * Per-service producer for [OnlineFeatureStore], wrapping the shared [RedisOnlineFeatureStore] from
 * openbank-libs (ADR-0140). Same pattern as `IdempotencyConfig`: the libs impl needs a
 * [ReactiveRedisDataSource], which only services that configure `quarkus.redis.hosts` have, so the
 * wiring is explicit per service rather than a libs-side `@Default` bean.
 */
@ApplicationScoped
class FeatureStoreConfig {
    @Produces
    @ApplicationScoped
    fun onlineFeatureStore(redis: ReactiveRedisDataSource): OnlineFeatureStore = RedisOnlineFeatureStore(redis)
}
