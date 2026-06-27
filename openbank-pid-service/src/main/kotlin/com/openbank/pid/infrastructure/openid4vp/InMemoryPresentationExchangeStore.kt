// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.openid4vp

import com.openbank.pid.application.port.`in`.EudiResolutionResult
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore.Exchange
import com.openbank.pid.infrastructure.openid4vp.PresentationExchangeStore.Status
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [PresentationExchangeStore] — the original ConcurrentHashMap behaviour. Ephemeral and
 * single-replica; the test/dev fallback. Production uses [PostgresPresentationExchangeStore].
 * Selected by `openbank.pid.eudi.persistence` (default postgres). Unit tests construct it directly.
 */
@ApplicationScoped
@DefaultBean
class InMemoryPresentationExchangeStore(
    @ConfigProperty(name = "openbank.pid.eudi.exchange-ttl-seconds", defaultValue = "300")
    private val ttlSeconds: Long,
) : PresentationExchangeStore {

    private val exchanges = ConcurrentHashMap<String, Exchange>()

    override suspend fun create(transactionId: String, nonce: String, audience: String, now: Instant): Exchange {
        val exchange = Exchange(
            transactionId = transactionId,
            nonce = nonce,
            audience = audience,
            createdAt = now,
            expiresAt = now.plusSeconds(ttlSeconds),
            status = Status.PENDING,
        )
        exchanges[transactionId] = exchange
        evictExpired(now)
        return exchange
    }

    override suspend fun find(transactionId: String, now: Instant): Exchange? {
        val exchange = exchanges[transactionId] ?: return null
        if (exchange.status == Status.PENDING && now.isAfter(exchange.expiresAt)) {
            exchange.status = Status.EXPIRED
        }
        return exchange
    }

    override suspend fun complete(transactionId: String, result: EudiResolutionResult, now: Instant): Boolean {
        var completed = false
        exchanges.computeIfPresent(transactionId) { _, exchange ->
            if (exchange.status == Status.PENDING && !now.isAfter(exchange.expiresAt)) {
                exchange.status = Status.COMPLETED
                exchange.result = result
                completed = true
            }
            exchange
        }
        return completed
    }

    /** Drop exchanges that expired over an hour ago (completed ones are kept until then so polls succeed). */
    private fun evictExpired(now: Instant) {
        val cutoff = now.minus(EVICT_GRACE_HOURS, ChronoUnit.HOURS)
        exchanges.values.removeIf { it.expiresAt.isBefore(cutoff) }
    }

    private companion object {
        const val EVICT_GRACE_HOURS = 1L
    }
}
