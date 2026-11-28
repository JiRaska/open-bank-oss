// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.infrastructure.scheduler

import com.openbank.sdd.application.port.out.SddMandateRepository
import com.openbank.sdd.domain.model.MandateAmendment
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddMandate
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class MandateExpirySchedulerTest {

    private val mandates = mockk<SddMandateRepository>()
    private val writer = mockk<MandateExpiryWriter>()
    private val clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 8, 1)

    private fun scheduler(enabled: Boolean, registry: MeterRegistry? = null) = MandateExpiryScheduler(
        mandates = mandates,
        writer = writer,
        clock = clock,
        enabled = enabled,
        domainMetrics = mockk(relaxed = true),
    ).also { s ->
        if (registry != null) {
            val instance = mockk<Instance<MeterRegistry>>()
            every { instance.isResolvable } returns true
            every { instance.get() } returns registry
            s.registryInstance = instance
        }
        s.onStart(StartupEvent())
    }

    private fun mandate(umr: String) = SddMandate(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        debtorIban = "CZ6508000000192000145399",
        creditorIdentifier = "CZ12ZZZ123456789",
        umr = umr,
        scheme = SddScheme.CORE,
        sequenceType = SequenceType.RCUR,
        creditorName = "Creditor",
        debtorName = "Debtor",
        signatureDate = LocalDate.of(2022, 1, 1),
        status = MandateStatus.ACTIVE,
        b2bConfirmed = false,
        lastCollectionDate = LocalDate.of(2023, 7, 1),
        lastPreNotificationDate = null,
        createdAt = Instant.parse("2022-01-01T00:00:00Z"),
        amendments = emptyList<MandateAmendment>(),
    )

    @Test
    fun `disabled scheduler is a no-op`() {
        scheduler(enabled = false).sweep().await().indefinitely()
        verify(exactly = 0) { mandates.listLive() }
    }

    @Test
    fun `enabled scheduler hands every live mandate to the transactional writer`() {
        val a = mandate("UMR-1")
        val b = mandate("UMR-2")
        every { mandates.listLive() } returns Uni.createFrom().item(listOf(a, b))
        every { writer.expireIfIdle(any(), today) } returns Uni.createFrom().item(true)

        scheduler(enabled = true).sweep().await().indefinitely()

        verify(exactly = 1) { writer.expireIfIdle(a, today) }
        verify(exactly = 1) { writer.expireIfIdle(b, today) }
    }

    @Test
    fun `one failing mandate does not abort the sweep and is counted`() {
        val bad = mandate("UMR-BAD")
        val good = mandate("UMR-GOOD")
        val live = mandate("UMR-LIVE")
        every { mandates.listLive() } returns Uni.createFrom().item(listOf(bad, good, live))
        every { writer.expireIfIdle(bad, today) } returns
            Uni.createFrom().failure(IllegalStateException("constraint violation"))
        every { writer.expireIfIdle(good, today) } returns Uni.createFrom().item(true)
        every { writer.expireIfIdle(live, today) } returns Uni.createFrom().item(false)
        val registry = SimpleMeterRegistry()

        // Old code propagated the first failure out of sweep() and never reached the rest.
        scheduler(enabled = true, registry = registry).sweep().await().indefinitely()

        verify(exactly = 1) { writer.expireIfIdle(good, today) }
        verify(exactly = 1) { writer.expireIfIdle(live, today) }
        assertThat(registry.get("openbank.sdd.mandate.expiry.expired").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("openbank.sdd.mandate.expiry.failed").counter().count()).isEqualTo(1.0)
    }
}
