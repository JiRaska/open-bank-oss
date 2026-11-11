// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.client

import com.openbank.kyb.application.port.out.RelatedPartyScreeningRequest
import com.openbank.kyb.application.port.out.RelatedPartyScreeningUnavailableException
import com.openbank.kyb.domain.model.ScreeningVerdict
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * How a sanctions-service answer is READ. The cases that matter are the ones where a careless
 * reading would clear someone: an unknown status, a failure, and a provider that predates the
 * screening profile and so reports a PEP name as a plain match.
 */
class SanctionsRelatedPartyScreeningAdapterTest {

    private val client = mockk<SanctionsScreeningRestClient>()
    private val adapter = SanctionsRelatedPartyScreeningAdapter(client).also { it.self = it }

    private val person = RelatedPartyScreeningRequest("Jana Nováková", "kyb-case:c:k", false, "kyb-screen:c:k")

    private fun answers(response: SanctionsScreenResponse) {
        every { client.screen(any()) } returns Uni.createFrom().item(response)
    }

    private fun screen(request: RelatedPartyScreeningRequest = person) = runBlocking { adapter.screen(request) }

    @Test
    fun `the request asks for the CUSTOMER profile and carries the subject and the idempotency key`() {
        val body = slot<SanctionsScreenBody>()
        every { client.screen(capture(body)) } returns Uni.createFrom().item(SanctionsScreenResponse(status = "CLEAR"))

        screen()
        assertThat(body.captured.profile).isEqualTo("CUSTOMER")
        assertThat(body.captured.entityType).isEqualTo("INDIVIDUAL")
        assertThat(body.captured.name).isEqualTo("Jana Nováková")
        assertThat(body.captured.subjectId).isEqualTo("kyb-case:c:k")
        assertThat(body.captured.idempotencyKey).isEqualTo("kyb-screen:c:k")

        screen(person.copy(name = "Příklad s.r.o.", organisation = true))
        assertThat(body.captured.entityType).isEqualTo("ORGANIZATION")
    }

    @Test
    fun `CLEAR and an analyst's WHITELISTED are clear, HIT is a hit`() {
        answers(SanctionsScreenResponse(status = "CLEAR", pepStatus = "CLEAR"))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.CLEAR)
        assertThat(screen().pep).isFalse()
        answers(SanctionsScreenResponse(status = "WHITELISTED"))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.CLEAR)
        answers(SanctionsScreenResponse(status = "HIT", matches = listOf(SanctionsScreenMatch("EU", "SANCTIONS", "NOVAKOVA J"))))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.HIT)
        assertThat(screen().matchedName).isEqualTo("NOVAKOVA J")
    }

    @Test
    fun `an unknown or missing status is a potential hit, never clear`() {
        answers(SanctionsScreenResponse(status = null))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
        answers(SanctionsScreenResponse(status = "SOMETHING_NEW"))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
        answers(SanctionsScreenResponse(status = "POTENTIAL_HIT"))
        assertThat(screen().sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
    }

    @Test
    fun `a PEP reported apart is a PEP fact and leaves the sanctions outcome clear`() {
        answers(
            SanctionsScreenResponse(
                status = "CLEAR",
                pepStatus = "HIT",
                matches = listOf(SanctionsScreenMatch("PEP_GLOBAL", "PEP", "NOVAKOVA Jana")),
            ),
        )
        val result = screen()
        assertThat(result.sanctions).isEqualTo(ScreeningVerdict.CLEAR)
        assertThat(result.pep).isTrue()
        assertThat(result.matchedName).isEqualTo("NOVAKOVA Jana")
    }

    @Test
    fun `a provider that does not know the profile reports the PEP as a plain match - that reviews`() {
        // No pepStatus, no matchKind: the PEP name was counted in `status`. It must not be read
        // as "sanctions clear, PEP only" — the provider never said so.
        answers(
            SanctionsScreenResponse(
                status = "POTENTIAL_HIT",
                pepStatus = null,
                matches = listOf(SanctionsScreenMatch("PEP_GLOBAL", null, "NOVAKOVA Jana")),
            ),
        )
        val result = screen()
        assertThat(result.sanctions).isEqualTo(ScreeningVerdict.POTENTIAL_HIT)
        assertThat(result.pep).isTrue()
    }

    @Test
    fun `an unknown PEP status counts as a PEP`() {
        answers(SanctionsScreenResponse(status = "CLEAR", pepStatus = "UNDER_REVIEW"))
        assertThat(screen().pep).isTrue()
    }

    @Test
    fun `any failure is 'unavailable' - it never becomes a result`() {
        every { client.screen(any()) } returns Uni.createFrom().failure(IllegalStateException("503"))
        assertThatThrownBy { screen() }.isInstanceOf(RelatedPartyScreeningUnavailableException::class.java)
        every { client.screen(any()) } throws IllegalStateException("connection refused")
        assertThatThrownBy { screen() }.isInstanceOf(RelatedPartyScreeningUnavailableException::class.java)
    }
}
