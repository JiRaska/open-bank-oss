// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.client

import com.openbank.fx.application.port.out.ScreeningEntityType
import com.openbank.fx.application.port.out.ScreeningSubject
import com.openbank.fx.application.port.out.ScreeningUnavailableException
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningRole
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

    private fun person(name: String) = ScreeningSubject(listOf(name), ScreeningEntityType.INDIVIDUAL)

    /**
     * The kind and the aliases on the wire are the subject's own. Sending a constant kind recorded
     * every company's screen as a natural person's.
     */
    @Test
    fun `the request carries the subject's entity type, primary name and aliases`() {
        val sent = mutableListOf<ScreenRequest>()
        every { client.screen(capture(sent)) } returns
            Uni.createFrom().item(ScreenResponse(status = "CLEAR", overallScore = 0.0, matches = emptyList()))

        runBlocking {
            adapter.screen(
                ScreeningSubject(listOf("Example Holding a.s.", "ExHold"), ScreeningEntityType.ORGANIZATION),
                ScreeningRole.DEBTOR,
                "idem-org",
            )
            adapter.screen(person("Alice Example"), ScreeningRole.DEBTOR, "idem-person")
        }

        assertThat(sent.map { it.entityType }).containsExactly("ORGANIZATION", "INDIVIDUAL")
        assertThat(sent[0].name).isEqualTo("Example Holding a.s.")
        assertThat(sent[0].aliases).containsExactly("ExHold")
        assertThat(sent[1].aliases).isEmpty()
    }

    @Test
    fun `a CLEAR response maps to ScreeningMatchStatus CLEAR`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = "CLEAR", overallScore = 0.0, matches = emptyList()))

        val result = runBlocking { adapter.screen(person("Alice Example"), ScreeningRole.DEBTOR, "idem-1") }

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

        val result = runBlocking { adapter.screen(person("Bad Actor"), ScreeningRole.CREDITOR, "idem-2") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.HIT)
        assertThat(result.matchedEntity).isEqualTo("OFAC SDN")
        assertThat(result.score).isEqualTo(0.97)
        assertThat(result.role).isEqualTo(ScreeningRole.CREDITOR)
    }

    @Test
    fun `an unknown or null remote status is escalated, never silently CLEAR`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = null, overallScore = null, matches = emptyList()))

        val result = runBlocking { adapter.screen(person("Someone"), ScreeningRole.DEBTOR, "idem-3") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `a garbled remote status string is escalated`() {
        every { client.screen(any()) } returns
            Uni.createFrom().item(ScreenResponse(status = "SOMETHING_ELSE", overallScore = 0.1, matches = emptyList()))

        val result = runBlocking { adapter.screen(person("Someone"), ScreeningRole.DEBTOR, "idem-4") }

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `a transport failure is mapped to ScreeningUnavailableException, fail-closed`() {
        every { client.screen(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy {
            runBlocking { adapter.screen(person("Alice Example"), ScreeningRole.DEBTOR, "idem-5") }
        }.isInstanceOf(ScreeningUnavailableException::class.java)
    }
}
