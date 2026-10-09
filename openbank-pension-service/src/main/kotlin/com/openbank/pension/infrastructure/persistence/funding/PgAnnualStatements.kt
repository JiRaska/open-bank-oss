// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.persistence.funding

import com.openbank.pension.application.port.out.AnnualStatement
import com.openbank.pension.application.port.out.AnnualStatementRepository
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Singleton
import java.time.ZoneOffset
import java.util.UUID

/** V9 `pension_annual_statements`: one row per (contract, year), insert-once (#12379). */
@Singleton
class PgAnnualStatements(client: Pool) :
    PgFundingSupport(client),
    AnnualStatementRepository {

    override suspend fun find(contractId: UUID, year: Int): AnnualStatement? = rows(
        "SELECT * FROM pension_annual_statements WHERE contract_id = $1 AND statement_year = $2",
        Tuple.of(contractId, year),
    ).firstOrNull()?.toStatement()

    override suspend fun saveOnce(statement: AnnualStatement): AnnualStatement {
        exec(
            """
            INSERT INTO pension_annual_statements (contract_id, statement_year, document_id, document_sha256, issued_at)
            VALUES ($1, $2, $3, $4, $5) ON CONFLICT (contract_id, statement_year) DO NOTHING
            """.trimIndent(),
            Tuple.tuple(
                listOf(
                    statement.contractId,
                    statement.year,
                    statement.documentId,
                    statement.sha256,
                    statement.issuedAt.atOffset(ZoneOffset.UTC),
                ),
            ),
        )
        // A concurrent issue may have won the insert; the stored row is the statement.
        return requireNotNull(find(statement.contractId, statement.year)) { "annual statement vanished" }
    }

    private fun Row.toStatement() = AnnualStatement(
        contractId = getUUID("contract_id"),
        year = getInteger("statement_year"),
        documentId = getString("document_id"),
        sha256 = getString("document_sha256"),
        issuedAt = getOffsetDateTime("issued_at").toInstant(),
    )
}
