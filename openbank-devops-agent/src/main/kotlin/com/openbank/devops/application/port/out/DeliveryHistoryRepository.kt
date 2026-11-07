// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.out

import com.openbank.devops.domain.model.DeliveryObservation

interface DeliveryHistoryRepository {
    /** Immutable first observation wins per repository and five-minute slot. */
    suspend fun save(observation: DeliveryObservation)
    suspend fun findRecent(limit: Int): List<DeliveryObservation>
}
