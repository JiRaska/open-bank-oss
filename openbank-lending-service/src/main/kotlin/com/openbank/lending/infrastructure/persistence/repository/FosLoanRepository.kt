// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.FosAction
import com.openbank.lending.application.port.out.FosCommand
import com.openbank.lending.application.port.out.FosLoanOperations
import com.openbank.lending.application.port.out.FosOperation
import com.openbank.lending.application.port.out.FosRoutePin
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class FosLoanRepository(private val sessions: Mutiny.SessionFactory, private val mapper: ObjectMapper) :
    FosLoanOperations {
    override fun pin(applicationId: UUID, route: FosRoutePin): Uni<Unit> = sessions.withTransaction { s ->
        s.flush().flatMap {
            s.createNativeQuery<Int>(INSERT_ROUTE).setParameter("id", applicationId)
                .setParameter("payload", mapper.writeValueAsString(route)).executeUpdate()
        }.replaceWith(Unit)
    }

    override fun route(applicationId: UUID): Uni<FosRoutePin?> = sessions.withSession { s ->
        s.createNativeQuery(LOAD_ROUTE, String::class.java).setParameter("id", applicationId).singleResultOrNull
            .map { it?.let { json -> mapper.readValue(json, FosRoutePin::class.java) } }
    }

    override fun find(applicationId: UUID, action: FosAction): Uni<FosOperation?> = sessions.withSession { s ->
        s.createNativeQuery(LOAD_OPERATION, String::class.java).setParameter("id", applicationId)
            .setParameter("action", action.name).singleResultOrNull.map { it?.let(::decode) }
    }

    override fun stage(operation: FosOperation): Uni<Unit> = sessions.withTransaction { s ->
        s.createNativeQuery<Int>(INSERT_OPERATION).setParameter("operation", operation.id)
            .setParameter("id", operation.applicationId).setParameter("action", operation.action.name)
            .setParameter("command", mapper.writeValueAsString(operation.command)).executeUpdate().replaceWith(Unit)
    }

    override fun admit(operationId: UUID): Uni<Unit> = change(MARK_ADMITTED, operationId)

    override fun refuse(operationId: UUID): Uni<Unit> = change(MARK_REFUSED, operationId)

    override fun complete(operationId: UUID): Uni<Unit> = change(MARK_COMPLETED, operationId)

    private fun change(sql: String, operationId: UUID): Uni<Unit> = sessions.withTransaction { s ->
        s.createNativeQuery<Int>(sql).setParameter("operation", operationId).executeUpdate().map { rows ->
            check(rows == 1) { "FOS operation has changed" }
        }
    }

    override fun claimDue(limit: Int): Uni<List<FosOperation>> = sessions.withTransaction { s ->
        s.createNativeQuery(CLAIM, String::class.java).setParameter("limit", limit)
            .resultList.map { rows -> rows.map(::decode) }
    }

    private fun decode(json: String): FosOperation = mapper.readTree(json).let { row ->
        FosOperation(
            UUID.fromString(row.path("operation_id").asText()),
            UUID.fromString(row.path("application_id").asText()),
            FosAction.valueOf(row.path("action").asText()),
            mapper.treeToValue(row.path("command"), FosCommand::class.java),
            !row.path("admitted_at").isNull,
            !row.path("completed_at").isNull,
            !row.path("refused_at").isNull,
        )
    }

    private companion object {
        const val INSERT_ROUTE = """
            INSERT INTO lending_fos_route (application_id, route) VALUES (:id, CAST(CAST(:payload AS text) AS jsonb))
        """
        const val LOAD_ROUTE = "SELECT CAST(route AS text) FROM lending_fos_route WHERE application_id = :id"
        const val LOAD_OPERATION = """
            SELECT CAST(to_jsonb(o) AS text) FROM lending_fos_operation o
            WHERE application_id = :id AND action = :action
        """
        const val INSERT_OPERATION = """
            INSERT INTO lending_fos_operation (operation_id, application_id, action, command)
            VALUES (:operation, :id, :action, CAST(CAST(:command AS text) AS jsonb))
        """
        const val MARK_ADMITTED = """
            UPDATE lending_fos_operation SET admitted_at = coalesce(admitted_at, clock_timestamp())
            WHERE operation_id = :operation AND refused_at IS NULL
        """
        const val MARK_REFUSED = """
            UPDATE lending_fos_operation SET refused_at = coalesce(refused_at, clock_timestamp())
            WHERE operation_id = :operation AND admitted_at IS NULL
        """
        const val MARK_COMPLETED = """
            UPDATE lending_fos_operation SET completed_at = coalesce(completed_at, clock_timestamp())
            WHERE operation_id = :operation AND admitted_at IS NOT NULL
        """
        const val CLAIM = """
            WITH due AS (
                SELECT operation_id FROM lending_fos_operation
                WHERE completed_at IS NULL AND refused_at IS NULL AND next_attempt_at <= clock_timestamp()
                ORDER BY next_attempt_at, operation_id
                LIMIT :limit FOR UPDATE SKIP LOCKED
            ), claimed AS (
                UPDATE lending_fos_operation o SET next_attempt_at = clock_timestamp() + interval '1 minute'
                FROM due WHERE o.operation_id = due.operation_id RETURNING o.*
            )
            SELECT CAST(to_jsonb(claimed) AS text) FROM claimed
        """
    }
}
