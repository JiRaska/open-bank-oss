// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class CustomerNotificationPagingContractTest {
    @Test
    fun `page reaches notification service with the authenticated party and bounded size`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val requestedUrl = slot<String>()
        every { upstream.get(capture(requestedUrl), party.toString()) } returns
            Response.ok("""{"items":[],"total":0,"page":3,"size":100,"unreadCount":0}""").build()
        val resource = CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            inMemoryPaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            jwt = mockk {
                every { getClaim<String>("party_id") } returns party.toString()
                every { subject } returns party.toString()
            }
            objectMapper = ObjectMapper()
            notificationServiceUrl = "http://notifications"
        }

        val response = resource.listNotifications(1_000, 3)

        assertThat(response.status).isEqualTo(200)
        assertThat(requestedUrl.captured).isEqualTo(
            "http://notifications/api/v1/notifications?partyId=$party&page=3&size=100",
        )
        verify(exactly = 1) { upstream.get(any(), party.toString()) }
    }
}
