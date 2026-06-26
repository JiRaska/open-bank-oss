// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sdd.domain.lifecycle

import com.openbank.sdd.Fixtures
import com.openbank.sdd.domain.model.MandateStatus
import com.openbank.sdd.domain.model.SddScheme
import com.openbank.sdd.domain.model.SequenceType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class MandateLifecycleTest {

    @Test
    fun `confirming a pending B2B mandate activates and marks it verified`() {
        val pending = Fixtures.mandate(scheme = SddScheme.B2B, status = MandateStatus.PENDING_CONFIRMATION, b2bConfirmed = false)
        val confirmed = MandateLifecycle.confirm(pending)
        assertThat(confirmed.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat(confirmed.b2bConfirmed).isTrue()
    }

    @Test
    fun `confirming an already-active mandate is illegal`() {
        assertThatThrownBy { MandateLifecycle.confirm(Fixtures.mandate(status = MandateStatus.ACTIVE)) }
            .isInstanceOf(IllegalMandateTransition::class.java)
    }

    @Test
    fun `suspend and resume round-trip between ACTIVE and SUSPENDED`() {
        val suspended = MandateLifecycle.suspend(Fixtures.mandate(status = MandateStatus.ACTIVE))
        assertThat(suspended.status).isEqualTo(MandateStatus.SUSPENDED)
        assertThat(MandateLifecycle.resume(suspended).status).isEqualTo(MandateStatus.ACTIVE)
    }

    @Test
    fun `cancelling is terminal and cannot be undone`() {
        val cancelled = MandateLifecycle.cancel(Fixtures.mandate(status = MandateStatus.ACTIVE))
        assertThat(cancelled.status).isEqualTo(MandateStatus.CANCELLED)
        assertThatThrownBy { MandateLifecycle.cancel(cancelled) }
            .isInstanceOf(IllegalMandateTransition::class.java)
    }

    @Test
    fun `amending a live mandate records an AMDT marker without changing status`() {
        val amended = MandateLifecycle.amend(
            Fixtures.mandate(status = MandateStatus.ACTIVE),
            field = "creditorName",
            oldValue = "Energie a.s.",
            newValue = "Energie SE",
            asOf = Instant.parse("2026-03-01T00:00:00Z"),
        )
        assertThat(amended.status).isEqualTo(MandateStatus.ACTIVE)
        assertThat(amended.amendments).hasSize(1)
        assertThat(amended.amendments.single().newValue).isEqualTo("Energie SE")
    }

    @Test
    fun `amending a terminal mandate is illegal`() {
        assertThatThrownBy {
            MandateLifecycle.amend(
                Fixtures.mandate(status = MandateStatus.CANCELLED),
                "umr", "UMR-0001", "UMR-0002", Instant.now(),
            )
        }.isInstanceOf(IllegalMandateTransition::class.java)
    }

    @Test
    fun `recording a collection advances FRST to RCUR and stamps the date`() {
        val first = Fixtures.mandate(sequenceType = SequenceType.FRST)
        val after = MandateLifecycle.recordCollection(first, LocalDate.parse("2026-02-10"))
        assertThat(after.sequenceType).isEqualTo(SequenceType.RCUR)
        assertThat(after.lastCollectionDate).isEqualTo(LocalDate.parse("2026-02-10"))
    }

    @Test
    fun `a mandate idle for 36 months auto-expires`() {
        val idle = Fixtures.mandate(status = MandateStatus.ACTIVE, lastCollectionDate = LocalDate.parse("2023-01-10"))
        val swept = MandateLifecycle.expireIfIdle(idle, LocalDate.parse("2026-01-10"))
        assertThat(swept.status).isEqualTo(MandateStatus.EXPIRED)
    }

    @Test
    fun `a mandate just short of 36 idle months is left untouched`() {
        val idle = Fixtures.mandate(status = MandateStatus.ACTIVE, lastCollectionDate = LocalDate.parse("2023-01-10"))
        val swept = MandateLifecycle.expireIfIdle(idle, LocalDate.parse("2026-01-09"))
        assertThat(swept.status).isEqualTo(MandateStatus.ACTIVE)
    }

    @Test
    fun `idle anchor falls back to the signature date when there is no collection`() {
        // No collection ever; signed 2023-01-01, asOf 36 months later -> idle.
        val never = Fixtures.mandate(status = MandateStatus.ACTIVE, lastCollectionDate = null, signatureDate = LocalDate.parse("2023-01-01"))
        assertThat(MandateLifecycle.expireIfIdle(never, LocalDate.parse("2026-01-01")).status)
            .isEqualTo(MandateStatus.EXPIRED)
    }
}
