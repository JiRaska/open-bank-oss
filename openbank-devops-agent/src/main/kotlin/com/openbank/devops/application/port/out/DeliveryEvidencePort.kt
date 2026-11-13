// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.out

import com.openbank.devops.domain.model.DeliveryEvidence
import java.time.Instant

interface DeliveryEvidencePort {
    suspend fun collect(windowStart: Instant, windowEnd: Instant): DeliveryEvidence
}
