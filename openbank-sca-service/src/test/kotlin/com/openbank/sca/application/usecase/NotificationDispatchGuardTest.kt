// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.openbank.sca.application.port.out.NotificationSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

class NotificationDispatchGuardTest {

    private val sender = mockk<NotificationSender>()
    private val guard = NotificationDispatchGuard(sender)

    @Test
    fun `sendPushNotification delegates to NotificationSender`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val challengeId = UUID.randomUUID()
        coEvery { sender.sendPushNotification(partyId, challengeId, any()) } returns Unit

        guard.sendPushNotification(partyId, challengeId, "Approve transaction")

        coVerify(exactly = 1) { sender.sendPushNotification(partyId, challengeId, "Approve transaction") }
    }
}
