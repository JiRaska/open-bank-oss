// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.domain.model

import java.time.Instant

data class DeliveryObservation(
    val slotStart: Instant,
    val recordedAt: Instant,
    val collectorVersion: String,
    val overview: DeliveryOverview,
    val schemaVersion: Int = 1,
)

data class DeliveryHistory(
    val samplingEnabled: Boolean,
    val samplingCron: String,
    val slotIntervalSeconds: Long = 300,
    val retentionDays: Int = 30,
    val observations: List<DeliveryObservation>,
)
