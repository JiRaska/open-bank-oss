// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.infrastructure.persistence.repository.JournalHistoryCaptureReceipts
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped

/** Expiry is enforced on every replay; this removes only already expired request metadata. */
@ApplicationScoped
class HistoryCaptureReceiptPurge(private val receipts: JournalHistoryCaptureReceipts) {
    @Scheduled(
        every = "{openbank.ledger.history-receipt-purge-every:1m}",
        delayed = "{openbank.ledger.history-receipt-purge-delay:1m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun purge() {
        receipts.purgeExpired()
    }
}
