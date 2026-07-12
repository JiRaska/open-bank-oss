// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.infrastructure.approval

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [ApprovalStore] (ADR-0155). Mirrors the sepa-payment pilot's
 * `ApprovalConfig` — see [RedisApprovalStore]'s KDoc for why this is a per-service `@Produces`
 * rather than a libs-side bean. balance-service has no pre-existing `IdempotencyConfig` peer
 * (its money-movement idempotency is DB-based — see the `balance_movement` dedup ledger, V8
 * migration) — this is the FIRST Redis wiring in this service, added solely for the
 * [ReactiveRedisDataSource] this producer consumes.
 */
@ApplicationScoped
class ApprovalConfig {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)
}
