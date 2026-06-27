// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.client

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilience boundary around the ledger-service call (mirrors the transaction-service guard). The
 * journal POST is idempotent on `idempotencyKey`, so retries are safe; the circuit breaker stops
 * hammering a degraded ledger and lets the caller fail fast (DORA Art. 11 operational resilience).
 */
@ApplicationScoped
class LedgerCallGuard(@RestClient private val ledgerClient: LedgerRestClient) {

    @Retry(maxRetries = 3, delay = 500, jitter = 100)
    @Timeout(2000)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    fun postJournal(request: PostJournalRequest): Uni<JournalResponse> = ledgerClient.postJournal(request)
}
