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
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class MandateExpirySchedulerTest {

    private val mandates = mockk<SddMandateRepository>()
    private val scheduler = MandateExpiryScheduler(
        mandates = mandates,
        clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
        enabled = false,
        domainMetrics = mockk(relaxed = true),
    )

    @Test
    fun `disabled scheduler is a no-op`() {
        scheduler.sweep().await().indefinitely()
    }

    @Test
    fun `enabled scheduler persists expired mandates and skips live ones`() {
        val idle = SddMandate(
            id = UUID.randomUUID(),
            accountId = UUID.randomUUID(),
            debtorIban = "CZ6508000000192000145399",
            creditorIdentifier = "CZ12ZZZ123456789",
            umr = "UMR-1",
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
        val enabled = MandateExpiryScheduler(
            mandates = mandates,
            clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
            enabled = true,
            domainMetrics = mockk(relaxed = true),
        )
        every { mandates.listLive() } returns Uni.createFrom().item(listOf(idle))
        every { mandates.save(any()) } answers { Uni.createFrom().item(firstArg<SddMandate>()) }

        enabled.sweep().await().indefinitely()
    }
}
