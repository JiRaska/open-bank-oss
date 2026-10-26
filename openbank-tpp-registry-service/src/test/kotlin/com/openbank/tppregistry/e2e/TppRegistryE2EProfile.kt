// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.e2e

import com.openbank.libs.testing.containers.PostgresRedpandaRedisTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Shared profile for the tpp-registry E2E suite: real Postgres + real Redpanda + real Valkey
 * (the shared [PostgresRedpandaRedisTestResource] — this service needs all three: the registry
 * rows in Postgres, the outbox dispatcher on the real broker, and the Redis-backed
 * [com.openbank.libs.idempotency.IdempotencyStore] the register/blacklist/sync endpoints use).
 *
 * Every pre-existing test either switches `tpp-events-out` to the in-memory connector or switches
 * the dispatcher off, so TPP_REGISTERED / TPP_BLACKLISTED had never reached a real broker; and the
 * idempotency-fingerprint IT runs the Redis store but drives the resource class indirectly. This
 * profile exists to close exactly that gap: one boot where the REST endpoints, the reactive
 * repository, the Redis idempotency reservations, the outbox dispatcher and the Kafka emitter are
 * all the production adapters running against real infrastructure.
 *
 * OPA runs advisory (`authz.enforce=false`): the blacklist endpoint's @Authorize interceptor with
 * an unreachable sidecar would fail CLOSED (503) and the suite would measure the absence of a PDP
 * container instead of the registry lifecycle. Authorization itself is covered by
 * TppRegistrySecurityTest with its own PDP stand-in.
 */
class TppRegistryE2EProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(
        TestResourceEntry(
            PostgresRedpandaRedisTestResource::class.java,
            mapOf("db" to "openbank_tpp_registry_e2e"),
        ),
    )

    override fun getConfigOverrides() = mapOf(
        "openbank.outbox.dispatch-enabled" to "true",
        "openbank.outbox.poll-interval" to "1s",
        "openbank.outbox.initial-delay" to "1s",
        // Advisory PDP: see the class KDoc. Same override as TppOutboxWriteIT's DispatcherOffResource.
        "authz.enforce" to "false",
    )
}
