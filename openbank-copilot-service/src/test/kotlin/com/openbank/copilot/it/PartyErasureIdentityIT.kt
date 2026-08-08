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
import java.util.UUID

/**
 * The half of #3881 that [ConversationErasureIT] cannot see: erasure keyed on an identity that is
 * **not** the storage key.
 *
 * `PARTY_ERASED` carries `partyId`; copilot stores history under the OIDC `sub`. Measured against
 * the deployed customers realm those are equal for 0 of 35 users, so a consumer that deletes on
 * `customer_id = partyId` receives the event, deletes zero rows, and logs
 * `erased 0 copilot conversation(s)` at INFO. Every test in [ConversationErasureIT] passes against
 * that code, because each of them erases using the very id it seeded with — the mismatch is
 * invisible to any test whose two identities are the same string.
 *
 * So the fixtures here deliberately use `sub != partyId`, and every verdict is a direct JDBC query:
 * reading through the store cannot distinguish "deleted" from "filtered by `expires_at`", which is
 * the distinction the whole issue turns on.
 *
 * RED against the unfixed code: with `party_id` neither stored nor matched,
 * `an erasure keyed on partyId removes history stored under a different sub` fails on
 * `expected 0 but was 1` — the row survives its own erasure.
 *
 * No real conversation content appears here; the fixtures are literal placeholder strings.
 */
@QuarkusTest
@QuarkusTestResource(CopilotPostgresTestResource::class)
class PartyErasureIdentityIT {

    @Inject
    lateinit var store: ConversationStore

    /** The Keycloak-minted `sub` copilot keys history on. */
    private val subject = "sub-${UUID.randomUUID()}"

    /** The party id the erasure event will carry — a different value, as it is for every live user. */
    private val partyId = UUID.randomUUID().toString()

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun <T> query(sql: String, arg: String, read: (java.sql.ResultSet) -> T): T {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, arg)
                ps.executeQuery().use { rs ->
                    rs.next()
                    return read(rs)
                }
            }
        }
    }

    private fun rowsForSubject(sub: String): Int =
        query("SELECT count(*) FROM conversation_history WHERE customer_id = ?", sub) { it.getInt(1) }

    private fun storedPartyId(sub: String): String? =
        query("SELECT party_id FROM conversation_history WHERE customer_id = ?", sub) { it.getString(1) }

    @Test
    fun `the party id is captured on the row at write time, not resolved at delete time`() {
        store.append(subject, "conv-a", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")), partyId)

        assertThat(storedPartyId(subject))
            .describedAs(
                "the erasure identity must be on the row: at erasure time the Keycloak user is " +
                    "gone, so nothing can map partyId back to this sub afterwards",
            )
            .isEqualTo(partyId)
    }

    @Test
    fun `an erasure keyed on partyId removes history stored under a different sub`() {
        store.append(subject, "conv-a", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")), partyId)
        store.append(subject, "conv-b", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")), partyId)
        assertThat(rowsForSubject(subject))
            .describedAs("fixture must exist before erasure, or the test proves nothing")
            .isEqualTo(2)

        val erased = onEventLoop { store.deleteForParty(partyId) }

        assertThat(erased)
            .describedAs("a count of 0 here IS the bug — the consumer logs that as success")
            .isEqualTo(2L)
        assertThat(rowsForSubject(subject))
            .describedAs(
                "GDPR Art. 17: no conversation_history row may survive erasure of the party that " +
                    "wrote it, even though the row is keyed on sub and the event carried partyId",
            )
            .isZero()
    }

    @Test
    fun `a row written with no party id is still erasable where sub is the party id (ADR-0069)`() {
        val adr0069Subject = UUID.randomUUID().toString()
        store.append(adr0069Subject, "conv-a", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")))
        assertThat(storedPartyId(adr0069Subject)).isNull()

        val erased = onEventLoop { store.deleteForParty(adr0069Subject) }

        assertThat(erased).isEqualTo(1L)
        assertThat(rowsForSubject(adr0069Subject)).isZero()
    }

    @Test
    fun `erasing one party never reaches another party's rows`() {
        val otherSubject = "sub-${UUID.randomUUID()}"
        val otherParty = UUID.randomUUID().toString()
        store.append(subject, "conv-a", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")), partyId)
        store.append(otherSubject, "conv-a", listOf(ChatMessage(ChatRole.USER, "placeholder-turn")), otherParty)

        onEventLoop { store.deleteForParty(partyId) }

        assertThat(rowsForSubject(subject)).isZero()
        assertThat(rowsForSubject(otherSubject))
            .describedAs("matching either identity column must not widen erasure across parties")
            .isEqualTo(1)
    }
}
