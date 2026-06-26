// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.infrastructure.client

import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

@ApplicationScoped
class LedgerCallGuard(@RestClient private val ledgerClient: LedgerRestClient) {

    @Retry(maxRetries = 3, delay = 500, jitter = 100)
    @Timeout(2000)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    fun postJournal(request: PostJournalRequest): Uni<JournalResponse> = ledgerClient.postJournal(request)

    @Retry(maxRetries = 2, delay = 300)
    @Timeout(2000)
    fun reverseJournal(journalId: UUID, request: ReverseJournalRequest): Uni<JournalResponse> =
        ledgerClient.reverseJournal(journalId, request)
}
