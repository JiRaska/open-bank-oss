// SPDX-License-Identifier: Apache-2.0
package com.openbank.notification.integration

import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import com.openbank.notification.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class NotificationPagingIT {
    @Inject lateinit var repository: NotificationRepository

    @Inject lateinit var dataSource: DataSource

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    @Test
    fun `pages preserve order and exact party counts across page boundaries`() {
        val party = UUID.randomUUID()
        val otherParty = UUID.randomUUID()
        // A shared high half gives UUIDs the same ordering in Java and PostgreSQL.
        val namespace = UUID.randomUUID().mostSignificantBits
        val ids = (1..5).map { UUID(namespace, it.toLong()) }
        val first = Instant.parse("2026-10-07T08:00:00Z")
        val later = first.plusSeconds(1)

        try {
            insert(party, ids[0], first, null)
            insert(party, ids[1], first, first)
            insert(party, ids[2], first, null)
            insert(party, ids[3], later, null)
            insert(otherParty, ids[4], later.plusSeconds(1), null)

            val firstPage = onVertxContext { repository.pageByParty(party, 0, 2) }
            val secondPage = onVertxContext { repository.pageByParty(party, 1, 2) }
            val otherPage = onVertxContext { repository.pageByParty(otherParty, 0, 2) }

            assertThat(firstPage.first.map { it.notificationId }).containsExactly(ids[3], ids[2])
            assertThat(secondPage.first.map { it.notificationId }).containsExactly(ids[1], ids[0])
            assertThat(firstPage.second).isEqualTo(4)
            assertThat(firstPage.third).isEqualTo(3)
            assertThat(secondPage.second).isEqualTo(4)
            assertThat(secondPage.third).isEqualTo(3)
            assertThat(otherPage.first.map { it.notificationId }).containsExactly(ids[4])
            assertThat(otherPage.second).isEqualTo(1)
            assertThat(otherPage.third).isEqualTo(1)
        } finally {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM notifications WHERE party_id IN (?, ?)").use { statement ->
                    statement.setObject(1, party)
                    statement.setObject(2, otherParty)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun insert(party: UUID, id: UUID, createdAt: Instant, readAt: Instant?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO notifications
                    (notification_id, party_id, channel, template, recipient, body, status, created_at, read_at)
                    VALUES (?, ?, 'EMAIL', 'TEST', 'test@example.com', 'test', 'SENT', ?, ?)""",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, party)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.setTimestamp(4, readAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
        }
    }
}
