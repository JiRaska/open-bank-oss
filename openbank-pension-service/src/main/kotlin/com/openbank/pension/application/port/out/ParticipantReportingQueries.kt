// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.port.out

import com.openbank.pension.domain.reporting.ContributionTotals
import com.openbank.pension.domain.reporting.InForceGroup
import com.openbank.pension.domain.reporting.PayoutTotals
import com.openbank.pension.domain.reporting.StateContributionFigures
import com.openbank.pension.domain.reporting.TransferTotals
import java.time.LocalDate

/**
 * Aggregate reads for statutory reporting (#12425). Every method answers with GROUP BY results —
 * the adapter never returns a row that identifies a contract or a participant.
 *
 * Dates are closed intervals [from, to]. Timestamped facts (payment instructions, transfer
 * completion, ledger postings) are bucketed by their UTC date.
 */
interface ParticipantReportingQueries {
    /** Distinct currencies booked in [from, to] across contributions and payouts. */
    suspend fun currencies(from: LocalDate, to: LocalDate): Set<String>

    /** Contracts started on or before [asOf] and not ended by it, grouped by line, age and status at [asOf]. */
    suspend fun inForce(asOf: LocalDate): List<InForceGroup>

    suspend fun startedBetween(from: LocalDate, to: LocalDate): Long

    suspend fun exitedBetween(from: LocalDate, to: LocalDate): Long

    /** Distinct contracts with a participant or employer contribution valued in [from, to]. */
    suspend fun contributingBetween(from: LocalDate, to: LocalDate): Long

    /** Distinct contracts paid a recurring pension (phased, fixed-period, annuity premium) in [from, to]. */
    suspend fun pensionersBetween(from: LocalDate, to: LocalDate): Long

    suspend fun contributions(from: LocalDate, to: LocalDate): ContributionTotals

    suspend fun stateContributions(from: LocalDate, to: LocalDate): StateContributionFigures

    suspend fun payouts(from: LocalDate, to: LocalDate): PayoutTotals

    suspend fun transfers(from: LocalDate, to: LocalDate): TransferTotals
}
