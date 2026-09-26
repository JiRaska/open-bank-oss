// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * `@QuarkusTest`-free coverage of [DefaultIdempotencyStoreProducer.idempotencyStore] — the two
 * branches the class's KDoc describes: a resolvable Redis `Instance<>` producing a working
 * [RedisIdempotencyStore], and an unresolvable one failing with the documented, actionable
 * message rather than silently doing nothing or throwing an opaque NPE.
 */
class DefaultIdempotencyStoreProducerTest {

    private val clock = Clock.systemUTC()

    @Test
    fun `produces a RedisIdempotencyStore when the Redis client is resolvable`() {
        val redisDataSource = mockk<ReactiveRedisDataSource>()
        val redis = mockk<Instance<ReactiveRedisDataSource>>()
        every { redis.isResolvable } returns true
        every { redis.get() } returns redisDataSource

        val store = DefaultIdempotencyStoreProducer().idempotencyStore(redis, clock)

        assertThat(store).isInstanceOf(RedisIdempotencyStore::class.java)
    }

    @Test
    fun `fails with a clear, actionable message when no Redis client is configured`() {
        val redis = mockk<Instance<ReactiveRedisDataSource>>()
        every { redis.isResolvable } returns false

        assertThatIllegalStateException()
            .isThrownBy { DefaultIdempotencyStoreProducer().idempotencyStore(redis, clock) }
            .withMessageContaining("quarkus.redis.hosts")
            .withMessageContaining("quarkus-redis-client")
            .withMessageContaining("@Produces IdempotencyStore")
    }
}
