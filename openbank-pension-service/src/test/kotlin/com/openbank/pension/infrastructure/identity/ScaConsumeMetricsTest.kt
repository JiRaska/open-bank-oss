// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.identity

import com.openbank.pension.application.exit.ScaOperation
import com.openbank.pension.application.onboarding.IntegrationUnavailableException
import com.openbank.pension.infrastructure.observability.MicrometerPensionMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** Each consume answer is counted under exactly one outcome (#12424). */
class ScaConsumeMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerPensionMetrics(registry)
    private val party = UUID.randomUUID()
    private val hash = sha256Hex("document")
    private val binding = ScaBinding.forDocument(ScaOperation.EXIT, hash)

    private fun count(outcome: String) =
        registry.find("openbank.pension.sca.consume").tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `a completed approval of this party counts as consumed`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val gate = ScaConsumeGate(metrics) { _, _ ->
            ScaChallengeDto(id, party, "APPROVAL", "COMPLETED", "2026-10-09T10:00:00Z")
        }
        assertThat(gate.spend(party, id.toString(), binding)).isTrue()
        assertThat(count("consumed")).isEqualTo(1.0)
        assertThat(count("refused") + count("invalid") + count("unavailable")).isZero()
    }

    @Test
    fun `a 409 and a 2xx parked for approval are both refusals`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        ScaConsumeGate(metrics) { _, _ -> throw WebApplicationException(409) }.spend(party, id.toString(), binding)
        ScaConsumeGate(metrics) { _, _ -> ScaChallengeDto(id, party, "APPROVAL", "PENDING_APPROVAL", null) }
            .spend(party, id.toString(), binding)
        assertThat(count("refused")).isEqualTo(2.0)
        assertThat(count("consumed")).isZero()
    }

    @Test
    fun `a malformed challenge id never calls sca-service and counts as invalid`(): Unit = runBlocking {
        var calls = 0
        val gate = ScaConsumeGate(metrics) { _, _ ->
            calls++
            error("must not be called")
        }
        assertThat(gate.spend(party, "not-a-uuid", binding)).isFalse()
        assertThat(gate.spend(party, UUID.randomUUID().toString(), null)).isFalse()
        assertThat(calls).isZero()
        assertThat(count("invalid")).isEqualTo(2.0)
    }

    @Test
    fun `an sca-service that cannot answer counts as unavailable and still fails closed`() {
        val gate = ScaConsumeGate(metrics) { _, _ -> throw WebApplicationException(503) }
        assertThatThrownBy { runBlocking { gate.spend(party, UUID.randomUUID().toString(), binding) } }
            .isInstanceOf(IntegrationUnavailableException::class.java)
        assertThat(count("unavailable")).isEqualTo(1.0)
        assertThat(count("refused")).isZero()
    }
}
