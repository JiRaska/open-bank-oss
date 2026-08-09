// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.usecase

import com.openbank.statement.application.port.`in`.RenderStatementDocumentUseCase
import com.openbank.statement.application.port.`in`.StatementModelUseCase
import com.openbank.statement.application.port.out.DocumentTemplatePort
import com.openbank.statement.application.port.out.RenderedDocument
import com.openbank.statement.domain.model.CreditDebit
import com.openbank.statement.domain.model.StatementEntry
import com.openbank.statement.domain.model.StatementModel
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.util.UUID

/**
 * Orchestrates the customer-facing styled statement download (ADR-0248): replays the same
 * [StatementModel] the camt.053/MT940/PDF render path uses (via [StatementModelUseCase], so the
 * reconciliation/lookup logic is not duplicated), maps it into the `MESICNI_VYPIS_CS`/
 * `MESICNI_VYPIS_EN` Handlebars data shape, and calls document-service's non-persisting preview
 * endpoint. Synchronous, on customer request only — no pre-generation, no Kafka, nothing persisted
 * on either side.
 */
@ApplicationScoped
class StatementDocumentService(
    private val statementModel: StatementModelUseCase,
    private val documentTemplates: DocumentTemplatePort,
) : RenderStatementDocumentUseCase {

    override fun renderDocument(
        accountId: UUID,
        currency: String,
        legalSequence: Long,
        locale: String,
    ): Uni<RenderedDocument> = statementModel.statementModel(accountId, currency, legalSequence).flatMap { model ->
        documentTemplates.renderTemplate(templateCodeFor(locale), model.toDocumentData())
    }

    private fun templateCodeFor(locale: String): String =
        if (locale.equals("cs", ignoreCase = true)) TEMPLATE_CODE_CS else TEMPLATE_CODE_EN

    private companion object {
        const val TEMPLATE_CODE_CS = "MESICNI_VYPIS_CS"
        const val TEMPLATE_CODE_EN = "MESICNI_VYPIS_EN"
    }
}

/**
 * Maps [StatementModel] into the `document.*` / `party.*` / `account.*` Handlebars data shape the
 * `MESICNI_VYPIS_CS`/`MESICNI_VYPIS_EN` templates expect (ADR-0248). `document.generatedAt` is taken
 * from [StatementModel.closedAt] — never the wall clock — so a re-render stays byte-identical, the
 * same determinism guarantee [com.openbank.statement.domain.render.StatementRenderer] upholds.
 */
internal fun StatementModel.toDocumentData(): Map<String, Any?> = mapOf(
    "document" to mapOf(
        "periodFrom" to periodFrom.toString(),
        "periodTo" to periodTo.toString(),
        "openingBalance" to openingBalance.amount,
        "closingBalance" to closingBalance.amount,
        "entries" to entries.map { it.toDocumentData() },
        "legalSequenceNumber" to legalSequenceNumber,
        "electronicSequenceNumber" to electronicSequenceNumber,
        "generatedAt" to closedAt.toString(),
    ),
    "party" to mapOf("name" to holderName),
    "account" to mapOf("iban" to iban, "currency" to currency),
)

private fun StatementEntry.toDocumentData(): Map<String, Any?> = mapOf(
    "bookingDate" to bookingDate.toString(),
    "valueDate" to valueDate.toString(),
    "amount" to signedAmount(),
    "currency" to currency,
    "counterparty" to counterparty,
    "description" to description,
)

/** [StatementEntry.amount] is always non-negative (sign carried by [StatementEntry.creditDebit]) —
 *  the document template expects a signed amount (credit positive, debit negative). */
private fun StatementEntry.signedAmount(): BigDecimal = when (creditDebit) {
    CreditDebit.CRDT -> amount
    CreditDebit.DBIT -> amount.negate()
}
