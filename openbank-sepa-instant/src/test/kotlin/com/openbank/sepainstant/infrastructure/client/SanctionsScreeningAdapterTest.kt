// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.client

import com.openbank.sepainstant.application.port.out.ScreeningUnavailableException
import com.openbank.sepainstant.domain.screening.ScreeningMatchStatus
import com.openbank.sepainstant.domain.screening.ScreeningRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for the fail-closed sanctions adapter (ADR-0032 §C/§D). The [SanctionsScreeningAdapter.self]
 * proxy is wired to the adapter itself so the resilient path runs without a CDI container — the
 * fault-tolerance interceptors are exercised in the integration suite, not here.
 */
class SanctionsScreeningAdapterTest {

    private val client = mockk<SanctionsServiceClient>()
    private val adapter = SanctionsScreeningAdapter(client).also { it.self = it }

    private fun response(
        status: String? = "CLEAR",
        overallScore: Double? = 0.0,
        matches: List<ScreenMatch> = emptyList(),
    ) = ScreenResponse(status = status, overallScore = overallScore, matches = matches)

    @Test
    fun `a CLEAR response maps to a CLEAR result carrying subject and role`() {
        every { client.screen(any()) } returns Uni.createFrom().item(response(status = "CLEAR"))

        val result = adapter.screen("Alice Debtor", ScreeningRole.DEBTOR, "pay-1:debtor").await().indefinitely()

        assertThat(result.subject).isEqualTo("Alice Debtor")
        assertThat(result.role).isEqualTo(ScreeningRole.DEBTOR)
        assertThat(result.status).isEqualTo(ScreeningMatchStatus.CLEAR)
        assertThat(result.score).isEqualTo(0.0)
        assertThat(result.matchedEntity).isNull()
    }

    @Test
    fun `a HIT response maps score and the first matched entity`() {
        every { client.screen(any()) } returns Uni.createFrom().item(
            response(
                status = "HIT",
                overallScore = 0.97,
                matches = listOf(
                    ScreenMatch(matchedName = "BOB CREDITOR / OFAC", matchScore = 0.97),
                    ScreenMatch(matchedName = "SECOND MATCH", matchScore = 0.55),
                ),
            ),
        )

        val result = adapter.screen("Bob Creditor", ScreeningRole.CREDITOR, "pay-1:creditor").await().indefinitely()

        assertThat(result.status).isEqualTo(ScreeningMatchStatus.HIT)
        assertThat(result.score).isEqualTo(0.97)
        assertThat(result.matchedEntity).isEqualTo("BOB CREDITOR / OFAC")
    }

    @Test
    fun `POTENTIAL_HIT and WHITELISTED statuses map onto the local mirror`() {
        every { client.screen(any()) } returns Uni.createFrom().item(response(status = "POTENTIAL_HIT"))
        assertThat(adapter.screen("N", ScreeningRole.DEBTOR, "k1").await().indefinitely().status)
            .isEqualTo(ScreeningMatchStatus.POTENTIAL_HIT)

        every { client.screen(any()) } returns Uni.createFrom().item(response(status = "whitelisted"))
        assertThat(adapter.screen("N", ScreeningRole.DEBTOR, "k2").await().indefinitely().status)
            .isEqualTo(ScreeningMatchStatus.WHITELISTED)
    }

    @Test
    fun `an unknown or missing status is escalated never silently CLEAR`() {
        every { client.screen(any()) } returns Uni.createFrom().item(response(status = "SOMETHING_NEW"))
        assertThat(adapter.screen("N", ScreeningRole.DEBTOR, "k1").await().indefinitely().status)
            .isEqualTo(ScreeningMatchStatus.ESCALATED)

        every { client.screen(any()) } returns Uni.createFrom().item(response(status = null))
        assertThat(adapter.screen("N", ScreeningRole.DEBTOR, "k2").await().indefinitely().status)
            .isEqualTo(ScreeningMatchStatus.ESCALATED)
    }

    @Test
    fun `a missing overall score defaults to zero`() {
        every { client.screen(any()) } returns Uni.createFrom().item(response(status = "CLEAR", overallScore = null))

        val result = adapter.screen("N", ScreeningRole.DEBTOR, "k").await().indefinitely()

        assertThat(result.score).isEqualTo(0.0)
    }

    @Test
    fun `the request carries the idempotency key the name and the INDIVIDUAL entity type`() {
        val requestSlot = slot<ScreenRequest>()
        every { client.screen(capture(requestSlot)) } returns Uni.createFrom().item(response())

        adapter.screen("Alice Debtor", ScreeningRole.DEBTOR, "pay-9:debtor").await().indefinitely()

        assertThat(requestSlot.captured.idempotencyKey).isEqualTo("pay-9:debtor")
        assertThat(requestSlot.captured.name).isEqualTo("Alice Debtor")
        assertThat(requestSlot.captured.entityType).isEqualTo("INDIVIDUAL")
        assertThat(requestSlot.captured.aliases).isEmpty()
    }

    @Test
    fun `a transport failure fails closed as ScreeningUnavailableException`() {
        val boom = RuntimeException("connection refused")
        every { client.screen(any()) } returns Uni.createFrom().failure(boom)

        assertThatThrownBy { adapter.screen("N", ScreeningRole.DEBTOR, "k").await().indefinitely() }
            .isInstanceOf(ScreeningUnavailableException::class.java)
            .hasCause(boom)
    }
}
