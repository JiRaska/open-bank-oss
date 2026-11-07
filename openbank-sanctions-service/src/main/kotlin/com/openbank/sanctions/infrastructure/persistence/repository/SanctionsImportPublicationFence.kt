// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.application.port.out.SanctionsPublicationPermit
import com.openbank.sanctions.domain.model.SanctionsListType
import com.openbank.sanctions.domain.model.SanctionsRefreshInProgress
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/**
 * One PostgreSQL advisory session lock serializes refresh owners. A durable generation, checked
 * in each independent writer transaction, rejects an old owner after its JDBC connection dies.
 * The JDBC pool is separate from the reactive pool used by entry writes and publication, so a
 * single reactive connection remains enough for an import. PostgreSQL releases the session lock
 * on connection loss; the next owner increments the generation before its first batch.
 */
@ApplicationScoped
class SanctionsImportPublicationFence private constructor(
    private val dataSource: DataSource,
    private val lockTimeoutSeconds: Int,
) {
    private companion object {
        const val LOCK_TIMEOUT_SECONDS = 30
    }

    @Inject
    constructor(dataSource: DataSource) : this(dataSource, LOCK_TIMEOUT_SECONDS)

    internal constructor(dataSource: DataSource, lockTimeout: Duration) :
        this(dataSource, lockTimeout.seconds.toInt()) {
        require(lockTimeout.seconds in 1..LOCK_TIMEOUT_SECONDS)
    }

    @Suppress("TooGenericExceptionCaught") // Every failure must keep an incomplete refresh fenced.
    suspend fun <T> duringRefresh(
        listType: SanctionsListType,
        waitForOwner: Boolean = true,
        refresh: suspend (SanctionsPublicationPermit) -> T,
    ): T {
        val (connection, generation, inheritedIncomplete) = withContext(NonCancellable + Dispatchers.IO) {
            acquire(listType, waitForOwner)
        }
        val permit = SanctionsPublicationPermit(listType, generation, inheritedIncomplete)
        try {
            return refresh(permit)
        } catch (failure: Throwable) {
            permit.deferUntilNextRefresh()
            throw failure
        } finally {
            permit.invalidate()
            withContext(NonCancellable + Dispatchers.IO) {
                release(connection, listType, generation, permit.retainPendingJournal)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // JDBC cleanup must also run after an Error.
    private fun acquire(listType: SanctionsListType, waitForOwner: Boolean): Triple<Connection, Long, Boolean> {
        val connection = dataSource.connection
        var locked = false
        try {
            // Bound both the advisory wait and the generation row update in one transaction.
            // SET LOCAL expires at commit and cannot leak into a pooled connection.
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute("SET LOCAL lock_timeout = '${lockTimeoutSeconds}s'")
            }
            lock(connection, listType, waitForOwner)
            locked = true
            // Read the prior durable state under the same row lock used to advance the
            // generation. A fallback/no-feed attempt cannot clear an earlier failed import.
            val inheritedIncomplete = connection.prepareStatement(
                "SELECT refresh_active FROM sanctions_change_publication WHERE list_type = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, listType.name)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "No sanctions publication row for $listType" }
                    rows.getBoolean(1)
                }
            }
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
            connection.commit()
            connection.autoCommit = true
            return Triple(connection, generation, inheritedIncomplete)
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            if (locked) runCatching { unlock(connection, listType) }
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun release(
        connection: Connection,
        listType: SanctionsListType,
        generation: Long,
        retainPendingJournal: Boolean,
    ) {
        try {
            if (!retainPendingJournal) {
                connection.prepareStatement(
                    "UPDATE sanctions_change_publication SET refresh_active = FALSE " +
                        "WHERE list_type = ? AND refresh_generation = ?",
                ).use { statement ->
                    statement.setString(1, listType.name)
                    statement.setLong(2, generation)
                    statement.executeUpdate()
                }
            }
        } finally {
            try {
                unlock(connection, listType)
            } finally {
                connection.close()
            }
        }
    }

    /**
     * Waits (bounded by `lock_timeout`) for the scheduler; an HTTP trigger (#14780) instead tries
     * the same advisory key once and refuses with 409 rather than queueing behind a running refresh.
     */
    private fun lock(connection: Connection, listType: SanctionsListType, waitForOwner: Boolean) {
        val sql = if (waitForOwner) {
            "SELECT pg_advisory_lock(11492, hashtext(?))"
        } else {
            "SELECT pg_try_advisory_lock(11492, hashtext(?))"
        }
        val acquired = connection.prepareStatement(sql).use { statement ->
            statement.setString(1, listType.name)
            // pg_advisory_lock returns void (a null column); only the try form answers false.
            statement.executeQuery().use { rows -> rows.next() && (waitForOwner || rows.getBoolean(1)) }
        }
        if (!acquired) throw SanctionsRefreshInProgress(listType.name)
    }

    private fun unlock(connection: Connection, listType: SanctionsListType) {
        connection.prepareStatement("SELECT pg_advisory_unlock(11492, hashtext(?))").use { statement ->
            statement.setString(1, listType.name)
            statement.execute()
        }
    }
}
