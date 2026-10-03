// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.EgressDeniedException
import com.openbank.libs.security.EgressResolver
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** ADR-0320 P1: GitHub egress is allow-listed — the listed host is reached, any other is refused unsent. */
class GitHubMetricsAdapterEgressTest {
    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val bytes = "{}".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun adapter(host: String) = GitHubMetricsAdapter(
        mockk<com.openbank.devops.infrastructure.config.DevOpsConfig> {
            every { githubApiUrl() } returns "http://$host:${server.address.port}"
            every { githubOwner() } returns "o"
            every { githubRepo() } returns "r"
        },
    ).also {
        it.objectMapper = ObjectMapper()
        it.allowedHosts = listOf("stub.test:${server.address.port};http;private")
        it.resolver = EgressResolver { listOf(InetAddress.getLoopbackAddress()) }
    }

    @Test
    fun `an allow-listed GitHub API host is reached`() {
        assertThat(runBlocking { adapter("stub.test").get("/issues") }).isEqualTo("{}")
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `a GitHub API host that is not allow-listed is refused and nothing is sent`() {
        assertThatThrownBy { runBlocking { adapter("evil.test").get("/issues") } }
            .isInstanceOf(EgressDeniedException::class.java)
        assertThat(hits.get()).isEqualTo(0)
    }
}
