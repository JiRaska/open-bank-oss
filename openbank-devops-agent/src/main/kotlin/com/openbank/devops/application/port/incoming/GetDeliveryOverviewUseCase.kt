// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.incoming

import com.openbank.devops.domain.model.DeliveryOverview

interface GetDeliveryOverviewUseCase {
    suspend fun getOverview(): DeliveryOverview
}
