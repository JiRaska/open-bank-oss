// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.persistence

import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.it.PGVECTOR_IMAGE
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer

/**
 * copilot conversation memory T1 (#3710): pins that PostgresConversationStore durably stores, retrieves and trims messages.
 * Runs against a real Postgres — the only way to verify Flyway created the table correctly.
 */
@QuarkusTest
@QuarkusTestResource(PostgresConversationStoreIT.Resource::class)
class PostgresConversationStoreIT {

    class Resource : QuarkusTestResourceLifecycleManager {
        private lateinit var postgres: PostgreSQLContainer<*>
        override fun start(): Map<String, String> {
            postgres = PostgreSQLContainer(PGVECTOR_IMAGE)
                .withDatabaseName("openbank_copilot")
                .withUsername("openbank")
                .withPassword("openbank")
            postgres.start()
            TestInfrastructureEvidence.record("postgres", PGVECTOR_IMAGE.asCanonicalNameString(), "started")
            return mapOf(
                "quarkus.datasource.reactive.url" to
                    "postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}",
                "quarkus.datasource.jdbc.url" to postgres.jdbcUrl,
                "quarkus.datasource.username" to postgres.username,
                "quarkus.datasource.password" to postgres.password,
                "quarkus.datasource.active" to "true",
                "quarkus.flyway.enabled" to "true",
                "quarkus.hibernate-orm.active" to "true",
            )
        }
        override fun stop() {
            if (::postgres.isInitialized) {
                postgres.stop()
                TestInfrastructureEvidence.record("postgres", PGVECTOR_IMAGE.asCanonicalNameString(), "stopped")
            }
        }
    }

    @jakarta.inject.Inject
    lateinit var store: ConversationStore

    private val user = "party-${java.util.UUID.randomUUID()}"
    private val conv = "conv-${java.util.UUID.randomUUID()}"

    @BeforeEach
    fun clean() {
        // Each test gets a fresh (user, conv) pair via the UUID suffix — no teardown needed.
    }

    @Test
    fun `empty conversation returns no messages`() {
        assertThat(store.load(user, conv)).isEmpty()
    }

    @Test
    fun `appended messages are returned in order`() {
        val m1 = ChatMessage(ChatRole.USER, "hello")
        val m2 = ChatMessage(ChatRole.ASSISTANT, "hi there")
        store.append(user, conv, listOf(m1, m2))
        val loaded = store.load(user, conv)
        assertThat(loaded).hasSize(2)
        assertThat(loaded[0].content).isEqualTo("hello")
        assertThat(loaded[1].content).isEqualTo("hi there")
    }

    @Test
    fun `subsequent appends accumulate and trim to MAX_MESSAGES`() {
        repeat(12) { i ->
            store.append(user, conv, listOf(ChatMessage(ChatRole.USER, "msg-$i")))
        }
        val loaded = store.load(user, conv)
        assertThat(loaded.size).isLessThanOrEqualTo(ConversationStore.MAX_MESSAGES)
        assertThat(loaded.last().content).isEqualTo("msg-11")
    }

    @Test
    fun `the NO_MEMORY_ID sentinel is never persisted`() {
        store.append(user, ConversationStore.NO_MEMORY_ID, listOf(ChatMessage(ChatRole.USER, "should not persist")))
        assertThat(store.load(user, ConversationStore.NO_MEMORY_ID)).isEmpty()
    }
}
