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
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Proves the retention sweep runs *as dispatched by the scheduler* and actually removes rows
 * (#3870).
 *
 * ## Why this drives the real cron instead of calling the method
 *
 * The failure mode this guards against is in how the framework invokes the method, not in the body:
 * a plain (non-`suspend`) `@Scheduled` method is run on a bare `executor-thread` with no Vert.x
 * context, so the reactive Panache delete throws `HR000068` and the tick aborts silently having done
 * nothing — the defect that left five schedulers in this repo never running (#2148, #2187). Calling
 * `sweepExpiredConversations()` from a test supplies the very context the scheduler does not, so
 * such a test passes against broken code and proves nothing.
 *
 * The profile below shrinks the cron to every two seconds against a real Postgres, and the test
 * waits for a genuinely scheduler-dispatched run to have removed a past-expiry row.
 *
 * Ids are literals, not randomized in a companion object: a [QuarkusTestProfile] loads in a
 * different classloader from the test class, so a companion initializes twice and a generated id
 * would differ between the fixture and the assertion.
 */
@QuarkusTest
@QuarkusTestResource(CopilotPostgresTestResource::class)
@TestProfile(ConversationRetentionSweepIT.FastSweepProfile::class)
class ConversationRetentionSweepIT {

    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "copilot.retention.conversation.cron" to "*/2 * * * * ?",
            "copilot.retention.conversation.enabled" to "true",
        )
    }

    @Inject
    lateinit var store: ConversationStore

    @Test
    fun `the scheduled sweep hard-deletes a past-expiry conversation`() {
        store.append(CUSTOMER, CONVERSATION, listOf(ChatMessage(ChatRole.USER, "placeholder-turn")))
        assertThat(expireRow())
            .describedAs("fixture must be present and expired before the sweep, or nothing is proven")
            .isEqualTo(1)

        val swept = await { rows() == 0 }

        assertThat(swept)
            .describedAs(
                "a scheduler-dispatched sweep must remove the row. Never removing one means the " +
                    "tick threw HR000068 off the Vert.x context before the first query (#2148/#2187), " +
                    "leaving the 90-day TTL a read filter rather than a retention guarantee",
            )
            .isTrue()
    }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    private fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    /** Read the table directly — `load` hides an expired row whether or not it was ever deleted. */
    private fun rows(): Int = withConnection { conn ->
        conn.prepareStatement("SELECT count(*) FROM conversation_history WHERE customer_id = ?").use { ps ->
            ps.setString(1, CUSTOMER)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun expireRow() = withConnection { conn ->
        conn.prepareStatement(
            "UPDATE conversation_history SET expires_at = now() - interval '1 day' WHERE customer_id = ?",
        ).use { ps ->
            ps.setString(1, CUSTOMER)
            ps.executeUpdate()
        }
    }

    private companion object {
        const val CUSTOMER = "sweep-it-customer"
        const val CONVERSATION = "sweep-it-conversation"

        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
