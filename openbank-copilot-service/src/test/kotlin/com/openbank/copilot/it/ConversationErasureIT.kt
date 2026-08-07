// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.it

import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Proves copilot conversation history can actually be ERASED (#3870, GDPR Art. 17 / ADR-0117).
 *
 * ## Why every assertion is a direct JDBC query
 *
 * `ConversationStore.load` filters on `expires_at > now()`, so reading through it cannot distinguish
 * "the row was deleted" from "the row is still there but no longer served" — and that distinction is
 * the entire defect being fixed. A test that asserted `load(...).isEmpty()` would pass against the
 * pre-fix code, where nothing ever deleted anything. So the fixtures are written through the store
 * (the real production write path) and every verdict is read straight out of `conversation_history`.
 *
 * RED against the code before this change: `deleteForCustomer` / `deleteConversation` did not exist
 * on the port at all, so these tests did not compile — and once stubbed to no-ops they fail on the
 * row count, which is the assertion that matters.
 *
 * No real conversation content appears here; the fixtures are literal placeholder strings.
 */
@QuarkusTest
@QuarkusTestResource(CopilotPostgresTestResource::class)
class ConversationErasureIT {

    @Inject
    lateinit var store: ConversationStore

    private val customer = "customer-${UUID.randomUUID()}"
    private val other = "customer-${UUID.randomUUID()}"

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    /** Counts rows in the table itself — never through the store, which hides expired rows. */
    private fun rowsFor(customerId: String): Int {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement("SELECT count(*) FROM conversation_history WHERE customer_id = ?").use { ps ->
                ps.setString(1, customerId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun seed(customerId: String, conversationId: String) {
        store.append(customerId, conversationId, listOf(ChatMessage(ChatRole.USER, "placeholder-turn")))
    }

    @Test
    fun `erasing a customer removes their rows from the table, not just from the read path`() {
        seed(customer, "conv-a")
        seed(customer, "conv-b")
        assertThat(rowsFor(customer))
            .describedAs("fixture must exist before erasure, or the test proves nothing")
            .isEqualTo(2)

        val erased = onEventLoop { store.deleteForCustomer(customer) }

        assertThat(erased).isEqualTo(2L)
        assertThat(rowsFor(customer))
            .describedAs(
                "PARTY_ERASED must leave NO conversation_history row for the party — before #3870 " +
                    "the 90-day expires_at only stopped the row being served, it stayed on disk " +
                    "and in every base backup indefinitely",
            )
            .isZero()
    }

    @Test
    fun `erasing one customer leaves another customer's history untouched`() {
        seed(customer, "conv-a")
        seed(other, "conv-a")

        onEventLoop { store.deleteForCustomer(customer) }

        assertThat(rowsFor(customer)).isZero()
        assertThat(rowsFor(other))
            .describedAs("erasure is scoped by customer_id and must never widen across customers")
            .isEqualTo(1)
    }

    @Test
    fun `erasing a single conversation removes only that row`() {
        seed(customer, "conv-a")
        seed(customer, "conv-b")

        val erased = onEventLoop { store.deleteConversation(customer, "conv-a") }

        assertThat(erased).isEqualTo(1L)
        assertThat(rowsFor(customer)).isEqualTo(1)
    }

    @Test
    fun `deleteExpired removes a past-expiry row that load already refused to serve`() {
        seed(customer, "conv-a")
        // Backdate expires_at directly: append() always writes now + 90 days, so this is the only
        // way to produce the state the sweep exists for. This is also the state that proves the
        // read-side filter is not deletion — load() is empty here while the row is still present.
        expireRow(customer)
        assertThat(store.load(customer, "conv-a"))
            .describedAs("an expired conversation is already unreadable — that is NOT erasure")
            .isEmpty()
        assertThat(rowsFor(customer))
            .describedAs("…and the row is still very much on disk")
            .isEqualTo(1)

        val deleted = onEventLoop { store.deleteExpired(Instant.now()) }

        assertThat(deleted).isGreaterThanOrEqualTo(1L)
        assertThat(rowsFor(customer)).isZero()
    }

    private fun expireRow(customerId: String) {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(
                "UPDATE conversation_history SET expires_at = now() - interval '1 day' WHERE customer_id = ?",
            ).use { ps ->
                ps.setString(1, customerId)
                ps.executeUpdate()
            }
        }
    }
}
