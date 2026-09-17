// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.infrastructure

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AmlCaseSourceStatusTest {
    private val client = mockk<AmlCaseSourceClient>()
    private val source = AmlCaseSourceStatus(client)
    private val id = UUID.fromString("00000000-0000-4000-8000-000000000001")

    @Test
    fun `open review and escalated source cases permit investigation`(): Unit = runBlocking {
        listOf("OPEN", "UNDER_REVIEW", "ESCALATED").forEach { status ->
            every { client.getCase(id) } returns Uni.createFrom().item(AmlCaseSourceSnapshot(id, status))
            assertThat(source.isOpen(id)).isTrue()
        }
    }

    @Test
    fun `terminal source cases do not permit investigation`(): Unit = runBlocking {
        listOf("CLEARED", "BLOCKED").forEach { status ->
            every { client.getCase(id) } returns Uni.createFrom().item(AmlCaseSourceSnapshot(id, status))
            assertThat(source.isOpen(id)).isFalse()
        }
    }

    @Test
    fun `missing source case denies investigation`(): Unit = runBlocking {
        every { client.getCase(id) } returns Uni.createFrom().failure(WebApplicationException(404))
        assertThat(source.isOpen(id)).isFalse()
    }

    @Test
    fun `missing unknown or mismatched source fields are unavailable`() {
        listOf(
            AmlCaseSourceSnapshot(),
            AmlCaseSourceSnapshot(id, "UNRECOGNIZED"),
            AmlCaseSourceSnapshot(UUID.fromString("00000000-0000-4000-8000-000000000002"), "OPEN"),
        ).forEach { value ->
            every { client.getCase(id) } returns Uni.createFrom().item(value)
            assertThatThrownBy { runBlocking { source.isOpen(id) } }.isInstanceOf(AmlCaseSourceUnavailable::class.java)
        }
    }

    @Test
    fun `authorization and source outages are unavailable rather than permission`() {
        listOf(401, 503).forEach { status ->
            every { client.getCase(id) } returns Uni.createFrom().failure(WebApplicationException(status))
            assertThatThrownBy { runBlocking { source.isOpen(id) } }.isInstanceOf(AmlCaseSourceUnavailable::class.java)
        }
    }

    @Test
    fun `unresponsive source times out without permission`() {
        every { client.getCase(id) } returns Uni.createFrom().nothing()
        assertThatThrownBy { runBlocking { source.isOpen(id) } }.isInstanceOf(AmlCaseSourceUnavailable::class.java)
    }
}
