// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.UUID

/** Plain SQL fixtures and reads for the decision and report ITs; every state change goes through HTTP. */
abstract class MonitoringDecisionITSupport {
    @Inject
    lateinit var pool: PgPool

    protected fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    protected fun rows(sql: String, vararg args: Any): List<Row> = onEventLoop {
        pool.preparedQuery(sql).execute(Tuple.from(args.toList())).awaitSuspending().toList()
    }

    protected fun seedCase(caseId: UUID, partyId: UUID, screeningType: String) {
        rows(
            """
            INSERT INTO aml_cases
              (case_id, idempotency_key, party_id, customer_reference, screening_type, risk_level, status,
               alert_code, screened_at, created_at, updated_at)
            VALUES ($1, $2, $3, 'ref', $4, 'MEDIUM', 'OPEN', 'syn-structuring-czk-out', now(), now(), now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            caseId,
            "it:$caseId",
            partyId,
            screeningType,
        )
    }

    protected fun case(caseId: UUID): Row =
        rows("SELECT status, decided_by, decision_reason FROM aml_cases WHERE case_id = $1", caseId).single()
}
