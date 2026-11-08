// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationReport
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class LedgerCloseParticipationSinkTest {
    private val client = mockk<LedgerCloseParticipationClient>()
    private val request = slot<ParticipationReportRequest>()
    private val sink = LedgerCloseParticipationSink(client)
    private val mapper = ObjectMapper()
    private val date = LocalDate.of(2026, 10, 1)

    @Test
    fun `sink preserves exercised interest jobs in the outbound JSON body`() {
        every { client.report(any(), any(), capture(request)) } returns
            Uni.createFrom().item(emptyMap<String, Any?>())

        sink.deliver(
            "interest",
            CloseParticipationReport(
                businessDate = date,
                outcome = CloseOutcome.RAN,
                journalsPosted = 2,
                itemsProcessed = 3,
                exercisedJobs = setOf("accrual", "capitalization"),
            ),
        ).await().indefinitely()

        verify(exactly = 1) { client.report("2026-10-01", "interest", any()) }
        val json = mapper.readTree(mapper.writeValueAsBytes(request.captured))
        assertThat(json.path("outcome").asText()).isEqualTo("RAN")
        assertThat(json.path("journalsPosted").asInt()).isEqualTo(2)
        assertThat(json.path("itemsProcessed").asInt()).isEqualTo(3)
        assertThat(json.path("exercisedJobs").isArray).isTrue()
        assertThat(json.path("exercisedJobs").map { it.asText() })
            .containsExactlyInAnyOrder("accrual", "capitalization")
    }

    @Test
    fun `legacy report serializes an empty job array without inventing cycle evidence`() {
        every { client.report(any(), any(), capture(request)) } returns
            Uni.createFrom().item(emptyMap<String, Any?>())

        sink.deliver("interest", CloseParticipationReport(date, CloseOutcome.RAN))
            .await().indefinitely()

        verify(exactly = 1) { client.report("2026-10-01", "interest", any()) }
        val json = mapper.readTree(mapper.writeValueAsBytes(request.captured))
        assertThat(json.path("exercisedJobs").isArray).isTrue()
        assertThat(json.path("exercisedJobs").size()).isZero()
    }
}
