// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped

/**
 * Where [PaymentSessionStore] keeps nearby-pay sessions. Every operation must be visible to every
 * replica of the edge, and the two updates must not resurrect an expired session.
 */
interface PaymentSessionBackend {
    fun put(token: String, session: PaymentSessionStore.Session, ttlMs: Long)

    fun get(token: String): PaymentSessionStore.Session?

    /** Set `paid`, only if the session still exists. */
    fun markPaid(token: String)

    /** Set the payment id only if the session exists AND has none yet (first write wins). */
    fun attachPaymentIfAbsent(token: String, paymentId: String)
}

/**
 * Redis hash per session, `edge:nearby-pay:session:<token>`, expiring with the session (issue #4728).
 *
 * Each write is one Lua script so it is atomic across replicas: create sets the fields and the
 * expiry together (a hash with no TTL in this Redis would be kept forever — the same instance
 * holds the durable passkey keyspace and is never flushed), and the two updates only touch a key
 * that still EXISTS. A plain HSET after expiry would recreate the session as a TTL-less, partial
 * hash: unresolvable garbage that never leaves.
 */
@ApplicationScoped
class RedisPaymentSessionBackend(private val redis: RedisDataSource) : PaymentSessionBackend {

    private val hashes = redis.hash(String::class.java)

    override fun put(token: String, session: PaymentSessionStore.Session, ttlMs: Long) {
        // Optional fields are written as "" and read back as null, so the arity is fixed.
        redis.execute(
            "EVAL", CREATE_SCRIPT, "1", key(token), ttlMs.toString(),
            F_CREDITOR_ACCOUNT, session.creditorAccountId,
            F_CREDITOR_PARTY, session.creditorPartyId,
            F_DISPLAY_NAME, session.displayName,
            F_AMOUNT, session.requestedAmount.orEmpty(),
            F_MASKED, session.creditorMasked,
            F_EXPIRES_AT, session.expiresAt.toString(),
            F_PAID, session.paid.toString(),
            F_PAYMENT_ID, session.paymentId.orEmpty(),
        )
    }

    override fun get(token: String): PaymentSessionStore.Session? {
        val h = hashes.hgetall(key(token))
        if (h.isNullOrEmpty()) return null
        return PaymentSessionStore.Session(
            creditorAccountId = h[F_CREDITOR_ACCOUNT] ?: return null,
            creditorPartyId = h[F_CREDITOR_PARTY] ?: return null,
            displayName = h[F_DISPLAY_NAME] ?: return null,
            requestedAmount = h[F_AMOUNT]?.ifEmpty { null },
            creditorMasked = h[F_MASKED] ?: return null,
            expiresAt = h[F_EXPIRES_AT]?.toLongOrNull() ?: return null,
            paid = h[F_PAID] == "true",
            paymentId = h[F_PAYMENT_ID]?.ifEmpty { null },
        )
    }

    override fun markPaid(token: String) {
        redis.execute("EVAL", SET_IF_PRESENT_SCRIPT, "1", key(token), F_PAID, "true")
    }

    override fun attachPaymentIfAbsent(token: String, paymentId: String) {
        redis.execute("EVAL", SETNX_IF_PRESENT_SCRIPT, "1", key(token), F_PAYMENT_ID, paymentId)
    }

    companion object {
        const val KEY_PREFIX = "edge:nearby-pay:session:"

        fun key(token: String) = KEY_PREFIX + token

        private const val F_CREDITOR_ACCOUNT = "creditorAccountId"
        private const val F_CREDITOR_PARTY = "creditorPartyId"
        private const val F_DISPLAY_NAME = "displayName"
        private const val F_AMOUNT = "requestedAmount"
        private const val F_MASKED = "creditorMasked"
        private const val F_EXPIRES_AT = "expiresAt"
        private const val F_PAID = "paid"
        private const val F_PAYMENT_ID = "paymentId"

        // ARGV[1] = ttl ms, ARGV[2..] = field/value pairs.
        private const val CREATE_SCRIPT =
            "redis.call('HSET', KEYS[1], unpack(ARGV, 2)) " +
                "redis.call('PEXPIRE', KEYS[1], ARGV[1]) return 1"

        private const val SET_IF_PRESENT_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 1 then " +
                "return redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) end return -1"

        // "" is the stored form of "no payment yet", so first-write-wins tests for that, not HSETNX.
        private const val SETNX_IF_PRESENT_SCRIPT =
            "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end " +
                "local cur = redis.call('HGET', KEYS[1], ARGV[1]) " +
                "if cur and cur ~= '' then return 0 end " +
                "return redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])"
    }
}
