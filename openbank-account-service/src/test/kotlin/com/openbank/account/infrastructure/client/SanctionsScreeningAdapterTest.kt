// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The adapter must never manufacture a passed screen. It used to map a response with no `status`
 * to "CLEAR", so a renamed or dropped field on sanctions-service opened every account unscreened.
 */
class SanctionsScreeningAdapterTest {

    private val client = mockk<SanctionsServiceClient>()
    private val adapter = SanctionsScreeningAdapter().also { it.client = client }

    private fun answer(response: SanctionsScreenResponse) {
        every { client.screen(any()) } returns Uni.createFrom().item(response)
    }

    @Test
    fun `a response with no status is UNKNOWN and does not permit opening`() {
        answer(SanctionsScreenResponse(status = null, overallScore = null))

        val result = runBlocking { adapter.screen("Jan Novak", "key-1") }

        assertThat(result.status).isEqualTo("UNKNOWN")
        assertThat(result.permitsOpening).isFalse()
    }

    @Test
    fun `sanctions-service's own statuses pass through, and only CLEAR and WHITELISTED permit opening`() {
        val permitted = listOf("CLEAR", "HIT", "POTENTIAL_HIT", "WHITELISTED", "ESCALATED").associateWith { status ->
            answer(SanctionsScreenResponse(status = status, overallScore = 0.7))
            runBlocking { adapter.screen("Jan Novak", "key-$status") }.permitsOpening
        }

        assertThat(permitted).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "CLEAR" to true,
                "WHITELISTED" to true,
                "HIT" to false,
                "POTENTIAL_HIT" to false,
                "ESCALATED" to false,
            ),
        )
    }

    @Test
    fun `a lower-case status is normalised before the allow-list reads it`() {
        answer(SanctionsScreenResponse(status = "clear", overallScore = 0.0))

        assertThat(runBlocking { adapter.screen("Jan Novak", "key-lc") }.permitsOpening).isTrue()
    }
}
