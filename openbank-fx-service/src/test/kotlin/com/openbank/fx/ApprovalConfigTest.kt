// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx

import com.openbank.fx.infrastructure.approval.ApprovalConfig
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

/** Unit coverage for the trivial [ApprovalStore][com.openbank.libs.approval.ApprovalStore] producer (ADR-0155). */
class ApprovalConfigTest {

    @Test
    fun `approvalStore produces a RedisApprovalStore wired with the given redis and clock`() {
        val redis = mockk<ReactiveRedisDataSource>()
        val clock = Clock.systemUTC()

        val store = ApprovalConfig().approvalStore(redis, clock)

        assertThat(store).isInstanceOf(RedisApprovalStore::class.java)
    }
}
