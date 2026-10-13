// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.copilot.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.copilot.application.port.out.ProposalTokenStore
import com.openbank.copilot.application.port.out.ToolPolicyDecision
import com.openbank.copilot.application.port.out.ToolPolicyPort
import com.openbank.copilot.domain.ProposalToken
import com.openbank.copilot.infrastructure.authz.OpaToolGate
import com.openbank.copilot.infrastructure.persistence.InMemoryProposalTokenStore
import com.openbank.copilot.infrastructure.persistence.RedisProposalTokenStore
import com.openbank.copilot.infrastructure.rest.ActionConfirmResource
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ProposalLogPrivacyTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val token = ProposalToken(
        UUID.randomUUID(),
        "private-tool-marker\r\nFORGED",
        emptyMap(),
        now.plusSeconds(60),
        "private-customer-marker",
    )

    @Test
    fun `memory store logs correlate without exposing proposal fields`() {
        capture(InMemoryProposalTokenStore::class.java) { records ->
            val store = InMemoryProposalTokenStore(Clock.fixed(now, ZoneOffset.UTC))
            store.save(token)
            assertThat(store.find(token.id)).isEqualTo(token)
            store.delete(token.id)
            assertPrivate(records)
            assertThat(text(records)).contains(ProposalToken.logRef(token.id))
        }
    }

    @Test
    fun `redis store logs omit proposal fields and deserialization payloads`() {
        val redis = mockk<ReactiveRedisDataSource>()
        val values = mockk<ReactiveValueCommands<String, String>>()
        every { redis.value(String::class.java) } returns values
        every { values.set(any(), any(), any()) } returns Uni.createFrom().voidItem()
        every { values.get(any()) } returns Uni.createFrom().item("private-json-marker")
        val mapper = mockk<ObjectMapper>()
        every { mapper.writeValueAsString(token) } returns "{}"
        every { mapper.readValue(any<String>(), ProposalToken::class.java) } throws
            IllegalArgumentException("private-json-marker")
        capture(RedisProposalTokenStore::class.java) { records ->
            val store = RedisProposalTokenStore(redis, mapper)
            store.save(token)
            assertThat(store.find(token.id)).isNull()
            assertPrivate(records)
        }
    }

    @Test
    fun `confirmation logs omit identities tool names and policy reasons`() {
        val store = mockk<ProposalTokenStore>(relaxed = true)
        val gate = mockk<ToolPolicyPort>()
        val jwt = mockk<JsonWebToken>()
        val identity = mockk<SecurityIdentity>()
        every { identity.principal } returns jwt
        every { store.find(token.id) } returns token
        val resource = ActionConfirmResource().also {
            it.tokenStore = store
            it.opaGate = gate
            it.identity = identity
            it.clock = Clock.fixed(now, ZoneOffset.UTC)
        }
        capture(ActionConfirmResource::class.java) { records ->
            every { jwt.subject } returns "private-attacker-marker"
            assertThat(resource.confirm(token.id.toString()).status).isEqualTo(403)
            every { jwt.subject } returns token.customerId
            every { gate.authorize(token.toolName, token.customerId, null) } returns
                ToolPolicyDecision.denied("private-reason-marker")
            assertThat(resource.confirm(token.id.toString()).status).isEqualTo(403)
            every { gate.authorize(token.toolName, token.customerId, null) } returns ToolPolicyDecision.ALLOWED
            assertThat(resource.confirm(token.id.toString()).status).isEqualTo(200)
            assertPrivate(records)
        }
    }

    @Test
    fun `OPA denial and error logs omit request values`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = """{"result":false}""".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val gate =
                OpaToolGate(Optional.of("http://127.0.0.1:${server.address.port}"), "http://unused", ObjectMapper())
            capture(OpaToolGate::class.java) { records ->
                assertThat(gate.authorize(token.toolName, token.customerId).allow).isFalse()
                status = 503
                assertThat(gate.authorize(token.toolName, token.customerId).allow).isFalse()
                server.stop(0)
                val unreachable = gate.authorize(token.toolName, token.customerId, "private-json-marker")
                assertThat(unreachable.allow).isFalse()
                assertThat(records.last().message).isEqualTo("OpaToolGate: sidecar unreachable")
                assertThat(records.last().thrown).isNull()
                assertPrivate(records)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun assertPrivate(records: List<LogRecord>) {
        assertThat(records).isNotEmpty()
        assertThat(text(records)).doesNotContain(
            "private-customer-marker",
            "private-tool-marker",
            "private-attacker-marker",
            "private-reason-marker",
            "private-json-marker",
            "FORGED",
            token.id.toString(),
        )
    }

    private fun text(records: List<LogRecord>): String = records.joinToString("\n") { record ->
        val args = record.parameters
        val message = if (args.isNullOrEmpty()) record.message else String.format(record.message, *args)
        message + (record.thrown?.stackTraceToString() ?: "")
    }

    private fun capture(type: Class<*>, test: (List<LogRecord>) -> Unit) {
        val logger = Logger.getLogger(type.name)
        val previousLevel = logger.level
        val previousParent = logger.useParentHandlers
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.level = Level.ALL
        logger.useParentHandlers = false
        logger.addHandler(handler)
        try {
            test(records)
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousParent
        }
    }
}
