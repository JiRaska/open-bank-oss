// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.feature

import com.openbank.fraud.application.usecase.FeatureOnlineUpdater
import com.openbank.libs.domain.feature.FeatureEvent
import com.openbank.libs.domain.feature.FeatureValue
import com.openbank.libs.domain.feature.OnlineFeatureStore
import com.openbank.libs.domain.feature.TRANSACTION_INITIATED
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H1
import com.openbank.libs.domain.feature.VELOCITY_TXN_COUNT_H24
import com.openbank.libs.feature.online.RedisOnlineFeatureStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0140 hard phase-1 acceptance gate: the **online** materialisation (events replayed through
 * [FeatureOnlineUpdater] into [RedisOnlineFeatureStore]) must agree bit-for-bit with the **offline**
 * reconstruction (the pure `FeatureDefinition.compute`). Any train/serve skew is a test failure, not
 * a production model regression. A fixed bucket-relative clock makes this independent of wall time.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FeatureParityIT {

    @Inject
    lateinit var redis: ReactiveRedisDataSource

    @Test
    fun `online store agrees with the offline compute for H1 and H24`() {
        val store: OnlineFeatureStore = RedisOnlineFeatureStore(redis)
        val updater = FeatureOnlineUpdater(store)
        val entity = "acc-${UUID.randomUUID()}"

        // A fixed H1 bucket; events land inside it, the as-of read instant sits just after them.
        val bucketStart = Instant.parse("2026-06-29T10:00:00Z")
        val asOf = bucketStart.plusSeconds(120)
        val events = (1..7).map { i ->
            FeatureEvent(
                entityId = entity,
                eventType = TRANSACTION_INITIATED,
                occurredAt = bucketStart.plusSeconds(i.toLong()),
            )
        }

        runBlocking {
            events.forEach { updater.onTransactionInitiated(entity, it.occurredAt) }

            val onlineH1 = (store.read(VELOCITY_TXN_COUNT_H1, entity, asOf) as FeatureValue.Fresh).value
            val onlineH24 = (store.read(VELOCITY_TXN_COUNT_H24, entity, asOf) as FeatureValue.Fresh).value

            val offlineH1 = VELOCITY_TXN_COUNT_H1.compute(asOf, events)
            val offlineH24 = VELOCITY_TXN_COUNT_H24.compute(asOf, events)

            assertThat(onlineH1).isEqualTo(offlineH1).isEqualTo(7.0)
            assertThat(onlineH24).isEqualTo(offlineH24).isEqualTo(7.0)
        }
    }

    @Test
    fun `replaying the same event is idempotent (offset surrogate)`() {
        val store: OnlineFeatureStore = RedisOnlineFeatureStore(redis)
        val updater = FeatureOnlineUpdater(store)
        val entity = "acc-${UUID.randomUUID()}"
        val bucketStart = Instant.parse("2026-06-29T10:00:00Z")
        val asOf = bucketStart.plusSeconds(120)
        val occurredAt = bucketStart.plusSeconds(5)

        runBlocking {
            repeat(3) { updater.onTransactionInitiated(entity, occurredAt) } // same event 3x
            val onlineH1 = (store.read(VELOCITY_TXN_COUNT_H1, entity, asOf) as FeatureValue.Fresh).value
            assertThat(onlineH1).isEqualTo(1.0) // redelivery did not double-count
        }
    }
}
