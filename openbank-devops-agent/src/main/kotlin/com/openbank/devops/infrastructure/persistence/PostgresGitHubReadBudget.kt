// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.persistence

import com.openbank.devops.application.port.out.GitHubReadBudget
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.future.await
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.util.UUID

/** Database-clock rolling-hour budget shared by all readers and replicas of this agent. */
@ApplicationScoped
class PostgresGitHubReadBudget(private val sf: Mutiny.SessionFactory) : GitHubReadBudget {
    override suspend fun reserve(observer: Boolean): Boolean = sf.withTransaction { s ->
        s.createNativeQuery<Int>(
            "UPDATE github_read_budget SET reservation_serial = reservation_serial + 1 WHERE id = 1",
        ).executeUpdate().flatMap {
            s.createNativeQuery<Int>(
                "DELETE FROM github_read_requests WHERE requested_at <= CURRENT_TIMESTAMP - INTERVAL '1 hour'",
            ).executeUpdate()
        }.flatMap {
            s.createNativeQuery<Int>(
                """INSERT INTO github_read_requests (id, requested_at, observer)
                        SELECT :id, CURRENT_TIMESTAMP, :observer
                        WHERE (SELECT COUNT(*) FROM github_read_requests) < 20
                        AND (:observer = false OR
                             (SELECT COUNT(*) FROM github_read_requests WHERE observer = true) < 18)
                        AND (SELECT blocked_until IS NULL OR blocked_until <= CURRENT_TIMESTAMP
                             FROM github_read_budget WHERE id = 1)
                """.trimIndent(),
            ).setParameter("id", UUID.randomUUID()).setParameter("observer", observer).executeUpdate()
        }
    }.map { it == 1 }.subscribeAsCompletionStage().await()

    override suspend fun deferUntil(until: Instant) {
        sf.withTransaction { s ->
            s.createNativeQuery<Int>(
                """UPDATE github_read_budget
                    SET blocked_until = GREATEST(COALESCE(blocked_until, CURRENT_TIMESTAMP), :until)
                    WHERE id = 1
                """.trimIndent(),
            ).setParameter("until", until).executeUpdate()
        }.subscribeAsCompletionStage().await()
    }
}
