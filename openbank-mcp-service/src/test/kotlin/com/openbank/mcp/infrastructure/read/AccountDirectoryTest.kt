// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.infrastructure.read

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.clients.account.AccountHttpException
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The read ports' account-service seam (#13169): found / 404 / every other failure, and that nothing is
 * swallowed on the way (a cancelled call must stay cancelled, not become "no such account").
 */
class AccountDirectoryTest {

    private val mapper = jacksonObjectMapper()
    private val accountId = UUID.fromString("00000000-0000-0000-0000-0000000000d3")

    private fun directoryAnswering(call: () -> Uni<Response>): AccountDirectory {
        val client = mockk<AccountServiceClient>()
        every { client.getAccountByIban(any()) } answers { call() }
        every { client.getAccount(any()) } answers { call() }
        return AccountDirectory(client)
    }

    @Test
    fun `a found account is returned typed, with the id parsed as a UUID whatever its case`() {
        val body = mapper.createObjectNode().put("id", accountId.toString().uppercase()).put("accountNumber", "CZ01")
        val directory = directoryAnswering { Uni.createFrom().item(accountResponse(body)) }

        assertThat(directory.idByIban("CZ01")).isEqualTo(accountId)
        assertThat(directory.byId(accountId).accountNumber).isEqualTo("CZ01")
    }

    @Test
    fun `a 404 is the not-found a denied tool call used to get, never an empty account`() {
        val directory = directoryAnswering { Uni.createFrom().item(accountResponse(null)) }

        assertThatThrownBy { directory.byIban("CZ01") }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { directory.byId(accountId) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `a non-404 error status is thrown with its status, not read as not-found`() {
        val directory = directoryAnswering { Uni.createFrom().item(Response.status(503).build()) }

        assertThatThrownBy { directory.byIban("CZ01") }
            .isInstanceOf(AccountHttpException::class.java)
            .extracting { (it as AccountHttpException).status }.isEqualTo(503)
    }

    @Test
    fun `a failed call is thrown unchanged`() {
        val boom = IllegalStateException("connection reset")
        val directory = directoryAnswering { Uni.createFrom().failure(boom) }

        assertThatThrownBy { directory.byIban("CZ01") }.isSameAs(boom)
    }

    @Test
    fun `a cancellation is rethrown, not turned into a not-found or a failure`() {
        val cancelled = CancellationException("cancelled")
        val directory = directoryAnswering { Uni.createFrom().failure(cancelled) }

        assertThatThrownBy { directory.byIban("CZ01") }.isSameAs(cancelled)
        assertThatThrownBy { directory.byId(accountId) }.isSameAs(cancelled)
    }

    @Test
    fun `an account answered without an id is a malformed answer, not an id`() {
        val body = mapper.createObjectNode().put("accountNumber", "CZ01")
        val directory = directoryAnswering { Uni.createFrom().item(accountResponse(body)) }

        assertThatThrownBy { directory.idByIban("CZ01") }.isInstanceOf(IllegalStateException::class.java)
    }
}
