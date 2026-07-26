// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.approval

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [ApprovalStore] (ADR-0155, wired for the ADR-0179 `party.merge`
 * four-eyes gate). Mirrors `com.openbank.notification.infrastructure.approval.ApprovalConfig`
 * — see [RedisApprovalStore]'s KDoc for why this is a per-service `@Produces` rather than a
 * libs-side bean (a `@Produces` in openbank-libs-runtime would drag a Redis type into the
 * Arc type closure of every service in the fleet, including the ~30 with no Redis extension).
 *
 * Producing this bean makes `AuthorizeInterceptor.approvalStore.isResolvable` true, which
 * matters beyond this service: `isResolvable` checks CDI bean presence, not whether
 * [ReactiveRedisDataSource] can actually reach a Redis instance. `gitops/components/party/
 * redis.yaml` and the `QUARKUS_REDIS_HOSTS` override on the Deployment are what make that
 * true here — without them this bean would exist but every call would fail on a Redis
 * connection error rather than fail open with the interceptor's documented log line
 * (issue #1354, found live in lending-service's identical wiring with no Redis behind it).
 */
@ApplicationScoped
class ApprovalConfig {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)
}
