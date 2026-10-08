// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.UnifiedNotificationInbox
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jboss.logging.MDC
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class UnifiedNotificationInboxTest {
    private val mapper = ObjectMapper()
    private val human = UUID.randomUUID()
    private val company = UUID.randomUUID()
    private val stranger = UUID.randomUUID()

    @Test
    fun `merges person and company chronologically with their origin and exact unread count`() {
        val upstream = mockk<UpstreamClient>()
        every {
            upstream.get("http://notifications/api/v1/notifications?partyId=$human&page=0&size=2", human.toString())
        } returns
            page(human, "2026-10-08T09:00:00Z", 1)
        every {
            upstream.get("http://notifications/api/v1/notifications?partyId=$company&page=0&size=2", company.toString())
        } returns
            page(company, "2026-10-08T10:00:00Z", 2)

        val response = UnifiedNotificationInbox(
            upstream,
            mapper,
            "http://notifications",
        ).list(listOf(human, company), 2)
        assertThat(response.status).isEqualTo(200)
        val body = mapper.readTree(response.entity as String)
        assertThat(body.path("items").map { it.path("partyId").asText() })
            .containsExactly(company.toString(), human.toString())
        assertThat(body.path("unreadCount").asInt()).isEqualTo(3)
    }

    @Test
    fun `one failed party feed returns no partial content`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("partyId=$human") }, human.toString()) } returns
            page(human, "2026-10-08T09:00:00Z", 1)
        every { upstream.get(match { it.contains("partyId=$company") }, company.toString()) } returns
            Response.status(503).build()

        val response = UnifiedNotificationInbox(
            upstream,
            mapper,
            "http://notifications",
        ).list(listOf(human, company), 20)
        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity.toString()).doesNotContain(human.toString())
    }

    @Test
    fun `a party-mismatched upstream row is never returned to another profile`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications").list(listOf(human), 20)
        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity.toString()).doesNotContain(company.toString())
    }

    @Test
    fun `more than twenty companies still produce a complete inbox`() {
        val companies = List(24) { UUID.randomUUID() }
        val parties = listOf(human) + companies
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } answers {
            val party = UUID.fromString(secondArg<String>())
            page(party, "2026-10-08T09:00:00Z", 1)
        }
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications").list(parties, 20)
        assertThat(response.status).isEqualTo(200)
        val body = mapper.readTree(response.entity as String)
        assertThat(body.path("total").asInt()).isEqualTo(parties.size)
        assertThat(body.path("unreadCount").asInt()).isEqualTo(parties.size)
        assertThat(body.path("items").size()).isEqualTo(20)
        verify(exactly = parties.size) { upstream.get(any(), any()) }
    }

    @Test
    fun `synthetic requests keep upstream reads on the tainted request thread`() {
        val upstream = mockk<UpstreamClient>()
        val callerThread = Thread.currentThread().threadId()
        val observedThreads = java.util.Collections.synchronizedList(mutableListOf<Long>())
        every { upstream.get(any(), any()) } answers {
            observedThreads += Thread.currentThread().threadId()
            page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
        }
        MDC.put("synthetic", "true")
        try {
            val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
                .list(listOf(human, company), 20)
            assertThat(response.status).isEqualTo(200)
            assertThat(observedThreads).containsExactly(callerThread, callerThread)
        } finally {
            MDC.remove("synthetic")
        }
    }

    @Test
    fun `foreign party filter is refused before any notification read`() {
        val upstream = mockk<UpstreamClient>()
        val resource = resource(upstream)
        val response = resource.listUnifiedNotifications(20, stranger)
        assertThat(response.status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(match { it.startsWith("http://notifications") }, any()) }
    }

    @Test
    fun `authorized company filter reads only that company's feed`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), company.toString()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val response = resource(upstream).listUnifiedNotifications(20, company)
        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) { upstream.get(any(), company.toString()) }
        verify(exactly = 0) { upstream.get(any(), human.toString()) }
    }

    @Test
    fun `unavailable mandate inventory refuses a misleading personal-only inbox`() {
        val upstream = mockk<UpstreamClient>()
        val resolver = mockk<ActingForResolver>()
        val resource = resource(upstream, resolver)
        every { resolver.profilesOfStrict(human) } throws ServiceUnavailableException("down")
        assertThatThrownBy { resource.listUnifiedNotifications(20, null) }
            .isInstanceOf(ServiceUnavailableException::class.java)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    private fun resource(upstream: UpstreamClient, resolver: ActingForResolver = mockk()): CustomerEdgeResource {
        every { resolver.resolve(human, null) } returns human
        every { resolver.profilesOfStrict(human) } returns listOf(mapOf("partyId" to company))
        return CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            inMemoryPaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            actingForResolver = resolver
            jwt = mockk {
                every { getClaim<String>("party_id") } returns human.toString()
                every { subject } returns human.toString()
            }
            objectMapper = mapper
            notificationServiceUrl = "http://notifications"
        }
    }

    private fun page(party: UUID, createdAt: String, unread: Int): Response = Response.ok(
        """{"items":[{"id":"${UUID.randomUUID()}","partyId":"$party","createdAt":"$createdAt"}],"total":1,"unreadCount":$unread}""",
    ).build()
}
