// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.domain.screening.ScreeningMatchStatus
import com.openbank.domestic.domain.screening.ScreeningRole
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SanctionsScreeningAdapterTest {

    private val client: SanctionsServiceClient = mockk()

    private fun adapter(): SanctionsScreeningAdapter = SanctionsScreeningAdapter(client).also { it.self = it }

    private fun stub(response: ScreenResponse) {
        every { client.screen(any()) } returns Uni.createFrom().item(response)
    }

    @Test
    fun `clear response maps to a CLEAR result with score and matched entity`(): Unit = runBlocking {
        stub(
            ScreenResponse(
                status = "clear",
                overallScore = 0.12,
                matches = listOf(ScreenMatch(matchedName = "Other Name", matchScore = 0.12)),
            ),
        )

        val result = adapter().screen("Jan Novak", ScreeningRole.DEBTOR, "idem-1")

        assertThat(result.subject).isEqualTo("Jan Novak")
        assertThat(result.role).isEqualTo(ScreeningRole.DEBTOR)
        assertThat(result.status).isEqualTo(ScreeningMatchStatus.CLEAR)
        assertThat(result.score).isEqualTo(0.12)
        assertThat(result.matchedEntity).isEqualTo("Other Name")
    }

    @Test
    fun `each known remote status maps to its local match status`(): Unit = runBlocking {
        val cases = mapOf(
            "CLEAR" to ScreeningMatchStatus.CLEAR,
            "POTENTIAL_HIT" to ScreeningMatchStatus.POTENTIAL_HIT,
            "HIT" to ScreeningMatchStatus.HIT,
            "WHITELISTED" to ScreeningMatchStatus.WHITELISTED,
        )

        for ((remote, expected) in cases) {
            stub(ScreenResponse(status = remote))
            val result = adapter().screen("Name", ScreeningRole.CREDITOR, "idem-$remote")
            assertThat(result.status).isEqualTo(expected)
        }
    }

    @Test
    fun `unknown remote status fails safe to ESCALATED`(): Unit = runBlocking {
        stub(ScreenResponse(status = "SOMETHING_WEIRD"))

        val result = adapter().screen("Name", ScreeningRole.DEBTOR, "idem-x")

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `null remote status fails safe to ESCALATED`(): Unit = runBlocking {
        stub(ScreenResponse(status = null))

        val result = adapter().screen("Name", ScreeningRole.DEBTOR, "idem-null")

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `null overall score defaults to zero and empty matches yield no matched entity`(): Unit = runBlocking {
        stub(ScreenResponse(status = "CLEAR", overallScore = null, matches = emptyList()))

        val result = adapter().screen("Name", ScreeningRole.DEBTOR, "idem-z")

        assertThat(result.score).isEqualTo(0.0)
        assertThat(result.matchedEntity).isNull()
    }

    @Test
    fun `a transport fault is mapped to ScreeningUnavailableException (fail closed)`(): Unit = runBlocking {
        every { client.screen(any()) } returns
            Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy {
            runBlocking { adapter().screen("Name", ScreeningRole.DEBTOR, "idem-f") }
        }.isInstanceOf(ScreeningUnavailableException::class.java)
    }
}
