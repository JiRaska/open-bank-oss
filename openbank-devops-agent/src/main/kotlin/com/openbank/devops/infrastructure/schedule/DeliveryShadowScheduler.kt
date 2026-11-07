// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.schedule

import com.openbank.devops.application.port.incoming.CaptureDeliveryObservationUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

@Startup
@ApplicationScoped
class DeliveryShadowScheduler(
    private val capture: CaptureDeliveryObservationUseCase,
    private val metrics: DomainMetrics,
    @param:ConfigProperty(name = "openbank.devops.delivery-shadow-enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun registerLiveness() {
        if (enabled) liveness = metrics.registerWorkflowLiveness("devops-delivery-shadow", INTERVAL)
    }

    private companion object {
        val INTERVAL: Duration = Duration.ofMinutes(5)
    }

    @Scheduled(
        cron = "{openbank.devops.delivery-shadow-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sample() {
        if (!enabled) return
        capture.capture()
        liveness?.recordSuccess()
    }
}
