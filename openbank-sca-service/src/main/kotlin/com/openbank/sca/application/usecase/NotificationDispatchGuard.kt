// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.sca.application.port.out.NotificationSender
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.faulttolerance.Timeout
import java.util.UUID

@ApplicationScoped
class NotificationDispatchGuard(private val notificationSender: NotificationSender) {

    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 200, jitter = 100, retryOn = [Exception::class])
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000, successThreshold = 2)
    suspend fun sendPushNotification(partyId: UUID, challengeId: UUID, message: String) {
        notificationSender.sendPushNotification(partyId, challengeId, message)
    }
}
