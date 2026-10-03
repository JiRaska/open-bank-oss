// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.integration

import com.openbank.context.infrastructure.LendingAssignedCandidatesRepository
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_context_it")],
)
@QuarkusTestResource(ContextMessagingTestResource::class)
class LendingAssignedCandidatesIT {
    @Inject lateinit var repository: LendingAssignedCandidatesRepository

    @Test
    fun `returns only current exact root assignments for same investigator`() {
        val now = Instant.now()
        val root = UUID.randomUUID()
        val eligible = UUID.randomUUID()
        val otherActor = UUID.randomUUID()
        val expired = UUID.randomUUID()
        val future = UUID.randomUUID()
        val revoked = UUID.randomUUID()
        val wrongPurpose = UUID.randomUUID()
        val wrongRoot = UUID.randomUUID()
        seed(root, ACTOR, PURPOSE, "lending-loan:$root", now.minusSeconds(60), now.plusSeconds(3600))
        seed(eligible, ACTOR, PURPOSE, "lending-loan:$eligible", now.minusSeconds(60), now.plusSeconds(3600))
        seed(
            otherActor,
            "other-actor",
            PURPOSE,
            "lending-loan:$otherActor",
            now.minusSeconds(60),
            now.plusSeconds(3600),
        )
        seed(expired, ACTOR, PURPOSE, "lending-loan:$expired", now.minusSeconds(3600), now.minusSeconds(1))
        seed(future, ACTOR, PURPOSE, "lending-loan:$future", now.plusSeconds(1), now.plusSeconds(3600))
        seed(revoked, ACTOR, PURPOSE, "lending-loan:$revoked", now.minusSeconds(3600), now.plusSeconds(3600))
        revoke(revoked, now.minusSeconds(1))
        seed(
            wrongPurpose,
            ACTOR,
            "FRAUD_INVESTIGATION",
            "lending-loan:$wrongPurpose",
            now.minusSeconds(60),
            now.plusSeconds(3600),
        )
        seed(
            wrongRoot,
            ACTOR,
            PURPOSE,
            "lending-loan:${UUID.randomUUID()}",
            now.minusSeconds(60),
            now.plusSeconds(3600),
        )
        seedRaw("NOT-A-UUID", ACTOR, PURPOSE, "lending-loan:NOT-A-UUID", now.minusSeconds(60), now.plusSeconds(3600))

        val result = onVertx { repository.assignedCandidates(root, ACTOR, now) }
        assertThat(result.ids).containsExactly(eligible)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `caps distinct sorted loan IDs and reports truncation`() {
        val now = Instant.now()
        val root = UUID.randomUUID()
        val ids = List(257) { UUID.randomUUID() }
        ids.forEach { seed(it, CAP_ACTOR, PURPOSE, "lending-loan:$it", now.minusSeconds(60), now.plusSeconds(3600)) }
        seed(
            ids.first(),
            CAP_ACTOR,
            PURPOSE,
            "lending-loan:${ids.first()}",
            now.minusSeconds(61),
            now.plusSeconds(3600),
        )

        val result = onVertx { repository.assignedCandidates(root, CAP_ACTOR, now) }
        assertThat(result.ids).hasSize(256)
        assertThat(result.ids.map(UUID::toString)).isSorted()
        assertThat(result.truncated).isTrue()
        assertThat(result.ids).doesNotContain(root)
    }

    private fun seed(id: UUID, principal: String, purpose: String, rootRef: String, from: Instant, to: Instant) =
        seedRaw(id.toString(), principal, purpose, rootRef, from, to)

    private fun seedRaw(id: String, principal: String, purpose: String, rootRef: String, from: Instant, to: Instant) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """INSERT INTO context_case_assignments
                   (assignment_id, bank_scope, principal_id, case_id, purpose, root_ref, valid_from, valid_to, created_at)
                   VALUES (?, 'openbank-cz', ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, principal)
                statement.setString(3, id)
                statement.setString(4, purpose)
                statement.setString(5, rootRef)
                statement.setTimestamp(6, Timestamp.from(from))
                statement.setTimestamp(7, Timestamp.from(to))
                statement.setTimestamp(8, Timestamp.from(now()))
                statement.executeUpdate()
            }
        }
    }

    private fun revoke(id: UUID, at: Instant) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                "UPDATE context_case_assignments SET valid_to = ? WHERE case_id = ? AND principal_id = ?",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(at))
                statement.setString(2, id.toString())
                statement.setString(3, ACTOR)
                assertThat(statement.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun <T> onVertx(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun now() = Instant.now()

    private companion object {
        const val ACTOR = "lending-candidate-investigator"
        const val CAP_ACTOR = "lending-cap-investigator"
        const val PURPOSE = "LENDING_EXPOSURE_REVIEW"
    }
}
