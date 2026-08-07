// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.`in`

import com.openbank.statement.application.port.out.RenderedDocument
import com.openbank.statement.domain.model.StatementFormat
import com.openbank.statement.domain.model.StatementModel
import com.openbank.statement.domain.model.StatementPeriod
import com.openbank.statement.domain.render.StatementRenderer
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/** Closes a calendar period for every pocket of an account (scheduled or triggered). */
interface ClosePeriodUseCase {
    fun closeMonth(accountId: UUID, periodFrom: LocalDate, periodTo: LocalDate): Uni<List<StatementPeriod>>
}

/**
 * Closes ONE pocket for ONE month — the granular unit the self-healing catch-up orchestrator drives
 * (ADR-0069 D3 / issue #470), so a failure in one pocket/month is isolated and accounted for rather
 * than aborting the whole account. Idempotent on (account, pocket, period).
 */
interface ClosePocketUseCase {
    fun closePocketMonth(accountId: UUID, currency: String, from: LocalDate, to: LocalDate): Uni<StatementPeriod>
}

/**
 * Restates an already-closed period after a correction to the underlying booked data (ADR-0035 §D,
 * issue #1302 item 5).
 *
 * A close is **immutable**: this never edits the existing record. It re-reads the booked entries and
 * balance-service's closing balance, reconciles fail-closed exactly as a first close does, and — if
 * the figures actually changed — writes a **new** period close with the next legal sequence and
 * `supersedesSequence` pointing at the record it replaces, flipping that record to
 * [com.openbank.statement.domain.model.PeriodCloseStatus.SUPERSEDED] in the same transaction.
 *
 * Idempotent in the sense that matters for a legal sequence: if the recomputed figures are identical
 * to the standing close, **no** sequence is burnt and the existing record is returned unchanged.
 */
interface RestatePeriodUseCase {
    fun restatePocketPeriod(accountId: UUID, currency: String, from: LocalDate, to: LocalDate): Uni<StatementPeriod>
}

/** Renders an already-closed period on demand — nothing is stored (ADR-0035 §F.2). */
interface RenderStatementUseCase {
    fun render(
        accountId: UUID,
        currency: String,
        legalSequence: Long,
        format: StatementFormat,
    ): Uni<StatementRenderer.Rendered>
}

/**
 * Replays an already-closed period into its canonical [StatementModel] — the same lookup
 * [RenderStatementUseCase.render] uses internally, exposed so a second consumer (the customer-facing
 * styled document, ADR-0248) can reuse the reconciliation/lookup logic without duplicating it.
 */
interface StatementModelUseCase {
    fun statementModel(accountId: UUID, currency: String, legalSequence: Long): Uni<StatementModel>
}

/**
 * Renders the customer-facing styled statement document (ADR-0248) — synchronous, on customer
 * request only. Assembles [StatementModel] into document-service's Handlebars data shape and calls
 * its non-persisting `/api/v1/documents/templates/preview` endpoint; nothing is stored on either
 * side.
 */
interface RenderStatementDocumentUseCase {
    fun renderDocument(accountId: UUID, currency: String, legalSequence: Long, locale: String): Uni<RenderedDocument>
}

/** Lists the retained period-close records for an account. */
interface ListStatementsUseCase {
    fun list(accountId: UUID): Uni<List<StatementPeriod>>
}

/**
 * On-demand, non-sequenced export for an arbitrary date range (ADR-0035 §F.3) — an *informational*
 * document that is explicitly NOT a numbered legal statement page.
 */
interface AdHocExportUseCase {
    fun export(
        accountId: UUID,
        currency: String,
        from: LocalDate,
        to: LocalDate,
        format: StatementFormat,
    ): Uni<StatementRenderer.Rendered>
}
