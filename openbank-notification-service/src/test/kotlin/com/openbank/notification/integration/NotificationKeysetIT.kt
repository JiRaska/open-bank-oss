// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
class NotificationKeysetIT {
    @Inject lateinit var repository: NotificationRepository

    @Inject lateinit var dataSource: DataSource

    @Test
    fun `new arrivals do not shift party history after cursor`() {
        val party = UUID.randomUUID()
        val older = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val sameSecondOlder = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val anchor = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val later = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val at = Instant.parse("2026-10-08T10:00:00Z")
        insert(party, older, at.minusSeconds(1))
        insert(party, anchor, at)
        val firstPage = onVertxContext { repository.pageByParty(party, 0, 1) }
        assertThat(firstPage.first.map { it.notificationId }).containsExactly(anchor)

        insert(party, sameSecondOlder, at)
        insert(party, later, at.plusSeconds(1))

        val before = onVertxContext { repository.pageByPartyBefore(party, at, anchor, 20) }
        assertThat(before.first.map { it.notificationId }).containsExactly(sameSecondOlder, older)
        assertThat(before.second).isEqualTo(4)
        assertThat(before.third).isEqualTo(4)
    }

    private fun insert(party: UUID, id: UUID, createdAt: Instant) {
        val sql = """
            INSERT INTO notifications (notification_id, party_id, channel, template, recipient, body, status, created_at)
            VALUES (?, ?, 'EMAIL', 'TEST', 'example@example.com', 'test', 'SENT', ?)
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, party)
                statement.setTimestamp(3, Timestamp.from(createdAt))
                statement.executeUpdate()
            }
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }
}
