// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.infrastructure.persistence.repository.JournalHistoryCaptureReceipts
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.runtime.configuration.DurationConverter
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/** Expiry is enforced on every replay; this removes only already expired request metadata. */
@Startup
@ApplicationScoped
class HistoryCaptureReceiptPurge(
    private val receipts: JournalHistoryCaptureReceipts,
    private val metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.ledger.history-receipt-purge-every", defaultValue = "1m")
    private val interval: String,
) {
    private var liveness: WorkflowLivenessRecorder? = null

    init {
        if (!ScheduleSwitch.isOff(TRIGGER_PROPERTY)) {
            liveness = metrics.registerWorkflowLiveness(WORKFLOW_NAME, DurationConverter.parseDuration(interval))
        }
    }

    @Scheduled(
        every = "{openbank.ledger.history-receipt-purge-every:1m}",
        delayed = "{openbank.ledger.history-receipt-purge-delay:1m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun purge() {
        receipts.purgeExpired()
        liveness?.recordSuccess()
    }
    internal companion object {
        const val WORKFLOW_NAME = "ledger-history-capture-receipt-purge"
        const val TRIGGER_PROPERTY = "openbank.ledger.history-receipt-purge-every"
    }
}
