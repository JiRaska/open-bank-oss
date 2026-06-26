// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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

    @Test
    fun `sendSmsOtp delegates to NotificationSender`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { sender.sendSmsOtp(partyId, any()) } returns Unit

        guard.sendSmsOtp(partyId, "123456")

        coVerify(exactly = 1) { sender.sendSmsOtp(partyId, "123456") }
    }
}
