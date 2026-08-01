// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.approval

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/** Per-service producer for [ApprovalStore] (ADR-0155), mirrors consent-service's. */
@ApplicationScoped
class ApprovalConfig {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)
}
