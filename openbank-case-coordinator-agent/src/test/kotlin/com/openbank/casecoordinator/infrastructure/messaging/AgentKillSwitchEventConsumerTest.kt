// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.casecoordinator.application.CaseKillSwitchCancellationService
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test

class AgentKillSwitchEventConsumerTest {
    private val cancellation = mockk<CaseKillSwitchCancellationService>(relaxed = true)
    private val consumer = AgentKillSwitchEventConsumer(jacksonObjectMapper(), cancellation)

    @Test
    fun `unrelated audit event is acknowledged without touching cancellation`() = kotlinx.coroutines.runBlocking {
        consumer.consume(Message.of("""{"eventType":"agent.chat","eventId":"1"}"""))

        verify(exactly = 0) { cancellation.handle(any()) }
    }
}
