// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Instant

/**
 * The online (low-latency, money-path) materialisation of the feature store (ADR-0140). Reads are
 * the synchronous hot-path lookup; writes are fed by the domain-event stream. Pure port — the
 * implementation (Valkey-backed) lives in `com.openbank.libs.feature.online` and is wired per
 * service via a `@Produces` factory (the same pattern as `IdempotencyStore`).
 */
interface OnlineFeatureStore {
    /**
     * Read [feature] for [entityId] as-of [now], applying the freshness assertion
     * ([FeatureDefinition.isStale]). Returns [FeatureValue.Fresh], [FeatureValue.Stale] (which the
     * caller must treat as missing), or [FeatureValue.Missing].
     */
    suspend fun read(feature: FeatureDefinition, entityId: String, now: Instant): FeatureValue

    /**
     * Record one event into a windowed counter [feature]: increment the count for [bucketStart],
     * resetting to 1 when the event opens a new bucket. **Idempotent on [offset]** — an event whose
     * offset is at or below the stored offset is a no-op (replay / at-least-once safe).
     *
     * @param bucketStart the tumbling-bucket start for the event (see [windowBucketStart]).
     * @param offset the source Kafka offset, monotonic per partition.
     */
    suspend fun incrementWindowed(
        feature: FeatureDefinition,
        entityId: String,
        occurredAt: Instant,
        bucketStart: Instant,
        offset: Long,
    )
}
