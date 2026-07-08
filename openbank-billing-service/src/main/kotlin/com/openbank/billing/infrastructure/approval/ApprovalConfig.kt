// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.approval

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [ApprovalStore] (ADR-0155). Mirrors
 * `openbank-sepa-payment`'s `ApprovalConfig` — see [RedisApprovalStore]'s KDoc for why this is a
 * per-service `@Produces` rather than a libs-side bean. Wires the four-eyes gate for
 * `billing.post` (ADR-0143 step 4): `AuthorizeInterceptor` pauses a maker's `POST /fees/post`
 * call with a `PendingApproval` when OPA flags it `four_eyes_required`, and a different operator
 * decides it via [ApprovalResource].
 */
@ApplicationScoped
class ApprovalConfig {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)
}
