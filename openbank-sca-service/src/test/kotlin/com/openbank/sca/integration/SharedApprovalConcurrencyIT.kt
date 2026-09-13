// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.integration

import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.impl.RedisApprovalStore
import com.openbank.sca.it.PostgresRedisTestResource
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.redis.datasource.value.SetArgs
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

/** Exercises the shared production store against Redis; no in-memory approval implementation. */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(OutboxDispatchDisabledProfile::class)
class SharedApprovalConcurrencyIT {
    @Inject lateinit var redis: ReactiveRedisDataSource

    @Inject lateinit var clock: Clock

    @Test
    fun `concurrent checkers acknowledge exactly one immutable decision`(): Unit = runBlocking {
        val store = RedisApprovalStore(redis, clock)
        val approval = store.create("device.revoke", "test-device", "maker")
        val attempts = (1..8).map { index ->
            async { runCatching { store.decide(approval.id, "checker-$index", index % 2 == 0) } }
        }.awaitAll()
        val accepted = attempts.mapNotNull { it.getOrNull() }
        assertThat(accepted).hasSize(1)
        assertThat(store.find(approval.id)).isEqualTo(accepted.single())
    }

    @Test
    fun `concurrent consumers spend an approved decision exactly once`(): Unit = runBlocking {
        val store = RedisApprovalStore(redis, clock)
        val approval = store.create("device.revoke", "test-device", "maker")
        store.decide(approval.id, "checker", true)
        val attempts = (1..8).map {
            async { runCatching { store.markExecuted(approval.id) } }
        }.awaitAll()
        assertThat(attempts.mapNotNull { it.getOrNull() }).hasSize(1)
        assertThat(store.find(approval.id)?.status).isEqualTo(ApprovalStatus.EXECUTED)
    }

    @Test
    fun `a late checker or consumer cannot recreate an evicted approval`(): Unit = runBlocking {
        val regular = RedisApprovalStore(redis, clock)
        val delayed = RedisApprovalStore(evictAtWriteBoundary(), clock)
        for (approved in listOf(false, true)) {
            val approval = regular.create("device.revoke", "test-device", "maker")
            if (approved) regular.decide(approval.id, "checker", true)
            val result = if (approved) {
                delayed.markExecuted(approval.id)
            } else {
                delayed.decide(approval.id, "checker", true)
            }
            assertThat(result).isNull()
            assertThat(regular.find(approval.id)).isNull()
        }
    }

    private fun evictAtWriteBoundary(): ReactiveRedisDataSource {
        // DEL simulates TTL eviction after the read. Both SET and Lua still run on real
        // Redis; the boundary cannot manufacture a successful or rejected CAS result.
        val actual = redis.value(String::class.java)
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { values.get(any()) } answers { actual.get(firstArg()) }
        every { values.set(any<String>(), any<String>(), any<SetArgs>()) } answers {
            val key = firstArg<String>()
            val value = secondArg<String>()
            val options = thirdArg<SetArgs>()
            redis.key(String::class.java).del(key).chain { _ -> actual.set(key, value, options) }
        }
        val guarded = mockk<ReactiveRedisDataSource>()
        every { guarded.value(String::class.java) } returns values
        every { guarded.execute("EVAL", *anyVararg()) } answers {
            val argv = secondArg<Array<String>>()
            redis.key(String::class.java).del(argv[2]).chain { _ -> redis.execute("EVAL", *argv) }
        }
        return guarded
    }
}
