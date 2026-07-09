// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.client

import com.openbank.kyc.application.port.out.PepScreeningStatus
import com.openbank.kyc.application.port.out.PepScreeningUnavailableException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [SanctionsScreeningAdapter] tests. "Andrej Babiš" / "Miloš Zeman" mirror real fixture rows
 * seeded in openbank-sanctions-service's V6 migration for the PEP_GLOBAL list type (sourced from
 * the live OpenSanctions PEP dataset the service imports in production) — these are exactly the
 * kind of names the real screen call would return a HIT for.
 */
class SanctionsScreeningAdapterTest {

    private val client: SanctionsServiceClient = mockk()

    private fun adapter(): SanctionsScreeningAdapter = SanctionsScreeningAdapter(client).also { it.self = it }

    private fun stub(response: ScreenResponse) {
        every { client.screen(any()) } returns Uni.createFrom().item(response)
    }

    @Test
    fun `every screen call is scoped to the PEP_GLOBAL list type only`(): Unit = runBlocking {
        val captured = slot<ScreenRequest>()
        every { client.screen(capture(captured)) } returns Uni.createFrom().item(ScreenResponse(status = "CLEAR"))

        adapter().screenForPep("Jane Clean", "idem-1")

        assertThat(captured.captured.listTypes).containsExactly("PEP_GLOBAL")
        assertThat(captured.captured.name).isEqualTo("Jane Clean")
        assertThat(captured.captured.idempotencyKey).isEqualTo("idem-1")
    }

    @Test
    fun `a known PEP name maps HIT to MATCH and carries the matched name and score`(): Unit = runBlocking {
        stub(
            ScreenResponse(
                status = "HIT",
                overallScore = 0.97,
                matches = listOf(ScreenMatch(matchedName = "Andrej Babiš", matchScore = 0.97)),
            ),
        )

        val result = adapter().screenForPep("Andrej Babis", "idem-pep-1")

        assertThat(result.status).isEqualTo(PepScreeningStatus.MATCH)
        assertThat(result.matchScore).isEqualTo(0.97)
        assertThat(result.matchedName).isEqualTo("Andrej Babiš")
    }

    @Test
    fun `a clean name maps CLEAR to CLEAR`(): Unit = runBlocking {
        stub(ScreenResponse(status = "CLEAR", overallScore = 0.05, matches = emptyList()))

        val result = adapter().screenForPep("Some Ordinary Person", "idem-clean-1")

        assertThat(result.status).isEqualTo(PepScreeningStatus.CLEAR)
        assertThat(result.matchedName).isNull()
    }

    @Test
    fun `POTENTIAL_HIT maps to POTENTIAL_MATCH`(): Unit = runBlocking {
        stub(
            ScreenResponse(
                status = "POTENTIAL_HIT",
                overallScore = 0.7,
                matches = listOf(ScreenMatch(matchedName = "Miloš Zeman", matchScore = 0.7)),
            ),
        )

        val result = adapter().screenForPep("Milos Zeman-like", "idem-potential-1")

        assertThat(result.status).isEqualTo(PepScreeningStatus.POTENTIAL_MATCH)
    }

    @Test
    fun `WHITELISTED maps to CLEAR (an operator has already cleared this specific match)`(): Unit = runBlocking {
        stub(ScreenResponse(status = "WHITELISTED", overallScore = 0.9))

        val result = adapter().screenForPep("Known False Positive", "idem-wl-1")

        assertThat(result.status).isEqualTo(PepScreeningStatus.CLEAR)
    }

    @Test
    fun `an unknown or null remote status fails safe to POTENTIAL_MATCH, never a silent CLEAR`(): Unit = runBlocking {
        stub(ScreenResponse(status = null))

        val result = adapter().screenForPep("Ambiguous Name", "idem-null-1")

        assertThat(result.status).isEqualTo(PepScreeningStatus.POTENTIAL_MATCH)
    }

    @Test
    fun `null overall score defaults to zero`(): Unit = runBlocking {
        stub(ScreenResponse(status = "CLEAR", overallScore = null))

        val result = adapter().screenForPep("Name", "idem-z")

        assertThat(result.matchScore).isEqualTo(0.0)
    }

    @Test
    fun `a transport fault is mapped to PepScreeningUnavailableException, not thrown raw`(): Unit = runBlocking {
        every { client.screen(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThatThrownBy {
            runBlocking { adapter().screenForPep("Name", "idem-f") }
        }.isInstanceOf(PepScreeningUnavailableException::class.java)
    }
}
