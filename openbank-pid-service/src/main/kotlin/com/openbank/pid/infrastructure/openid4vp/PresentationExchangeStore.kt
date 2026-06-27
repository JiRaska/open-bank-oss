// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vp

import com.openbank.pid.application.port.`in`.EudiResolutionResult
import java.time.Instant

/**
 * In-flight OpenID4VP presentation exchanges (ADR-0094).
 *
 * Each exchange binds a single-use [Exchange.nonce] to a transaction. The wallet's `direct_post`
 * response is accepted **exactly once** (PENDING → COMPLETED) before the nonce is spent — that
 * atomic transition is the anti-replay guarantee, complementing the holder key-binding the verifier
 * checks against the same nonce. Exchanges are ephemeral (TTL).
 *
 * Backed by [PostgresPresentationExchangeStore] in production (durable + multi-replica safe: the
 * create / wallet-callback / poll endpoints may land on different replicas) and
 * [InMemoryPresentationExchangeStore] for tests, selected by `openbank.pid.eudi.persistence`. The
 * exchange holds no secret beyond the nonce (itself public, single-use), so the only failure mode of
 * a lost entry is a wallet having to restart the flow.
 */
interface PresentationExchangeStore {
    enum class Status { PENDING, COMPLETED, EXPIRED }

    class Exchange(
        val transactionId: String,
        val nonce: String,
        val audience: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        @Volatile var status: Status,
        @Volatile var result: EudiResolutionResult? = null,
    )

    suspend fun create(transactionId: String, nonce: String, audience: String, now: Instant): Exchange

    /** Look up an exchange, treating a still-PENDING-but-elapsed one as EXPIRED. */
    suspend fun find(transactionId: String, now: Instant): Exchange?

    /**
     * Atomically spend the nonce: transition PENDING → COMPLETED exactly once, attaching [result].
     * Returns true on success; false if the exchange is unknown, already consumed, or expired (replay
     * or stale). Verification must succeed BEFORE calling this — a failed attempt leaves the exchange
     * PENDING so the wallet may retry within the TTL.
     */
    suspend fun complete(transactionId: String, result: EudiResolutionResult, now: Instant): Boolean
}
