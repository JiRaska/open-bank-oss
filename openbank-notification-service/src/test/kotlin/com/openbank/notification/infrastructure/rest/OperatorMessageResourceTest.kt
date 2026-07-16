// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure.rest

import com.openbank.notification.application.OperatorMessageRejected
import com.openbank.notification.application.OperatorMessageService
import com.openbank.notification.domain.model.OperatorMessageTemplate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [OperatorMessageResource] has exactly one job beyond delegation: map
 * [OperatorMessageRejected] (a caller-shaped error — an undeclared variable) to 400, not let it
 * escape as an unmapped 500. Everything else (the closed schema, rendering, delivery) is
 * [OperatorMessageService]'s concern and is tested there.
 */
class OperatorMessageResourceTest {

    @Test
    fun `a successful compose returns 201 with the new notification id`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val service = mockk<OperatorMessageService> {
            coEvery { compose(any()) } returns id
        }
        val resource = OperatorMessageResource().apply { this.service = service }

        val response = resource.compose(
            ComposeMessageRequest(
                partyId = UUID.randomUUID(),
                template = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
                recipient = "customer@example.com",
                variables = mapOf("ticketReference" to "TCK-123"),
            ),
        )

        assertThat(response.status).isEqualTo(201)
        assertThat((response.entity as Map<*, *>)["id"]).isEqualTo(id)
    }

    @Test
    fun `an undeclared variable is rejected as 400, not an unmapped 500`(): Unit = runBlocking {
        val service = mockk<OperatorMessageService> {
            coEvery { compose(any()) } throws
                OperatorMessageRejected(
                    "template SUPPORT_FOLLOWUP declares [ticketReference] but request carried undeclared [note]",
                )
        }
        val resource = OperatorMessageResource().apply { this.service = service }

        val response = resource.compose(
            ComposeMessageRequest(
                partyId = UUID.randomUUID(),
                template = OperatorMessageTemplate.SUPPORT_FOLLOWUP,
                recipient = "customer@example.com",
                variables = mapOf("note" to "smuggled"),
            ),
        )

        assertThat(response.status).isEqualTo(400)
        assertThat((response.entity as Map<*, *>)["code"]).isEqualTo("BAD_REQUEST")
    }
}
