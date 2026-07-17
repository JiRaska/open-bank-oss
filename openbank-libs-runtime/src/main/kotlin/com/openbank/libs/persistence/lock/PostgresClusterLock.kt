// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.lock

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jboss.logging.Logger

/**
 * [ClusterLock] via a **transaction-scoped** Postgres advisory lock, `pg_try_advisory_xact_lock`
 * — not the session-scoped `pg_try_advisory_lock` the #1201 issue text names. Session-scoped
 * locks are held by the underlying DB *connection*, and Hibernate Reactive's session is a
 * connection borrowed from a pool for the duration of one `Panache.withTransaction`/`withSession`
 * block — nothing stops that connection being returned to the pool and reused elsewhere while a
 * session-scoped lock taken on it is still logically "held" by this job, unless every call site
 * remembers to pair `pg_advisory_lock`/`pg_advisory_unlock` and never lets an exception skip the
 * unlock. The transaction-scoped variant needs no unlock call at all: Postgres releases it
 * automatically at COMMIT or ROLLBACK, and Hibernate Reactive always commits or rolls back at the
 * end of a `Panache.withTransaction` block — so the lock cannot leak, and [block] runs (and any
 * nested `Panache.withTransaction`/`withSession` calls it makes participate in the same
 * transaction, per Hibernate Reactive's context-bound session propagation) for exactly the
 * lifetime the lock is held.
 */
@ApplicationScoped
class PostgresClusterLock : ClusterLock {
    private val log: Logger = Logger.getLogger(PostgresClusterLock::class.java)

    override suspend fun <T> tryRunExclusively(jobName: String, block: suspend () -> T): T? {
        val key = ClusterLockKey.of(jobName)
        val result = Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(TRY_LOCK_SQL, java.lang.Boolean::class.java)
                    .setParameter("key", key)
                    .singleResult
                    .chain<T?> { acquired ->
                        if (acquired == true) {
                            uni<T?>(CoroutineScope(Dispatchers.Unconfined)) { block() }
                        } else {
                            Uni.createFrom().nullItem()
                        }
                    }
            }
        }.awaitSuspending()
        if (result == null) {
            log.debugf("cluster-lock job=%s key=%d: not acquired (another pod holds this tick)", jobName, key)
        }
        return result
    }

    private companion object {
        const val TRY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(:key)"
    }
}
