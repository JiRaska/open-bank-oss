// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.client

import com.openbank.ledger.application.port.out.CnbFixing
import com.openbank.ledger.application.port.out.CnbRateProvider
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilient adapter over [FxServiceClient]. Wraps the cross-service call in the same fault-tolerance
 * posture as the outbox dispatchers so a slow or down fx-service degrades the daily revaluation
 * gracefully. A 404 (no ČNB rate ingested for the currency) is a normal "absent" answer mapped to
 * `null` — not retried — so a missing currency simply skips that leg rather than failing the batch.
 */
@ApplicationScoped
class FxServiceCnbRateAdapter(@RestClient private val client: FxServiceClient) : CnbRateProvider {

    @Inject
    lateinit var self: FxServiceCnbRateAdapter

    override suspend fun cnbRate(base: String): CnbFixing? = self.fetchWithResilience(base)

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 500, jitter = 200, retryOn = [Exception::class])
    @Timeout(8_000)
    open suspend fun fetchWithResilience(base: String): CnbFixing? = try {
        val rate = client.getRate(base, QUOTE, SOURCE_CNB).awaitSuspending()
        // CNB fixing stores bid == ask == mid; either is the per-unit CZK rate.
        // `validFrom` rides along unchanged, including when absent — see CnbFixing's KDoc for why a
        // missing fixing date must not become Instant.now() here (#3921).
        (rate.bidRate ?: rate.askRate)?.let { CnbFixing(it, rate.validFrom) }
    } catch (ex: WebApplicationException) {
        if (ex.response?.status == 404) null else throw ex
    }

    private companion object {
        const val QUOTE = "CZK"
        const val SOURCE_CNB = "CNB"
    }
}
