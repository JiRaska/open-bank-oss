// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval.impl

import io.mockk.mockk
import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * Replaces the per-service `ApprovalConfigTest` copies. The real wiring proof is each opted-in
 * service's `quarkusBuild` (an `ApprovalResource` constructor-injects the store, so a missing
 * opt-in is an unsatisfied dependency) plus notification-service's `ApprovalStoreWiringIT`
 * round-tripping a real approval through real Redis.
 */
class RedisApprovalStoreProducerTest {

    @Test
    fun `produces a RedisApprovalStore wired with the given redis and clock`() {
        val store = RedisApprovalStoreProducer().approvalStore(mockk<ReactiveRedisDataSource>(), Clock.systemUTC())

        assertThat(store).isInstanceOf(RedisApprovalStore::class.java)
    }

    @Test
    fun `the bean is opt-in - absent unless openbank approval store is exactly redis`() {
        val gate = RedisApprovalStoreProducer::class.java.getAnnotation(IfBuildProperty::class.java)

        assertThat(gate).isNotNull
        assertThat(gate.name).isEqualTo("openbank.approval.store")
        assertThat(gate.stringValue).isEqualTo("redis")
        // enableIfMissing=true would register the bean in every service with the libs JAR:
        // four-eyes silently on, and an unsatisfiable Redis injection point where none is configured.
        assertThat(gate.enableIfMissing).isFalse()
    }
}
