// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import java.util.concurrent.ConcurrentHashMap

/**
 * Test double for [PaymentSessionBackend]: the unit tests that construct the resource by hand need
 * a working store without a Redis. Test-source only on purpose — production has exactly one
 * backend, the Redis one, so a pod-local store cannot be selected by accident (issue #4728).
 */
class InMemoryPaymentSessionBackend : PaymentSessionBackend {
    private val sessions = ConcurrentHashMap<String, PaymentSessionStore.Session>()

    override fun put(token: String, session: PaymentSessionStore.Session, ttlMs: Long) {
        sessions[token] = session
    }

    override fun get(token: String): PaymentSessionStore.Session? = sessions[token]

    override fun markPaid(token: String) {
        sessions.computeIfPresent(token) { _, s -> s.copy(paid = true) }
    }

    override fun attachPaymentIfAbsent(token: String, paymentId: String) {
        sessions.computeIfPresent(token) { _, s ->
            if (s.paymentId.isNullOrBlank()) s.copy(paymentId = paymentId) else s
        }
    }
}

/** A [PaymentSessionStore] over a fresh [InMemoryPaymentSessionBackend]. */
fun inMemoryPaymentSessionStore(): PaymentSessionStore = PaymentSessionStore(InMemoryPaymentSessionBackend())
