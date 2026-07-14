// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.webauthn

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import jakarta.enterprise.context.ApplicationScoped

/**
 * Redis-backed store of outstanding WebAuthn challenges (ADR-0066 F2), keyed by the challenge
 * value itself. A WebAuthn challenge is high-entropy random bytes, so using it directly as the
 * key is a safe, simple correlation id — no separate session/ticket concept is needed to match a
 * "begin" call to its later "complete" call.
 *
 * [consume] deletes on read (get-then-delete is not atomic here, but a lost race only ever
 * causes a spurious rejection of a legitimate concurrent retry, never a replay: a challenge that
 * IS found and consumed is immediately gone for any other reader). This is the server-side half
 * of WebAuthn replay protection — the manager verifies the challenge embedded in
 * `clientDataJSON` cryptographically, but only THIS store proves the edge actually issued it and
 * hasn't already accepted a completion for it.
 */
@ApplicationScoped
class ChallengeStore(redis: RedisDataSource) {
    private val values = redis.value(String::class.java)

    /** [purpose] is opaque bookkeeping (e.g. "registration" or "authentication"), not verified. */
    fun save(challengeBase64Url: String, purpose: String) {
        values.set(key(challengeBase64Url), purpose, SetArgs().ex(TTL_SECONDS))
    }

    /** Returns the stored purpose and deletes the entry, or null if unknown/expired/already used. */
    fun consume(challengeBase64Url: String): String? = values.getdel(key(challengeBase64Url))

    private fun key(challenge: String) = "edge:webauthn:challenge:$challenge"

    companion object {
        // Generous headroom over a Face ID round-trip; short enough that a lost/logged challenge
        // is worthless well before anyone could act on it.
        const val TTL_SECONDS = 2L * 60
    }
}
