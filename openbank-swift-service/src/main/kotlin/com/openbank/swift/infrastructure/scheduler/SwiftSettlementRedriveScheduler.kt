// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.swift.application.usecase.SwiftSettlementRedriveService
import com.openbank.swift.application.usecase.SwiftSettlementRedriveService.Result
import com.openbank.swift.infrastructure.observability.SwiftSettlementMetrics
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kotlinx.coroutines.CancellationException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * #13686: sweeps accepted MT103s whose settlement booking is unproven and re-drives each one.
 *
 * A `suspend` @Scheduled method, so Quarkus supplies the Vert.x context the reactive repositories need;
 * there is no blocking bridge. Nothing is caught here: a failing database ends the tick with an exception
 * and the liveness heartbeat is not recorded, so `WorkflowLivenessStale` (`swift-settlement-redrive`) sees
 * a stopped sweep. A per-message settlement failure is counted durably by the service
 * (attempts + backoff), and an exhausted message increments `openbank_payment_unsettled_total
 * {outcome="redrive-exhausted"}` while its open clock keeps `SwiftBookingUnsettled` firing.
 */
@ApplicationScoped
class SwiftSettlementRedriveScheduler(
    private val service: SwiftSettlementRedriveService,
    private val metrics: SwiftSettlementMetrics,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.swift.settlement-redrive.enabled", defaultValue = "true")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.swift.settlement-redrive.batch-size", defaultValue = "20")
    private val batchSize: Int,
) {
    private var liveness: WorkflowLivenessRecorder? = null
    private val log = Logger.getLogger(SwiftSettlementRedriveScheduler::class.java)

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) {
            liveness = domainMetrics.registerWorkflowLiveness(
                "swift-settlement-redrive",
                Duration.ofSeconds(INTERVAL_SECONDS),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        every = "\${openbank.swift.settlement-redrive.every:60s}",
        delayed = "\${openbank.swift.settlement-redrive.delayed:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweep() {
        if (!enabled) return
        service.due(batchSize).forEach { id ->
            try {
                if (service.redrive(id) == Result.EXHAUSTED) {
                    metrics.unsettled(SwiftSettlementMetrics.Outcome.REDRIVE_EXHAUSTED)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // observed-by: openbank_payment_unsettled_total{outcome="redrive-error"} and the open booking
                // clock (SwiftBookingUnsettled). One message's database error must not abort the batch; its
                // claim lapses with the lease and the next tick retries it.
                metrics.unsettled(SwiftSettlementMetrics.Outcome.REDRIVE_ERROR)
                log.errorf(ex, "Settlement re-drive of SWIFT message %s failed; continuing the sweep", id)
            }
        }
        liveness?.recordSuccess()
    }

    private companion object {
        const val INTERVAL_SECONDS = 60L
    }
}
