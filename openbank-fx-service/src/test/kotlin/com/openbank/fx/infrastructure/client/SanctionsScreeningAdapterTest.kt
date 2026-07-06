// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.fx.domain.screening.ScreeningMatchStatus
import com.openbank.fx.domain.screening.ScreeningRole
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SanctionsScreeningAdapterTest {

    private val client = mockk<SanctionsServiceClient>()
    private val adapter = SanctionsScreeningAdapter(client).also { it.self = it }

    @Test
    fun `a CLEAR response maps to ScreeningMatchStatus CLEAR`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = "CLEAR", overallScore = 0.0, matches = emptyList()))

        val result = runBlocking { adapter.screen("Alice Example", ScreeningRole.DEBTOR, "idem-1") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.CLEAR)
        assertThat(result.matchedEntity).isNull()
    }

    @Test
    fun `a HIT response carries through the matched entity name`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(
                ScreenResponse(
                    status = "HIT",
                    overallScore = 0.97,
                    matches = listOf(ScreenMatch(matchedName = "OFAC SDN", matchScore = 0.97)),
                ),
            )

        val result = runBlocking { adapter.screen("Bad Actor", ScreeningRole.CREDITOR, "idem-2") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.HIT)
        assertThat(result.matchedEntity).isEqualTo("OFAC SDN")
        assertThat(result.score).isEqualTo(0.97)
        assertThat(result.role).isEqualTo(ScreeningRole.CREDITOR)
    }

    @Test
    fun `an unknown or null remote status is escalated, never silently CLEAR`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = null, overallScore = null, matches = emptyList()))

        val result = runBlocking { adapter.screen("Someone", ScreeningRole.DEBTOR, "idem-3") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `a garbled remote status string is escalated`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = "SOMETHING_ELSE", overallScore = 0.1, matches = emptyList()))

        val result = runBlocking { adapter.screen("Someone", ScreeningRole.DEBTOR, "idem-4") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `a transport failure is mapped to ScreeningUnavailableException, fail-closed`() {
        every { client.screen(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy {
            runBlocking { adapter.screen("Alice Example", ScreeningRole.DEBTOR, "idem-5") }
        }.isInstanceOf(ScreeningUnavailableException::class.java)
    }
}
