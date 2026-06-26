// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.AggregateKey

/**
 * Outbound port that performs the physical erasure of an aggregate's analytics data (ADR-0023, F6).
 *
 * "Erasure" in a 10-year warehouse is **crypto-shredding / tokenisation**, not row deletion: bronze
 * is the immutable log of record, so we destroy the means of reading the data rather than mutating
 * the log. The analytics layer already masks directly-identifying PII at the sink and keeps only the
 * pseudonymous [AggregateKey.aggregateId]; this port covers the residual erasure for the *erasable*
 * categories (consent/behavioural/operational) where no statutory hold applies.
 *
 * Default binding [com.openbank.analytics.infrastructure.erasure.NoOpCryptoErasure] is a logged no-op
 * so the service is offline-buildable; the real key-destruction adapter (KMS key per data subject) is
 * the documented follow-up. The *decision* of whether erasure is permitted lives in [RetentionPolicies].
 */
interface CryptoErasure {
    /** Crypto-shred/tokenise all analytics data for [key]. Idempotent. Returns rows/keys affected. */
    suspend fun erase(key: AggregateKey): Long
}
