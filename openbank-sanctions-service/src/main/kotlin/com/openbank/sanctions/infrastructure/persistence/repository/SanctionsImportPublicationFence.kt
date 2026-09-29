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
import javax.sql.DataSource

/**
 * One PostgreSQL advisory transaction lock spans every independently committed import batch and
 * its final publication. This keeps two complete refreshes from being merged at the handoff.
 * The JDBC pool is separate from the reactive pool used by entry writes and publication, so a
 * single reactive connection remains enough for an import. PostgreSQL releases the lock on rollback
 * or connection loss; a crashed pod leaves committed journal rows available for the next publisher.
 */
@ApplicationScoped
class SanctionsImportPublicationFence(private val dataSource: DataSource) {
    @Suppress("TooGenericExceptionCaught")
    // The connection must be closed even if acquisition fails with an Error or cancellation.
    suspend fun <T> duringRefresh(listType: SanctionsListType, refresh: suspend (SanctionsPublicationPermit) -> T): T {
        val connection = withContext(NonCancellable + Dispatchers.IO) {
            dataSource.connection.also { connection ->
                try {
                    connection.autoCommit = false
                    connection.prepareStatement("SELECT pg_advisory_xact_lock(11492, hashtext(?))").use { statement ->
                        statement.setString(1, listType.name)
                        statement.execute()
                    }
                } catch (failure: Throwable) {
                    connection.close()
                    throw failure
                }
            }
        }
        val permit = SanctionsPublicationPermit(listType)
        try {
            return refresh(permit)
        } finally {
            permit.invalidate()
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    connection.rollback()
                } finally {
                    connection.close()
                }
            }
        }
    }
}
