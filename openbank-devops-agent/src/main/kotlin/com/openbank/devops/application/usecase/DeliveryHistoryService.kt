// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.incoming.CaptureDeliveryObservationUseCase
import com.openbank.devops.application.port.incoming.GetDeliveryHistoryUseCase
import com.openbank.devops.application.port.incoming.GetDeliveryOverviewUseCase
import com.openbank.devops.application.port.out.DeliveryHistoryRepository
import com.openbank.devops.domain.model.DeliveryHistory
import com.openbank.devops.domain.model.DeliveryObservation
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Instant

@ApplicationScoped
class DeliveryHistoryService(
    private val overview: GetDeliveryOverviewUseCase,
    private val repository: DeliveryHistoryRepository,
    private val clock: Clock,
    @param:ConfigProperty(name = "quarkus.application.version") private val collectorVersion: String,
    @param:ConfigProperty(name = "openbank.devops.delivery-shadow-enabled", defaultValue = "false")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "openbank.devops.delivery-shadow-cron") private val samplingCron: String,
) : CaptureDeliveryObservationUseCase,
    GetDeliveryHistoryUseCase {
    override suspend fun capture() {
        if (!enabled) return
        val sample = overview.getOverview()
        val slot = Instant.ofEpochSecond(
            Math.floorDiv(sample.observedAt.epochSecond, INTERVAL_SECONDS) * INTERVAL_SECONDS,
        )
        repository.save(DeliveryObservation(slot, Instant.now(clock), collectorVersion, sample))
    }

    override suspend fun getHistory(limit: Int): DeliveryHistory {
        require(limit in 1..MAX_HISTORY) { "limit must be between 1 and $MAX_HISTORY" }
        return DeliveryHistory(enabled, samplingCron, observations = repository.findRecent(limit))
    }

    private companion object {
        const val INTERVAL_SECONDS = 300L
        const val MAX_HISTORY = 288
    }
}
