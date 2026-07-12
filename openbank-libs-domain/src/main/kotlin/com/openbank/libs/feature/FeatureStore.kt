// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.feature

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * An online feature's stored value (ADR-0140). [asOf] is the source event's business
 * time — never wall-clock-at-write, per ADR-0140's anti-leakage rule — and
 * [sourceOffset] is the offset of the event that produced this value, carried for
 * staleness detection and audit/reproducibility (it is what makes a training row
 * offset-pinned and replayable, ADR-0141).
 */
data class FeatureValue(
    val value: BigDecimal,
    val asOf: Instant,
    val sourceOffset: Long,
)

/**
 * The outcome of reading a feature and classifying it against its declared TTL
 * (ADR-0140). A [Stale] value is deliberately NOT usable — the decisioning engine
 * must treat it identically to [Missing] (ADR-0139's fail-closed floor: "a silent
 * stale feature is worse than an absent one"). Callers should never branch on
 * [FeatureValue] directly; always go through [Freshness] so a future feature type
 * cannot accidentally skip the staleness check.
 */
sealed interface Freshness {
    data class Fresh(val value: FeatureValue) : Freshness
    data class Stale(val value: FeatureValue) : Freshness
    data object Missing : Freshness
}

/**
 * Online feature store (ADR-0140) — single-key lookup, money-path latency budget.
 * NOT a CDI bean by itself; per-service `@Produces` wiring, same pattern as
 * [com.openbank.libs.approval.ApprovalStore] / [com.openbank.libs.idempotency.IdempotencyStore]
 * (not every service uses Redis, so a libs-side bean would break ArC augmentation
 * fleet-wide).
 *
 * This port only stores/retrieves a value — it does NOT compute one. The "one
 * definition, two materialisations" feature-computation function (ADR-0140) is
 * declared by the consuming service (e.g. fraud-service's velocity aggregates);
 * this store is the shared, generic online-read/write primitive it's built on.
 */
interface FeatureStore {
    /**
     * Reads the current value for `name`/`entityId` and classifies it against [ttl]:
     * absent -> [Freshness.Missing]; present but older than [ttl] -> [Freshness.Stale];
     * otherwise -> [Freshness.Fresh]. Never throws for a missing/stale key — those are
     * expected outcomes in the money path, not error conditions.
     */
    suspend fun read(name: String, entityId: String, ttl: Duration): Freshness

    /**
     * Writes/overwrites the current value for `name`/`entityId`. The online updater
     * (ADR-0140's outbox consumer) MUST be idempotent on [FeatureValue.sourceOffset]
     * itself before calling this — this port does not deduplicate replayed events.
     */
    suspend fun write(name: String, entityId: String, value: FeatureValue)
}
