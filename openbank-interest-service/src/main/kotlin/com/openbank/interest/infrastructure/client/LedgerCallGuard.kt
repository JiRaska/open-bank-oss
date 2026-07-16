// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient

/**
 * Resilience boundary around the ledger-service call (mirrors lending's and transaction-service's
 * guard). The journal POST is idempotent on `idempotencyKey` — and this caller's key is derived from
 * the capitalization's *business* identity, not a per-attempt row id — so retries are safe: the
 * ledger collapses them onto the already-booked journal. The circuit breaker stops hammering a
 * degraded ledger and lets the caller fail fast (DORA Art. 11 operational resilience).
 *
 * Failing fast is the correct outcome here: capitalize() posts BEFORE it commits its own rows, so a
 * ledger outage leaves the accruals `ACCRUING` and the period simply capitalizes on the next run.
 */
@ApplicationScoped
class LedgerCallGuard(@RestClient private val ledgerClient: LedgerRestClient) {

    @Retry(maxRetries = 3, delay = 500, jitter = 100)
    @Timeout(value = 2000)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    fun postJournal(request: PostJournalRequest): Uni<JournalResponse> = ledgerClient.postJournal(request)
}
