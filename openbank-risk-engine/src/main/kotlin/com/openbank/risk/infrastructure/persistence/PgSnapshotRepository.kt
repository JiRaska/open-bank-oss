// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.libs.lending.AmortizationMethod
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.LoanExtension
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.RateTerms
import com.openbank.risk.domain.model.RateType
import com.openbank.risk.domain.model.ScheduledInstallment
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutMismatch
import com.openbank.risk.domain.model.TieOutStatus
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.SqlConnection
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Snapshot store on the plain reactive SQL client.
 *
 * Every write is one transaction: the run row, its tie-out mismatches and its positions commit
 * together, so there is never a TIED_OUT run with half its positions. The run insert is
 * `ON CONFLICT (as_of, input_hash) DO NOTHING`, which is what makes the natural key hold under two
 * concurrent requests rather than only under a read-then-write.
 */
@ApplicationScoped
class PgSnapshotRepository(private val pool: Pool) : SnapshotRepository {

    override suspend fun findByNaturalKey(asOf: LocalDate, inputHash: String): SnapshotRun? = loadRun(
        pool.preparedQuery(
            "$SELECT_RUN WHERE as_of = $1 AND input_hash = $2",
        ).execute(Tuple.of(asOf, inputHash)).awaitSuspending().firstOrNull(),
    )

    override suspend fun listRecent(limit: Int): List<SnapshotRunSummary> =
        pool.preparedQuery(SELECT_RECENT).execute(Tuple.of(limit)).awaitSuspending().map { row ->
            SnapshotRunSummary(
                id = row.getUUID("id"),
                asOf = row.getLocalDate("as_of"),
                recordedAt = row.getOffsetDateTime("recorded_at").toInstant(),
                provenance = Provenance.parse(row.getString("provenance")).wire,
                status = TieOutStatus.valueOf(row.getString("status")).name,
                positionCount = row.getInteger("position_count"),
                mismatchCount = row.getInteger("mismatch_count"),
            )
        }

    override suspend fun findById(id: UUID): SnapshotRun? =
        loadRun(pool.preparedQuery("$SELECT_RUN WHERE id = $1").execute(Tuple.of(id)).awaitSuspending().firstOrNull())

    override suspend fun saveIfAbsent(
        run: SnapshotRun,
        positions: List<Position>,
        instruments: List<Instrument>,
    ): SnapshotRun {
        pool.withTransaction { conn ->
            conn.preparedQuery(INSERT_RUN).execute(
                Tuple.tuple(
                    listOf(
                        run.id,
                        run.asOf,
                        run.recordedAt.atOffset(ZoneOffset.UTC),
                        run.inputHash,
                        run.provenance.wire,
                        run.status.name,
                        run.positionCount,
                        run.mismatches.size,
                    ),
                ),
            ).flatMap { result ->
                if (result.rowCount() == 0) {
                    Uni.createFrom().voidItem()
                } else {
                    insertChildren(conn, run, positions, instruments)
                }
            }
        }.awaitSuspending()
        return requireNotNull(findByNaturalKey(run.asOf, run.inputHash)) { "run ${run.id} vanished after commit" }
    }

    private fun insertChildren(
        conn: SqlConnection,
        run: SnapshotRun,
        positions: List<Position>,
        instruments: List<Instrument>,
    ): Uni<Void> {
        val mismatchRows = run.mismatches.map {
            Tuple.tuple(listOf(run.id, it.glAccountCode, it.currency, it.ledgerNet, it.positionsNet))
        }
        val recordedAt = run.recordedAt.atOffset(ZoneOffset.UTC)
        val positionRows = positions.map {
            Tuple.tuple(
                listOf(
                    run.id,
                    it.kind.name,
                    it.glAccountCode,
                    it.glAccountType,
                    it.currency,
                    it.subAccountId,
                    it.amount,
                    run.asOf,
                    recordedAt,
                    it.instrumentId,
                ),
            )
        }
        val instrumentRows = instruments.map { instrumentRow(run, it) }
        val installmentRows = instruments.flatMap { i ->
            (i.extension as? LoanExtension)?.remainingInstallments.orEmpty().map {
                Tuple.tuple(listOf(run.id, i.id, it.number, it.dueDate, it.principal, it.interest))
            }
        }
        // Instruments before positions is not required (no FK between them) but instruments before
        // their installments is: the installment rows reference (run_id, instrument_id).
        return batch(conn, INSERT_MISMATCH, mismatchRows)
            .flatMap { batch(conn, INSERT_INSTRUMENT, instrumentRows) }
            .flatMap { batch(conn, INSERT_INSTALLMENT, installmentRows) }
            .flatMap { batch(conn, INSERT_POSITION, positionRows) }
    }

    private fun instrumentRow(run: SnapshotRun, i: Instrument): Tuple {
        val loan = i.extension as? LoanExtension
        val rate = i.rateTerms
        return Tuple.tuple(
            listOf(
                run.id,
                i.id,
                i.kind.name,
                i.glAccountCode,
                i.currency,
                i.outstanding,
                i.valueDate,
                i.maturityDate,
                rate?.rateType?.name,
                rate?.currentAnnualRate,
                rate?.index?.name,
                rate?.spread,
                rate?.resetFrequencyMonths,
                rate?.nextResetDate,
                i.counterpartyRef,
                i.ifrs9Stage,
                loan?.method?.name,
                loan?.periodsPerYear,
            ),
        )
    }

    override suspend fun findInstruments(runId: UUID): List<Instrument> {
        val installments = pool.preparedQuery(SELECT_INSTALLMENTS).execute(Tuple.of(runId)).awaitSuspending()
            .map { row ->
                row.getString("instrument_id") to ScheduledInstallment(
                    number = row.getInteger("installment_number"),
                    dueDate = row.getLocalDate("due_date"),
                    principal = row.getBigDecimal("principal"),
                    interest = row.getBigDecimal("interest"),
                )
            }
            .groupBy({ it.first }, { it.second })
        return pool.preparedQuery(SELECT_INSTRUMENTS).execute(Tuple.of(runId)).awaitSuspending().map { row ->
            val id = row.getString("instrument_id")
            val method = row.getString("amortization_method")
            Instrument(
                id = id,
                kind = InstrumentKind.valueOf(row.getString("instrument_kind")),
                glAccountCode = row.getString("gl_account_code"),
                currency = row.getString("currency"),
                outstanding = row.getBigDecimal("outstanding"),
                valueDate = row.getLocalDate("value_date"),
                maturityDate = row.getLocalDate("maturity_date"),
                rateTerms = row.getString("rate_type")?.let { type ->
                    RateTerms(
                        rateType = RateType.valueOf(type),
                        currentAnnualRate = row.getBigDecimal("current_annual_rate"),
                        index = row.getString("rate_index")?.let(CurveIndex::valueOf),
                        spread = row.getBigDecimal("spread"),
                        resetFrequencyMonths = row.getInteger("reset_frequency_months"),
                        nextResetDate = row.getLocalDate("next_reset_date"),
                    )
                },
                counterpartyRef = row.getString("counterparty_ref"),
                ifrs9Stage = row.getString("ifrs9_stage"),
                extension = method?.let {
                    LoanExtension(
                        method = AmortizationMethod.valueOf(it),
                        periodsPerYear = row.getInteger("periods_per_year"),
                        remainingInstallments = installments[id].orEmpty(),
                    )
                },
            )
        }
    }

    // executeBatch rejects an empty list, and an empty snapshot (no ledger activity) is legitimate.
    private fun batch(conn: SqlConnection, sql: String, rows: List<Tuple>): Uni<Void> = if (rows.isEmpty()) {
        Uni.createFrom().voidItem()
    } else {
        conn.preparedQuery(
            sql,
        ).executeBatch(rows).replaceWithVoid()
    }

    override suspend fun findPositions(runId: UUID): List<Position> =
        pool.preparedQuery(SELECT_POSITIONS).execute(Tuple.of(runId)).awaitSuspending().map { row ->
            Position(
                kind = PositionKind.valueOf(row.getString("position_kind")),
                glAccountCode = row.getString("gl_account_code"),
                glAccountType = row.getString("gl_account_type"),
                currency = row.getString("currency"),
                subAccountId = row.getUUID("sub_account_id"),
                amount = row.getBigDecimal("amount"),
                instrumentId = row.getString("instrument_id"),
            )
        }

    private suspend fun loadRun(row: Row?): SnapshotRun? {
        row ?: return null
        val id = row.getUUID("id")
        val mismatches = pool.preparedQuery(SELECT_MISMATCHES).execute(Tuple.of(id)).awaitSuspending().map {
            TieOutMismatch(
                glAccountCode = it.getString("gl_account_code"),
                currency = it.getString("currency"),
                ledgerNet = it.getBigDecimal("ledger_net"),
                positionsNet = it.getBigDecimal("positions_net"),
            )
        }
        return SnapshotRun(
            id = id,
            asOf = row.getLocalDate("as_of"),
            recordedAt = row.getOffsetDateTime("recorded_at").toInstant(),
            inputHash = row.getString("input_hash"),
            provenance = Provenance.parse(row.getString("provenance")),
            status = TieOutStatus.valueOf(row.getString("status")),
            positionCount = row.getInteger("position_count"),
            mismatches = mismatches,
        )
    }

    private companion object {
        const val SELECT_RECENT =
            "SELECT id, as_of, recorded_at, provenance, status, position_count, mismatch_count FROM snapshot_run " +
                "ORDER BY recorded_at DESC, id LIMIT $1"
        const val SELECT_RUN =
            "SELECT id, as_of, recorded_at, input_hash, provenance, status, position_count FROM snapshot_run"
        const val INSERT_RUN =
            "INSERT INTO snapshot_run (id, as_of, recorded_at, input_hash, provenance, status, " +
                "position_count, mismatch_count) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (as_of, input_hash) DO NOTHING"
        const val INSERT_MISMATCH =
            "INSERT INTO snapshot_tie_out_mismatch (run_id, gl_account_code, currency, ledger_net, positions_net) " +
                "VALUES ($1, $2, $3, $4, $5)"
        const val INSERT_POSITION =
            "INSERT INTO snapshot_position (run_id, position_kind, gl_account_code, gl_account_type, currency, " +
                "sub_account_id, amount, valid_date, recorded_at, instrument_id) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)"
        const val SELECT_MISMATCHES =
            "SELECT gl_account_code, currency, ledger_net, positions_net FROM snapshot_tie_out_mismatch " +
                "WHERE run_id = $1 ORDER BY id"
        const val SELECT_POSITIONS =
            "SELECT position_kind, gl_account_code, gl_account_type, currency, sub_account_id, amount, " +
                "instrument_id FROM snapshot_position WHERE run_id = $1 ORDER BY id"
        const val INSERT_INSTRUMENT =
            "INSERT INTO snapshot_instrument (run_id, instrument_id, instrument_kind, gl_account_code, currency, " +
                "outstanding, value_date, maturity_date, rate_type, current_annual_rate, rate_index, spread, " +
                "reset_frequency_months, next_reset_date, counterparty_ref, ifrs9_stage, amortization_method, " +
                "periods_per_year) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, " +
                "$17, $18)"
        const val INSERT_INSTALLMENT =
            "INSERT INTO snapshot_instrument_installment (run_id, instrument_id, installment_number, due_date, " +
                "principal, interest) VALUES ($1, $2, $3, $4, $5, $6)"
        const val SELECT_INSTRUMENTS =
            "SELECT instrument_id, instrument_kind, gl_account_code, currency, outstanding, value_date, " +
                "maturity_date, rate_type, current_annual_rate, rate_index, spread, reset_frequency_months, " +
                "next_reset_date, counterparty_ref, ifrs9_stage, amortization_method, periods_per_year " +
                "FROM snapshot_instrument WHERE run_id = $1 ORDER BY instrument_id"
        const val SELECT_INSTALLMENTS =
            "SELECT instrument_id, installment_number, due_date, principal, interest " +
                "FROM snapshot_instrument_installment WHERE run_id = $1 ORDER BY instrument_id, installment_number"
    }
}
