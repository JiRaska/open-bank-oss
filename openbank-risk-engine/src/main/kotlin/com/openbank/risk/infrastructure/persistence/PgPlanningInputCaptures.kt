// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.persistence

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.risk.application.port.out.PlanningInputCaptures
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import io.vertx.pgclient.PgException
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class PgPlanningInputCaptures(private val pool: Pool) : PlanningInputCaptures {
    override suspend fun capture(snapshot: UUID, curves: UUID, treasuryRevisions: List<Long>): UUID {
        val id = Ids.newId()
        try {
            pool.preparedQuery("SELECT capture_planning_inputs($1, $2, $3, $4)")
                .execute(Tuple.tuple(listOf(id, snapshot, curves, treasuryRevisions.toTypedArray())))
                .awaitSuspending()
        } catch (e: PgException) {
            if (e.sqlState !in setOf("23502", "23503", "23505", "23514")) throw e
            throw IllegalArgumentException(
                "Select a tied-out snapshot, a curve set for the same date and provenance, " +
                    "and at most one known Treasury revision per deal",
                e,
            )
        }
        return id
    }
}
