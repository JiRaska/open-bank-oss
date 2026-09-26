// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

    /**
     * Whether this binding can actually destroy key material.
     *
     * A row count cannot answer this: a real backend legitimately returns 0 for a subject with no
     * warehouse data, so `0` is indistinguishable from "this build has no erasure backend at all".
     * [ErasureService][com.openbank.analytics.application.ErasureService] needs the difference,
     * because one is a completed erasure and the other is nothing having happened — and #9671 is
     * what it costs when the caller cannot tell: the deployed image carried the no-op and every
     * Art. 17 response still said `erased: true` and "Crypto-shredded analytics data".
     *
     * Defaults to `true`, so a real adapter needs no change; only a stub overrides it.
     */
    val performsErasure: Boolean
        get() = true
}
