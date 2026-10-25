// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import com.openbank.libs.approval.ApprovalStore
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Fleet producer of the Redis-backed [ApprovalStore] (ADR-0155 four-eyes), replacing the 18
 * byte-identical per-service `ApprovalConfig` classes.
 *
 * Gated at BUILD time by `openbank.approval.store=redis`, OFF when the property is missing, and
 * the gate sits on the CLASS so that, when it is off, ArC sees no bean at all — not even this
 * holder — and therefore never asks for a [ReactiveRedisDataSource]. Two reasons it must be off
 * by default:
 * - `AuthorizeInterceptor` keys four-eyes off [ApprovalStore] bean PRESENCE, so an
 *   unconditional bean would switch four-eyes on in every service that has the libs JAR.
 * - Most services run no Redis; an unconditional `ReactiveRedisDataSource` injection point
 *   would fail their ArC augmentation.
 *
 * Deliberately NOT `@DefaultBean`, for the same reason as
 * [com.openbank.libs.authz.OpaPolicyDecisionPointProducer]: a service that opts in AND keeps its
 * own `ApprovalStore` producer (settlement's and sca's Postgres stores, a stale copy of the old
 * `ApprovalConfig`) must fail `quarkusBuild` as an ambiguous dependency, not have one store
 * silently displace the other.
 *
 * Services that expose an `ApprovalResource` inject [ApprovalStore] by constructor, so dropping
 * the property from such a service fails its build with an unsatisfied dependency instead of
 * quietly turning four-eyes off.
 */
@ApplicationScoped
@IfBuildProperty(name = RedisApprovalStoreProducer.STORE_PROPERTY, stringValue = "redis", enableIfMissing = false)
class RedisApprovalStoreProducer {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)

    companion object {
        const val STORE_PROPERTY = "openbank.approval.store"
    }
}
