// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.incoming

import com.openbank.devops.domain.model.DeliveryHistory

interface GetDeliveryHistoryUseCase {
    suspend fun getHistory(limit: Int): DeliveryHistory
}
