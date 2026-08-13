// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.SendLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CampaignInteractionQueryTest {
    private val sendLog = mockk<SendLogRepository>()
    private val query = CampaignInteractionQuery(sendLog)

    @Test
    fun `delegates the opaque reference and authoritative party to the send log`() = runBlocking {
        val interactionRef = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        coEvery { sendLog.hasPushInteractionForParty(interactionRef, partyId) } returns true

        assertThat(query.isValidForParty(interactionRef, partyId)).isTrue()
        coVerify(exactly = 1) { sendLog.hasPushInteractionForParty(interactionRef, partyId) }
    }
}
