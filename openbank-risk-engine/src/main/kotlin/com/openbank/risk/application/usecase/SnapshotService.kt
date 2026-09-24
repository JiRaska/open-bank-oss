// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.risk.application.port.`in`.SnapshotOutcome
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.application.port.out.LendingPort
import com.openbank.risk.application.port.out.SnapshotNotFoundException
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.port.out.UntiedSnapshotException
import com.openbank.risk.domain.model.InputHash
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LoanInstrumentMapper
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionBuilder
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOut
import com.openbank.risk.domain.model.TieOutStatus
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds, gates and stores one balance-sheet snapshot (ADR-0314 D1–D4).
 *
 * Framework-free on purpose: the provenance arrives as a constructor value so the unit tests
 * construct this class directly; the CDI wiring lives in `SnapshotServiceProducer`.
 */
class SnapshotService(
    private val ledger: LedgerPort,
    private val repository: SnapshotRepository,
    private val clock: Clock,
    private val provenance: Provenance,
    /**
     * Lending's loan book (ADR-0314 D4), or null when the read is disabled — then Loans
     * Receivable stays a GL-level position exactly as before loans were modelled.
     */
    private val lending: LendingPort? = null,
) : SnapshotUseCase {

    override suspend fun createSnapshot(asOf: LocalDate): SnapshotOutcome {
        val inputs = ledger.read(asOf)
        val loanBook = lending?.readLoanBook(asOf)
        val inputHash = InputHash.of(inputs, loanBook)
        repository.findByNaturalKey(asOf, inputHash)?.let { return SnapshotOutcome(it, replayed = true) }

        val instruments = loanBook?.map(LoanInstrumentMapper::toInstrument)
        val positions = PositionBuilder.build(inputs, instruments)
        val tieOut = TieOut.check(inputs.trialBalance, positions)
        val candidate = SnapshotRun(
            id = Ids.newId(),
            asOf = asOf,
            recordedAt = clock.instant(),
            inputHash = inputHash,
            provenance = provenance,
            status = tieOut.status,
            positionCount = positions.size,
            mismatches = tieOut.mismatches,
        )
        val stored = repository.saveIfAbsent(candidate, positions, instruments.orEmpty())
        return SnapshotOutcome(stored, replayed = stored.id != candidate.id)
    }

    override suspend fun getRun(id: UUID): SnapshotRun = repository.findById(id) ?: throw SnapshotNotFoundException(id)

    override suspend fun getPositions(id: UUID): List<Position> {
        val run = getRun(id)
        if (run.status != TieOutStatus.TIED_OUT) throw UntiedSnapshotException(run.id, run.mismatches)
        return repository.findPositions(id)
    }

    override suspend fun getInstruments(id: UUID): List<Instrument> {
        val run = getRun(id)
        if (run.status != TieOutStatus.TIED_OUT) throw UntiedSnapshotException(run.id, run.mismatches)
        return repository.findInstruments(id)
    }
}
