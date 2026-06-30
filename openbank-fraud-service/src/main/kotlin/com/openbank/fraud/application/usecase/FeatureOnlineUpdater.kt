// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.application.usecase

import com.openbank.libs.domain.feature.OnlineFeatureStore
import com.openbank.libs.domain.feature.PHASE1_FEATURES
import com.openbank.libs.domain.feature.windowBucketStart
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Updates the online feature store (ADR-0140) from the `transaction.initiated` signal plane, in
 * parallel with the legacy Postgres velocity aggregates (which stay the source for the live rules).
 * Pure event-time: the bucket is derived from the event's `occurredAt`, not wall-clock.
 *
 * Phase-1 idempotency uses `occurredAt` (epoch millis) as a monotonic surrogate offset — it makes an
 * exact redelivery a no-op (the common at-least-once case). True Kafka partition/offset idempotency
 * is the phase-1b hardening (it needs the consumer to read `Message` metadata); deliberately out of
 * scope here to avoid an ack-management change to the money-path consumer for a shadow-only feature.
 */
@ApplicationScoped
class FeatureOnlineUpdater(private val store: OnlineFeatureStore) {

    suspend fun onTransactionInitiated(entityId: String, occurredAt: Instant) {
        val surrogateOffset = occurredAt.toEpochMilli()
        PHASE1_FEATURES.forEach { feature ->
            val bucketStart = windowBucketStart(feature, occurredAt) ?: return@forEach
            store.incrementWindowed(feature, entityId, occurredAt, bucketStart, surrogateOffset)
        }
    }
}
