// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.incoming

interface CaptureDeliveryObservationUseCase {
    suspend fun capture()
}
