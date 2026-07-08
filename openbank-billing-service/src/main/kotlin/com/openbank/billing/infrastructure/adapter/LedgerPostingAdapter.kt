// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.adapter

import com.openbank.billing.application.port.out.LedgerPostingPort
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.infrastructure.client.BillingJournalFactory
import com.openbank.billing.infrastructure.client.BillingLedgerConfig
import com.openbank.billing.infrastructure.client.LedgerJournalEntryResponse
import com.openbank.billing.infrastructure.client.LedgerPostJournalRequest
import com.openbank.billing.infrastructure.client.LedgerRestClient
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Real [LedgerPostingPort]: posts a fee charge to ledger-service as a balanced double-entry
 * journal (ADR-0143 step 2), the same `POST /api/v1/journals` contract every other money-path
 * service uses. Mirrors `openbank-settlement-service`'s `LedgerBookAdapter` /
 * `openbank-lending-service`'s `RestLedgerPostingAdapter` in structure and error handling.
 *
 * Resilience ([Retry]/[Timeout]/[CircuitBreaker]) sits directly on the ledger HTTP call as a
 * defense-in-depth boundary — the journal POST is idempotent on [FeeJournalCommand.idempotencyKey],
 * so a retry after a timeout is safe. This is IN ADDITION to (not instead of) the outbox
 * dispatcher's own resilience annotations on `publishWithResilience`: a call that exhausts
 * retries here still throws, which the dispatcher records as an outbox `markFailed` and retries
 * on its next scheduled tick — the outbox is the outer at-least-once loop, this is the inner
 * per-call circuit breaker.
 */
@ApplicationScoped
class LedgerPostingAdapter(
    @RestClient private val ledgerClient: LedgerRestClient,
    private val config: BillingLedgerConfig,
    private val clock: Clock,
) : LedgerPostingPort {

    private val log = Logger.getLogger(LedgerPostingAdapter::class.java)

    override suspend fun post(command: FeeJournalCommand): UUID {
        val request = BillingJournalFactory.buildRequest(
            command = command,
            accounts = config.gl(),
            systemActorId = config.systemActorId(),
            date = LocalDate.now(clock),
        )
        val response = postWithResilience(request = request).awaitSuspending()
        log.debugf(
            "ledger journal %s posted (%s) for fee idempotencyKey=%s",
            response.id,
            response.status,
            command.idempotencyKey,
        )
        return response.id
    }

    @Retry(maxRetries = MAX_RETRIES, delay = RETRY_DELAY_MS, jitter = RETRY_JITTER_MS)
    @Timeout(CALL_TIMEOUT_MS)
    @CircuitBreaker(requestVolumeThreshold = CB_VOLUME_THRESHOLD, failureRatio = CB_FAILURE_RATIO, delay = CB_DELAY_MS)
    fun postWithResilience(request: LedgerPostJournalRequest): Uni<LedgerJournalEntryResponse> =
        ledgerClient.postJournal(request)

    private companion object {
        // Resilience tuning — mirrors LedgerCallGuard (openbank-lending-service).
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 500L
        const val RETRY_JITTER_MS = 100L
        const val CALL_TIMEOUT_MS = 2000L
        const val CB_VOLUME_THRESHOLD = 5
        const val CB_FAILURE_RATIO = 0.5
        const val CB_DELAY_MS = 10000L
    }
}
