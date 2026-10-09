// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.customeredge.infrastructure.rest.ActingForResolver
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.UnifiedNotificationInbox
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jboss.logging.MDC
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        assertThat(body.path("total").asInt()).isEqualTo(2)
        assertThat(body.path("unreadCount").asInt()).isEqualTo(3)
        assertThat(body.path("items").first().path("sentAt").isNull).isTrue()
        assertThat(body.path("items").first().path("readAt").isNull).isTrue()
    }

    @Test
    fun `fractional second sorts after whole second even when limit is one`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString()) } returns page(human, "2026-10-08T10:00:00Z", 1)
        every { upstream.get(any(), company.toString()) } returns page(company, "2026-10-08T10:00:00.500Z", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            .list(listOf(human, company), 1)
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.entity as String).path("items").first().path("partyId").asText())
            .isEqualTo(company.toString())
    }

    @Test
    fun `malformed timestamp fails the whole aggregate closed`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), human.toString()) } returns page(human, "not-an-instant", 1)
        every { upstream.get(any(), company.toString()) } returns page(company, "2026-10-08T10:00:00Z", 1)
        val response = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            .list(listOf(human, company), 1)
        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity.toString()).doesNotContain(company.toString())
    }

    @Test
    fun `slow upstream cannot schedule a large inventory or outlive aggregate deadline`() {
        val calls = AtomicInteger()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } answers {
            calls.incrementAndGet()
            Thread.sleep(5_000)
            page(UUID.fromString(secondArg()), "2026-10-08T10:00:00Z", 1)
        }
        val started = System.nanoTime()
        val response = UnifiedNotificationInbox(
            upstream,
            mapper,
            "http://notifications",
            TimeUnit.MILLISECONDS.toNanos(100),
        ).list(List(200) { UUID.randomUUID() }, 20)
        assertThat(response.status).isEqualTo(502)
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000)
        assertThat(calls.get()).isLessThanOrEqualTo(16)
        val atDeadline = calls.get()
        Thread.sleep(100)
        assertThat(calls.get()).isEqualTo(atDeadline)
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
        every { upstream.get(any(), any(), any()) } answers {
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
    fun `synthetic sequential reads shrink their per-call timeout within one deadline`() {
        val upstream = mockk<UpstreamClient>()
        val budgets = mutableListOf<Long>()
        val elapsedNanos = java.util.concurrent.atomic.AtomicLong()
        every { upstream.get(any(), any(), any()) } answers {
            val budget = thirdArg<Long>()
            budgets += budget
            elapsedNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(minOf(60, budget)))
            if (budget < 60) {
                Response.status(502).build()
            } else {
                page(UUID.fromString(secondArg()), "2026-10-08T09:00:00Z", 1)
            }
        }
        MDC.put("synthetic", "true")
        try {
            val response = UnifiedNotificationInbox(
                upstream,
                mapper,
                "http://notifications",
                TimeUnit.MILLISECONDS.toNanos(100),
                elapsedNanos::get,
            ).list(listOf(human, company, stranger), 20)
            assertThat(response.status).isEqualTo(502)
            assertThat(budgets).hasSize(2)
            assertThat(budgets).containsExactly(100L, 40L)
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
        every { upstream.get(any(), any()) } answers {
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
        val callers = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val inbox = UnifiedNotificationInbox(upstream, mapper, "http://notifications")
            val responses = List(12) { callers.submit<Response> { inbox.list(parties, 20) } }.map { it.get() }
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
        every { upstream.get(any(), company.toString()) } returns page(company, "2026-10-08T09:00:00Z", 1)
        val response = resource(upstream).listUnifiedNotifications(20, company)
        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) { upstream.get(any(), company.toString()) }
        verify(exactly = 0) { upstream.get(any(), human.toString()) }
    }

    @Test
    fun `explicit company origin authorizes detail and mark read independent of selected profile`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every {
            upstream.get("http://notifications/api/v1/notifications/$id/self?partyId=$company", company.toString())
        } returns Response.ok(
            """{"id":"$id","partyId":"$company","body":"message","createdAt":"2026-10-08T10:00:00Z"}""",
        ).build()
        every {
            upstream.patch("http://notifications/api/v1/notifications/$id/read?partyId=$company", company.toString())
        } returns Response.noContent().build()
        val resource = resource(upstream)
        val detail = resource.getNotification(id, company)
        assertThat(detail.status).isEqualTo(200)
        assertThat(mapper.readTree(detail.entity.toString()).path("partyId").asText()).isEqualTo(company.toString())
        assertThat(resource.markNotificationRead(id, company).status).isEqualTo(204)
    }

    @Test
    fun `foreign origin is refused before detail or mark read upstream calls`() {
        val upstream = mockk<UpstreamClient>()
        val resource = resource(upstream)
        val id = UUID.randomUUID()
        assertThatThrownBy { resource.getNotification(id, stranger) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { resource.markNotificationRead(id, stranger) }.isInstanceOf(ForbiddenException::class.java)
        verify(exactly = 0) { upstream.get(match { it.startsWith("http://notifications") }, any()) }
        verify(exactly = 0) { upstream.patch(any(), any()) }
    }

    @Test
    fun `explicit company detail refuses a row belonging to another profile`() {
        val id = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), company.toString()) } returns Response.ok(
            """{"id":"$id","partyId":"$stranger","body":"private"}""",
        ).build()
        val response = resource(upstream).getNotification(id, company)
        assertThat(response.status).isEqualTo(403)
        assertThat(response.entity.toString()).doesNotContain("private")
    }

    @Test
    fun `unavailable mandate inventory refuses explicit company action`() {
        val upstream = mockk<UpstreamClient>()
        val resolver = mockk<ActingForResolver>()
        val resource = resource(upstream, resolver)
        every { resolver.profilesOfStrict(human) } throws ServiceUnavailableException("down")
        assertThatThrownBy { resource.getNotification(UUID.randomUUID(), company) }
            .isInstanceOf(ServiceUnavailableException::class.java)
        verify(exactly = 0) { upstream.get(match { it.startsWith("http://notifications") }, any()) }
    }

    @Test
    fun `published unified response declares origin and count fields`() {
        val spec = ObjectMapper(YAMLFactory())
            .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
        val schema = spec.path("paths").path("/notifications/unified").path("get")
            .path("responses").path("200").path("content").path("application/json").path("schema")
        assertThat(schema.path("required").map { it.asText() })
            .contains("items", "total", "unreadCount", "size", "page")
        assertThat(schema.path("properties").path("items").path("items").path("required").map { it.asText() })
            .contains("id", "partyId", "createdAt")
        val itemProperties = schema.path("properties").path("items").path("items").path("properties")
        for (field in listOf("sentAt", "readAt")) {
            assertThat(itemProperties.path(field).path("type").map { it.asText() }).contains("string", "null")
        }
        for (route in listOf("/notifications/{id}", "/notifications/{id}/read")) {
            val method = if (route.endsWith("read")) "patch" else "get"
            assertThat(spec.path("paths").path(route).path(method).path("parameters").map { it.path("name").asText() })
                .contains("partyId")
        }
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
    fun `mandate inventory upstream read receives remaining aggregate budget`() {
        val upstream = mockk<UpstreamClient>()
        var timeoutMs = Long.MAX_VALUE
        every { upstream.get(any(), human.toString(), any()) } answers {
            timeoutMs = thirdArg()
            Response.status(503).build()
        }
        val resolver = ActingForResolver(upstream, mapper, Clock.systemUTC(), "http://party", true)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
        assertThatThrownBy { resolver.profilesOfStrict(human, deadline) }
            .isInstanceOf(ServiceUnavailableException::class.java)
        assertThat(timeoutMs).isBetween(1, 100)
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
        """{"items":[{"id":"${UUID.randomUUID()}","partyId":"$party","createdAt":"$createdAt","sentAt":null,"readAt":null}],"total":1,"unreadCount":$unread}""",
    ).build()
}
