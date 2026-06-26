// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.infrastructure.client

import com.openbank.ledger.application.port.out.CnbRateProvider
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.math.BigDecimal

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

    override suspend fun cnbRate(base: String): BigDecimal? = self.fetchWithResilience(base)

    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10_000, successThreshold = 2)
    @Retry(maxRetries = 3, delay = 500, jitter = 200, retryOn = [Exception::class])
    @Timeout(8_000)
    open suspend fun fetchWithResilience(base: String): BigDecimal? = try {
        val rate = client.getRate(base, QUOTE, SOURCE_CNB).awaitSuspending()
        // CNB fixing stores bid == ask == mid; either is the per-unit CZK rate.
        rate.bidRate ?: rate.askRate
    } catch (ex: WebApplicationException) {
        if (ex.response?.status == 404) null else throw ex
    }

    private companion object {
        const val QUOTE = "CZK"
        const val SOURCE_CNB = "CNB"
    }
}
