// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.FraudScoreCommand
import com.openbank.libs.fincrime.FraudVerdict
import com.openbank.libs.fincrime.ScreeningMatchStatus
import com.openbank.libs.fincrime.ScreeningRole
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RailWireVocabularyTest {

    @Test
    fun `sanctions wire mapping retains the untrimmed rail contract`(): Unit = runBlocking {
        val client = mockk<SanctionsServiceClient>()
        val adapter = SanctionsScreeningAdapter(client).also { it.self = it }
        val valid = ScreeningMatchStatus.entries.flatMap { listOf(it.name to it, it.name.lowercase() to it) }
        val padded = ScreeningMatchStatus.entries.flatMap {
            listOf(" ${it.name} ", "\t${it.name}", "${it.name}\n")
        }.map { it to ScreeningMatchStatus.ESCALATED }
        val invalid = listOf(null, "", "UNRECOGNISED").map { it to ScreeningMatchStatus.ESCALATED }
        for ((remote, expected) in valid + padded + invalid) {
            every { client.screen(any()) } returns Uni.createFrom().item(ScreenResponse(status = remote))
            val actual = adapter.screen("Synthetic subject", ScreeningRole.DEBTOR, "wire-contract").status
            assertThat(actual).describedAs("remote=%s", remote).isEqualTo(expected)
        }
    }

    @Test
    fun `fraud wire mapping retains the untrimmed rail contract`(): Unit = runBlocking {
        val client = mockk<FraudScoreClient>()
        val adapter = FraudScoringAdapter(client, mockk(relaxed = true)).also { it.self = it }
        val command = FraudScoreCommand(
            amount = BigDecimal("1.00"),
            currency = "EUR",
            rail = "SYNTHETIC",
            accountId = null,
            counterpartyId = null,
        )
        val valid = FraudVerdict.entries.flatMap { listOf(it.name to it, it.name.lowercase() to it) }
        val padded = FraudVerdict.entries.flatMap {
            listOf(" ${it.name} ", "\t${it.name}", "${it.name}\n")
        }.map { it to FraudVerdict.UNKNOWN }
        val invalid = listOf("", "UNRECOGNISED").map { it to FraudVerdict.UNKNOWN }
        for ((remote, expected) in valid + padded + invalid) {
            every { client.score(any()) } returns Uni.createFrom().item(FraudScoreClientResponse(verdict = remote))
            val actual = adapter.score(command).verdict
            assertThat(actual).describedAs("remote=%s", remote).isEqualTo(expected)
        }
    }
}
