// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.openbank.interest.application.port.out.LedgerPostingRejectedException
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
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
 * Failing fast is the correct outcome here: capitalize() claims the accrual set (`ACCRUING` ->
 * `CAPITALIZING`) BEFORE this call, so a ledger outage leaves the accruals `CAPITALIZING`, not
 * `ACCRUING` — a retry of the same request finds the same claimed set and the period simply
 * capitalizes on the next run.
 *
 * **Only a failure that says something about the LEDGER is retried or counted by the breaker.** A
 * 4xx (bar 408/429) is the ledger answering, deterministically, about THIS request — a closed
 * accounting day, an unknown GL account, an invalid line. Retrying cannot change the answer, and
 * counting it opens a breaker that then fails every other capitalization's post while the ledger is
 * healthy. Before this split one closed-day 409 burned all four attempts (1 + 3 retries), opened the
 * breaker, and on every later sweep the half-open probe drew the same 409 and re-opened it — so the
 * log only ever showed "circuit breaker is open", never the refusal behind it (#10404). Such a
 * response surfaces as [LedgerPostingRejectedException]: `abortOn` stops the retries and `skipOn`
 * records it as a non-failure for the breaker.
 */
@ApplicationScoped
class LedgerCallGuard(@RestClient private val ledgerClient: LedgerRestClient) {

    @Retry(maxRetries = 3, delay = 500, jitter = 100, abortOn = [LedgerPostingRejectedException::class])
    @Timeout(value = 2000)
    @CircuitBreaker(
        requestVolumeThreshold = 5,
        failureRatio = 0.5,
        delay = 10000,
        skipOn = [LedgerPostingRejectedException::class],
    )
    fun postJournal(request: PostJournalRequest): Uni<JournalResponse> =
        ledgerClient.postJournal(request).onFailure().transform { classify(it, request) }

    internal companion object {
        private const val REQUEST_TIMEOUT = 408
        private const val TOO_MANY_REQUESTS = 429
        private const val CLIENT_ERROR_MIN = 400
        private const val CLIENT_ERROR_MAX = 499

        /** A 4xx the ledger answers identically on every retry. 408/429 are about load, not the request. */
        fun isDeterministicRejection(status: Int): Boolean =
            status in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX && status != REQUEST_TIMEOUT && status != TOO_MANY_REQUESTS

        fun classify(failure: Throwable, request: PostJournalRequest): Throwable {
            val response = (failure as? WebApplicationException)?.response ?: return failure
            if (!isDeterministicRejection(response.status)) return failure
            // The ledger's own reason ("... is closed") is the one fact an operator needs, and the
            // REST client's message ("Conflict, status code 409") omits it.
            val reason = runCatching { response.readEntity(String::class.java) }.getOrNull().orEmpty()
            return LedgerPostingRejectedException(
                response.status,
                "ledger rejected journal ${request.idempotencyKey} (entryDate=${request.entryDate}): " +
                    "HTTP ${response.status} $reason",
                failure,
            )
        }
    }
}
