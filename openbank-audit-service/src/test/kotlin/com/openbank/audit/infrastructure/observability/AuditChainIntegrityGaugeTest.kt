// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.audit.infrastructure.observability

import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.ChainVerification
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The gauge's job is to make a broken chain LOUD, so the cases that matter are the ones where a
 * naive implementation stays quiet: a broken chain, a verification that throws, and a chain of only
 * pre-V5 rows. Each is checked by reading the registry back, not by trusting the setter.
 *
 * `runBlocking` is fine here and NOT the @Scheduled footgun: this drives a mocked repository, so no
 * reactive Panache call is made and no Vert.x context is needed. The production path is a
 * `suspend fun` (enforced by check-no-runblocking-in-scheduled.py).
 */
class AuditChainIntegrityGaugeTest {

    private fun gauge(repo: AuditRepository): Pair<AuditChainIntegrityGauge, SimpleMeterRegistry> {
        val registry = SimpleMeterRegistry()
        val g = AuditChainIntegrityGauge()
        g.registry = registry
        g.auditRepository = repo
        g.enabled = true
        g.register()
        return g to registry
    }

    private fun SimpleMeterRegistry.gaugeValue(name: String): Double =
        find(name).gauge()?.value() ?: error("gauge $name not registered")

    @Test
    fun `an intact chain reports 1 and the counts it verified`() {
        val repo = mockk<AuditRepository>()
        coEvery { repo.verifyChain(any()) } returns
            ChainVerification(intact = true, checked = 42, unchained = 7, firstBrokenEntryId = null)
        val (g, registry) = gauge(repo)

        runBlocking { g.verify() }

        assertThat(registry.gaugeValue("openbank.audit.chain.intact")).isEqualTo(1.0)
        assertThat(registry.gaugeValue("openbank.audit.chain.entries.checked")).isEqualTo(42.0)
        assertThat(registry.gaugeValue("openbank.audit.chain.entries.unchained")).isEqualTo(7.0)
        assertThat(registry.gaugeValue("openbank.audit.chain.last.verified.timestamp.seconds"))
            .isGreaterThan(0.0)
    }

    @Test
    fun `a broken chain drives the gauge to 0 — the condition AuditChainBroken alerts on`() {
        val repo = mockk<AuditRepository>()
        coEvery { repo.verifyChain(any()) } returns ChainVerification(
            intact = false,
            checked = 11,
            unchained = 0,
            firstBrokenEntryId = UUID.randomUUID(),
        )
        val (g, registry) = gauge(repo)

        runBlocking { g.verify() }

        assertThat(registry.gaugeValue("openbank.audit.chain.intact")).isEqualTo(0.0)
        // The links verified BEFORE the break are still reported: that count is where an incident
        // responder starts the re-walk, so zeroing it would destroy the only cheap lead.
        assertThat(registry.gaugeValue("openbank.audit.chain.entries.checked")).isEqualTo(11.0)
    }

    @Test
    fun `a verification that throws leaves the previous verdict alone rather than crying tamper`() {
        val repo = mockk<AuditRepository>()
        coEvery { repo.verifyChain(any()) } returns
            ChainVerification(intact = true, checked = 5, unchained = 0, firstBrokenEntryId = null)
        val (g, registry) = gauge(repo)
        runBlocking { g.verify() }
        val timestampAfterGoodRun =
            registry.gaugeValue("openbank.audit.chain.last.verified.timestamp.seconds")

        coEvery { repo.verifyChain(any()) } throws IllegalStateException("connection reset")
        runBlocking { g.verify() }

        // Still 1, NOT 0: a DB blip is not evidence of tampering, and a false critical at 03:00
        // teaches everyone to ignore the real one.
        assertThat(registry.gaugeValue("openbank.audit.chain.intact")).isEqualTo(1.0)
        // And the timestamp does NOT advance — which is precisely what makes the failure visible,
        // via AuditChainVerificationStale rather than via a false AuditChainBroken.
        assertThat(registry.gaugeValue("openbank.audit.chain.last.verified.timestamp.seconds"))
            .isEqualTo(timestampAfterGoodRun)
    }

    @Test
    fun `a chain with no verifiable rows reports intact but says so in the unchained count`() {
        val repo = mockk<AuditRepository>()
        coEvery { repo.verifyChain(any()) } returns
            ChainVerification(intact = true, checked = 0, unchained = 900, firstBrokenEntryId = null)
        val (g, registry) = gauge(repo)

        runBlocking { g.verify() }

        // verifyChain() legitimately calls this intact — it found no bad link, because it found no
        // link at all. Without the unchained gauge the dashboard would read a perfect green over an
        // audit log that proves nothing, so this pairing is the assertion.
        assertThat(registry.gaugeValue("openbank.audit.chain.intact")).isEqualTo(1.0)
        assertThat(registry.gaugeValue("openbank.audit.chain.entries.checked")).isEqualTo(0.0)
        assertThat(registry.gaugeValue("openbank.audit.chain.entries.unchained")).isEqualTo(900.0)
    }

    @Test
    fun `disabled means no verification is attempted at all`() {
        val repo = mockk<AuditRepository>()
        coEvery { repo.verifyChain(any()) } returns
            ChainVerification(intact = false, checked = 1, unchained = 0, firstBrokenEntryId = null)
        val (g, registry) = gauge(repo)
        g.enabled = false

        runBlocking { g.verify() }

        // Gauges stay at their registered zero and, critically, the timestamp stays 0 — so a
        // deployment that switches this off is caught by AuditChainNeverVerified rather than
        // reporting a confident, permanent "intact".
        assertThat(registry.gaugeValue("openbank.audit.chain.last.verified.timestamp.seconds"))
            .isEqualTo(0.0)
    }
}
