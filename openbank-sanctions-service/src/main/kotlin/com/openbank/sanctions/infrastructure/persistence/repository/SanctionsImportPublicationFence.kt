// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.application.port.out.SanctionsPublicationPermit
import com.openbank.sanctions.domain.model.SanctionsListType
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * One PostgreSQL advisory session lock serializes refresh owners. A durable generation, checked
 * in each independent writer transaction, rejects an old owner after its JDBC connection dies.
 * The JDBC pool is separate from the reactive pool used by entry writes and publication, so a
 * single reactive connection remains enough for an import. PostgreSQL releases the session lock
 * on connection loss; the next owner increments the generation before its first batch.
 */
@ApplicationScoped
class SanctionsImportPublicationFence(private val dataSource: DataSource) {
    suspend fun <T> duringRefresh(listType: SanctionsListType, refresh: suspend (SanctionsPublicationPermit) -> T): T {
        val (connection, generation) = withContext(NonCancellable + Dispatchers.IO) {
            acquire(listType)
        }
        val permit = SanctionsPublicationPermit(listType, generation)
        try {
            return refresh(permit)
        } finally {
            permit.invalidate()
            withContext(NonCancellable + Dispatchers.IO) {
                release(connection, listType, generation)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // JDBC cleanup must also run after an Error.
    private fun acquire(listType: SanctionsListType): Pair<Connection, Long> {
        val connection = dataSource.connection
        var locked = false
        try {
            // The timeout is LOCAL to this acquisition transaction, never leaked to the pool.
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '30s'") }
            connection.prepareStatement("SELECT pg_advisory_lock(11492, hashtext(?))").use { statement ->
                statement.setString(1, listType.name)
                statement.execute()
            }
            locked = true
            connection.commit()
            connection.autoCommit = true
            val generation = connection.prepareStatement(
                "UPDATE sanctions_change_publication SET refresh_generation = refresh_generation + 1, " +
                    "refresh_active = TRUE WHERE list_type = ? RETURNING refresh_generation",
            ).use { statement ->
                statement.setString(1, listType.name)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "No sanctions publication row for $listType" }
                    rows.getLong(1)
                }
            }
            return connection to generation
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            if (locked) runCatching { unlock(connection, listType) }
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun release(connection: Connection, listType: SanctionsListType, generation: Long) {
        try {
            connection.prepareStatement(
                "UPDATE sanctions_change_publication SET refresh_active = FALSE " +
                    "WHERE list_type = ? AND refresh_generation = ?",
            ).use { statement ->
                statement.setString(1, listType.name)
                statement.setLong(2, generation)
                statement.executeUpdate()
            }
        } finally {
            try {
                unlock(connection, listType)
            } finally {
                connection.close()
            }
        }
    }

    private fun unlock(connection: Connection, listType: SanctionsListType) {
        connection.prepareStatement("SELECT pg_advisory_unlock(11492, hashtext(?))").use { statement ->
            statement.setString(1, listType.name)
            statement.execute()
        }
    }
}
