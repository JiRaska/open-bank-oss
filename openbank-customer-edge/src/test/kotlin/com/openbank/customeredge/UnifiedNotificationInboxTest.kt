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
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jboss.logging.MDC
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
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
            upstream.get(
                "http://notifications/api/v1/notifications?partyId=$human&page=0&size=2",
                human.toString(),
                any(),
            )
        } returns
            page(human, "2026-10-08T09:00:00Z", 1)
        every {
            upstream.get(
                "http://notifications/api/v1/notifications?partyId=$company&page=0&size=2",
                company.toString(),
                any(),
            )
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
        assertThat(body.path("total").asInt()).isEqualTo(2)
        assertThat(body.path("unreadCount").asInt()).isEqualTo(3)
    }

    @Test
    fun `fractional second instant outranks older whole second across profiles`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString(), any()) } returns page(human, "2026-10-08T10:00:00Z", 1)
        every { upstream.get(any(), company.toString(), any()) } returns page(company, "2026-10-08T10:00:00.500Z", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            .list(listOf(human, company), 1)
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.entity as String).path("items")[0].path("partyId").asText())
            .isEqualTo(company.toString())
    }

    @Test
    fun `next page sends strict keyset cursor to every authorized profile`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString(), any()) } answers {
            if (firstArg<String>().contains("beforeCreatedAt=")) {
                page(human, "2026-10-08T08:00:00Z", 1)
            } else {
                page(human, "2026-10-08T10:00:00Z", 1)
            }
        }
        every { upstream.get(any(), company.toString(), any()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val inbox = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
        val first = mapper.readTree(inbox.list(listOf(human, company), 1).entity as String)
        val cursor = first.path("nextCursor").asText()
        val second = mapper.readTree(inbox.list(listOf(human, company), 1, cursor).entity as String)
        assertThat(second.path("items")[0].path("createdAt").asText()).isEqualTo("2026-10-08T09:00:00Z")
        verify(exactly = 2) {
            upstream.get(
                match { it.contains("beforeCreatedAt=2026-10-08T10:00:00Z") },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `cursor from one authorized profile set cannot silently page another`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString(), any()) } returns page(human, "2026-10-08T10:00:00Z", 1)
        every { upstream.get(any(), company.toString(), any()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val inbox = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
        val first = mapper.readTree(inbox.list(listOf(human, company), 1).entity as String)
        val cursor = first.path("nextCursor").asText()
        assertThatThrownBy { inbox.list(listOf(human), 1, cursor) }
            .isInstanceOf(BadRequestException::class.java)
        verify(exactly = 1) { upstream.get(any(), human.toString(), any()) }
    }

    @Test
    fun `invalid cursor is refused before any upstream call`() {
        val upstream = mockk<UpstreamClient>()
        assertThatThrownBy {
            UnifiedNotificationInbox(upstream, mapper, "http://notifications").list(listOf(human), 1, "!")
        }.isInstanceOf(BadRequestException::class.java)
        verify(exactly = 0) { upstream.get(any(), any(), any()) }
    }

    @Test
    fun `malformed upstream instant fails the whole inbox closed`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString(), any()) } returns page(human, "invalid", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            .list(listOf(human), 1)
        assertThat(response.status).isEqualTo(502)
    }

    @Test
    fun `slow upstream exceeds aggregate deadline without partial response`() {
        val upstream = mockk<UpstreamClient>()
        val startedReads = java.util.concurrent.atomic.AtomicInteger()
        every { upstream.get(any(), any(), any()) } answers {
            startedReads.incrementAndGet()
            Thread.sleep(500)
            page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
        }
        val started = System.nanoTime()
        val response = UnifiedNotificationInbox(
            upstream,
            mapper,
            "http://notifications",
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(80),
        ).list(List(40) { UUID.randomUUID() }, 1)
        assertThat(response.status).isEqualTo(502)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(400))
        assertThat(startedReads.get()).isLessThanOrEqualTo(16)
    }

    @Test
    fun `one failed party feed returns no partial content`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("partyId=$human") }, human.toString(), any()) } returns
            page(human, "2026-10-08T09:00:00Z", 1)
        every { upstream.get(match { it.contains("partyId=$company") }, company.toString(), any()) } returns
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
        every { upstream.get(any(), human.toString(), any()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications").list(listOf(human), 20)
        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity.toString()).doesNotContain(company.toString())
    }

    @Test
    fun `more than twenty companies still produce a complete inbox`() {
        val companies = List(24) { UUID.randomUUID() }
        val parties = listOf(human) + companies
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any(), any()) } answers {
            val party = UUID.fromString(secondArg<String>())
            page(party, "2026-10-08T09:00:00Z", 1)
        }
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications").list(parties, 20)
        assertThat(response.status).isEqualTo(200)
        val body = mapper.readTree(response.entity as String)
        assertThat(body.path("total").asInt()).isEqualTo(parties.size)
        assertThat(body.path("unreadCount").asInt()).isEqualTo(parties.size)
        assertThat(body.path("items").size()).isEqualTo(20)
        verify(exactly = parties.size) { upstream.get(any(), any(), any()) }
    }

    @Test
    fun `synthetic requests propagate trusted taint into bounded workers without leaking it`() {
        val upstream = mockk<UpstreamClient>()
        val observedThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observedTaint = java.util.Collections.synchronizedList(mutableListOf<Any?>())
        every { upstream.get(any(), any(), any()) } answers {
            observedThreads += Thread.currentThread().name
            observedTaint += MDC.get("synthetic")
            page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
        }
        val inbox = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
        MDC.put("synthetic", "true")
        try {
            val response = inbox.list(listOf(human, company), 20)
            assertThat(response.status).isEqualTo(200)
            assertThat(observedThreads).allMatch { it == "unified-notification-feed" }
            assertThat(observedTaint).containsOnly("true")
        } finally {
            MDC.remove("synthetic")
        }
        assertThat(inbox.list(listOf(human), 20).status).isEqualTo(200)
        assertThat(observedTaint.last()).isNull()
    }

    @Test
    fun `synthetic slow inventory obeys the same aggregate deadline`() {
        val upstream = mockk<UpstreamClient>()
        val startedReads = java.util.concurrent.atomic.AtomicInteger()
        every { upstream.get(any(), any(), any()) } answers {
            startedReads.incrementAndGet()
            Thread.sleep(500)
            page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
        }
        MDC.put("synthetic", "true")
        try {
            val started = System.nanoTime()
            val response = UnifiedNotificationInbox(
                upstream,
                mapper,
                "http://notifications",
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(80),
            ).list(List(40) { UUID.randomUUID() }, 1)
            assertThat(response.status).isEqualTo(502)
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(400))
            assertThat(startedReads.get()).isLessThanOrEqualTo(16)
        } finally {
            MDC.remove("synthetic")
        }
    }

    @Test
    fun `concurrent inbox requests reuse a bounded worker pool`() {
        val parties = List(20) { UUID.randomUUID() }
        val pooledThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any(), any()) } answers {
            if (Thread.currentThread().name == "unified-notification-feed") {
                pooledThreads += Thread.currentThread().threadId()
            }
            peak.accumulateAndGet(active.incrementAndGet()) { previous, current -> maxOf(previous, current) }
            try {
                Thread.sleep(10)
                page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
            } finally {
                active.decrementAndGet()
            }
        }
        val callers = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val inbox = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            val responses = List(6) { callers.submit<Response> { inbox.list(parties, 20) } }.map { it.get() }
            assertThat(responses.map { it.status }).containsOnly(200)
            assertThat(pooledThreads.size).isBetween(1, 16)
            assertThat(peak.get()).isLessThanOrEqualTo(16)
            responses.forEach { response ->
                val body = mapper.readTree(response.entity as String)
                assertThat(body.path("total").asInt()).isEqualTo(parties.size)
                assertThat(body.path("unreadCount").asInt()).isEqualTo(parties.size)
            }
        } finally {
            callers.shutdownNow()
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
        every { upstream.get(any(), company.toString(), any()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val response = resource(upstream).listUnifiedNotifications(20, company)
        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) { upstream.get(any(), company.toString(), any()) }
        verify(exactly = 0) { upstream.get(any(), human.toString(), any()) }
    }

    @Test
    fun `unavailable mandate inventory refuses a misleading personal-only inbox`() {
        val upstream = mockk<UpstreamClient>()
        val resolver = mockk<ActingForResolver>()
        val resource = resource(upstream, resolver)
        every { resolver.profilesOfStrict(human, any()) } throws ServiceUnavailableException("down")
        assertThatThrownBy { resource.listUnifiedNotifications(20, null) }
            .isInstanceOf(ServiceUnavailableException::class.java)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `mandate inventory consumes only remaining aggregate budget`() {
        val upstream = mockk<UpstreamClient>()
        var observedTimeout = Long.MAX_VALUE
        every { upstream.get(any(), human.toString(), any()) } answers {
            observedTimeout = thirdArg()
            Response.status(503).build()
        }
        val resolver = ActingForResolver(upstream, mapper, Clock.systemUTC(), "http://party", true)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)
        assertThatThrownBy { resolver.profilesOfStrict(human, deadline) }
            .isInstanceOf(ServiceUnavailableException::class.java)
        assertThat(observedTimeout).isBetween(1, 100)
    }

    @Test
    fun `authorized company detail includes origin while hiding upstream recipient`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val url = "http://notifications/api/v1/notifications/$id/self?partyId=$company"
        every { upstream.get(url, company.toString()) } returns
            Response.ok("""{"id":"$id","partyId":"$company","recipient":"private","body":"message"}""").build()
        val response = resource(upstream).getNotification(id, company)
        assertThat(response.status).isEqualTo(200)
        val body = response.entity.toString()
        assertThat(body).contains(company.toString()).doesNotContain("recipient", "private")
    }

    @Test
    fun `foreign origin cannot fetch detail or mark notification read`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resource = resource(upstream)
        assertThat(resource.getNotification(id, stranger).status).isEqualTo(403)
        assertThat(resource.markNotificationRead(id, stranger).status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(any(), any()) }
        verify(exactly = 0) { upstream.patch(any(), any()) }
    }

    @Test
    fun `mark read verifies upstream origin before mutation`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val url = "http://notifications/api/v1/notifications/$id/self?partyId=$company"
        every { upstream.get(url, company.toString()) } returns
            Response.ok("""{"id":"$id","partyId":"$human"}""").build()
        val response = resource(upstream).markNotificationRead(id, company)
        assertThat(response.status).isEqualTo(403)
        verify(exactly = 0) { upstream.patch(any(), any()) }
    }

    @Test
    fun `authorized company notification is marked read in its origin profile`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val url = "http://notifications/api/v1/notifications/$id/self?partyId=$company"
        every { upstream.get(url, company.toString()) } returns
            Response.ok("""{"id":"$id","partyId":"$company"}""").build()
        val readUrl = "http://notifications/api/v1/notifications/$id/read?partyId=$company"
        every { upstream.patch(readUrl, company.toString()) } returns
            Response.noContent().build()
        val response = resource(upstream).markNotificationRead(id, company)
        assertThat(response.status).isEqualTo(204)
        verify(exactly = 1) {
            upstream.patch(readUrl, company.toString())
        }
    }

    @Test
    fun `read all remains scoped to authorized origin profile`() {
        val upstream = mockk<UpstreamClient>()
        val url = "http://notifications/api/v1/notifications/read-all?partyId=$company"
        every { upstream.patch(url, company.toString()) } returns Response.ok("""{"marked":1}""").build()
        val resource = resource(upstream)
        assertThat(resource.markAllNotificationsRead(company).status).isEqualTo(200)
        assertThat(resource.markAllNotificationsRead(stranger).status).isEqualTo(403)
        verify(exactly = 1) { upstream.patch(url, company.toString()) }
    }

    private fun resource(upstream: UpstreamClient, resolver: ActingForResolver = mockk()): CustomerEdgeResource {
        every { resolver.resolve(human, null) } returns human
        every { resolver.profilesOfStrict(human) } returns listOf(mapOf("partyId" to company))
        every { resolver.profilesOfStrict(human, any()) } returns listOf(mapOf("partyId" to company))
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
