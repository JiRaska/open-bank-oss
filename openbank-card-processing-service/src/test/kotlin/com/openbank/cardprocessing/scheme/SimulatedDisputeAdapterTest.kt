// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.scheme

import com.openbank.cardprocessing.infrastructure.scheme.RoutedDisputePort
import com.openbank.cardprocessing.infrastructure.scheme.SimulatedDisputeAdapter
import com.openbank.libs.domain.cards.scheme.CardScheme
import com.openbank.libs.domain.cards.scheme.DisputeEvidence
import com.openbank.libs.domain.cards.scheme.SchemeFailure
import com.openbank.libs.domain.cards.scheme.SchemeResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The network-side case lifecycle, and the two things the simulator must refuse to invent: a reason
 * code and a case that does not exist.
 */
class SimulatedDisputeAdapterTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)
    private val adapter = SimulatedDisputeAdapter(clock)

    @Test
    fun `opening a case carries the caller's reason code through unchanged`(): Unit = runBlocking {
        val opened = adapter.open("acq-42", "13.1", 25_000, "czk") as SchemeResult.Answered

        // The code vocabulary is per-network, versioned and published under contract. Mapping or
        // normalising it here would teach a caller a code the network rejects — months later, at a
        // real chargeback deadline.
        assertThat(opened.value.reasonCode).isEqualTo("13.1")
        assertThat(opened.value.currencyCode).isEqualTo("CZK")
        assertThat(opened.value.status).isEqualTo("OPEN")
        assertThat(opened.value.respondByDate).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(opened.scheme).isEqualTo(CardScheme.SIMULATOR)
    }

    @Test
    fun `a blank reason code is refused rather than defaulted`(): Unit = runBlocking {
        val result = adapter.open("acq-42", "  ", 25_000, "CZK")

        assertThat((result as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.MALFORMED)
    }

    @Test
    fun `a non-positive amount is refused`(): Unit = runBlocking {
        assertThat((adapter.open("acq-42", "13.1", 0, "CZK") as SchemeResult.Unanswered).failure)
            .isEqualTo(SchemeFailure.MALFORMED)
    }

    @Test
    fun `evidence moves the case on and is readable back`(): Unit = runBlocking {
        val opened = (adapter.open("acq-42", "13.1", 25_000, "CZK") as SchemeResult.Answered).value

        val submitted = adapter.submitEvidence(
            DisputeEvidence(opened.networkCaseId, "doc-1", "receipt"),
        ) as SchemeResult.Answered
        val readBack = adapter.status(opened.networkCaseId) as SchemeResult.Answered

        assertThat(submitted.value.status).isEqualTo("EVIDENCE_SUBMITTED")
        assertThat(readBack.value.status).isEqualTo("EVIDENCE_SUBMITTED")
    }

    @Test
    fun `evidence with no document reference is refused`(): Unit = runBlocking {
        val opened = (adapter.open("acq-42", "13.1", 25_000, "CZK") as SchemeResult.Answered).value

        val result = adapter.submitEvidence(DisputeEvidence(opened.networkCaseId, "", null))

        assertThat((result as SchemeResult.Unanswered).failure).isEqualTo(SchemeFailure.MALFORMED)
    }

    @Test
    fun `an unknown case is NOT_FOUND on both read and write`(): Unit = runBlocking {
        assertThat((adapter.status("sim-case-nope") as SchemeResult.Unanswered).failure)
            .isEqualTo(SchemeFailure.NOT_FOUND)
        assertThat(
            (adapter.submitEvidence(DisputeEvidence("sim-case-nope", "doc", null)) as SchemeResult.Unanswered).failure,
        ).isEqualTo(SchemeFailure.NOT_FOUND)
    }

    @Test
    fun `choosing a vendor binding says a contract is needed, and names the network`(): Unit = runBlocking {
        val router = RoutedDisputePort(adapter, "mastercard")

        val result = router.open("acq-42", "13.1", 25_000, "CZK") as SchemeResult.Unanswered

        assertThat(result.failure).isEqualTo(SchemeFailure.NOT_BOUND)
        assertThat(result.scheme).isEqualTo(CardScheme.MASTERCARD)
        assertThat(result.detail).contains("contract")
    }
}
