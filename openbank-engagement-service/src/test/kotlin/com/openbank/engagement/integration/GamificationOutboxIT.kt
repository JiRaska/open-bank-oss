// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Proves ADR-0220 D3's gamification award actually reaches the transactional outbox with the
 * required audit-attribution shape — real Postgres (`EngagementPostgresTestResource`), real HTTP
 * request (`RecordEngagementEventUseCase`'s Hibernate Reactive Panache repositories need a real
 * Vert.x context, same reasoning `SurfaceRestContractIT`'s own KDoc documents), real transaction:
 * the award row and its outbox row are asserted from the SAME commit the REST call produced, not
 * from a mock. Kafka itself is switched to in-memory (no broker in this IT's stack, same as
 * `SurfaceRestContractIT`) — the payload assertions below are what prove dispatch-readiness, the
 * same technique `SurfaceRestContractIT.readCampaignAttribution` already uses to prove an outbox
 * write without needing a live broker.
 */
@QuarkusTest
@TestSecurity(user = "edge@openbank.test", roles = ["ROLE_OPERATOR"])
@QuarkusTestResource(SurfaceRestContractIT.NoKafkaResource::class)
@QuarkusTestResource(EngagementPostgresTestResource::class)
class GamificationOutboxIT {

    private val mapper = ObjectMapper()

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a genuine challenge completion by an opted-in party writes an award and an attributed outbox row`() {
        val party = UUID.randomUUID()
        insertOptedInMembership(party)

        postConversion(party, "COMPLETE_BUDGETING_COURSE")

        val award = readLatestAward(party)
        assertThat(award).isNotNull
        assertThat(award!!.challengeId).isEqualTo("COMPLETE_BUDGETING_COURSE")
        assertThat(award.earnSourceId).isEqualTo("EDUCATIONAL_CONTENT_COMPLETION")
        assertThat(award.points).isEqualTo(50)
        assertThat(award.ruleVersion).isEqualTo("v1")

        val outboxPayload = readLatestOutboxPayload(party, eventTypePrefix = "GamificationAward.")
        assertThat(outboxPayload).isNotNull
        val payload = mapper.readTree(outboxPayload)
        assertThat(payload.get("earnSourceId").asText()).isEqualTo("EDUCATIONAL_CONTENT_COMPLETION")
        assertThat(payload.get("ruleVersion").asText()).isEqualTo("v1")
        assertThat(payload.get("correlationEventId").asText()).isEqualTo(award.correlationEventId.toString())
        assertThat(payload.get("actorType").asText()).isEqualTo("SYSTEM")
        assertThat(payload.get("actorId").asText()).isEqualTo("system:engagement:gamification-award-rule")
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `a party who never opted in writes no award and no gamification outbox row`() {
        val party = UUID.randomUUID()
        // Deliberately no insertOptedInMembership call.

        postConversion(party, "COMPLETE_BUDGETING_COURSE")

        assertThat(readLatestAward(party)).isNull()
        assertThat(readLatestOutboxPayload(party, eventTypePrefix = "GamificationAward.")).isNull()
    }

    @Test
    @TestSecurity(user = TEST_OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `posting the same conversion twice awards only once`() {
        val party = UUID.randomUUID()
        insertOptedInMembership(party)

        postConversion(party, "COMPLETE_BUDGETING_COURSE")
        postConversion(party, "COMPLETE_BUDGETING_COURSE")

        assertThat(countAwards(party)).isEqualTo(1)
    }

    private fun postConversion(party: UUID, contentId: String) {
        Given {
            contentType("application/json")
            body("""{"partyId":"$party","contentId":"$contentId","slot":"REWARDS_HUB","type":"CONVERSION"}""")
        } When {
            post("/api/v1/surfaces/events")
        } Then {
            statusCode(202)
        }
    }

    private fun insertOptedInMembership(party: UUID) {
        openTestDatabase().use { conn ->
            conn.prepareStatement(
                "INSERT INTO rewards_hub_membership (party_id, state, since) VALUES (?, ?, ?)",
            ).use { st ->
                st.setObject(1, party)
                st.setString(2, "OPTED_IN")
                st.setTimestamp(3, Timestamp.from(Instant.now()))
                st.executeUpdate()
            }
        }
    }

    private fun countAwards(party: UUID): Int = openTestDatabase().use { conn ->
        conn.prepareStatement("SELECT count(*) FROM gamification_award WHERE party_id = ?").use { st ->
            st.setObject(1, party)
            st.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun readLatestAward(party: UUID): StoredAward? = openTestDatabase().use { conn ->
        conn.prepareStatement(
            """
            SELECT challenge_id, earn_source_id, points, rule_version, correlation_event_id
            FROM gamification_award WHERE party_id = ? ORDER BY occurred_at DESC LIMIT 1
            """.trimIndent(),
        ).use { st ->
            st.setObject(1, party)
            st.executeQuery().use(::mapStoredAward)
        }
    }

    private fun mapStoredAward(rows: ResultSet): StoredAward? {
        if (!rows.next()) return null
        return StoredAward(
            challengeId = rows.getString("challenge_id"),
            earnSourceId = rows.getString("earn_source_id"),
            points = rows.getInt("points"),
            ruleVersion = rows.getString("rule_version"),
            correlationEventId = rows.getObject("correlation_event_id", UUID::class.java),
        )
    }

    private fun readLatestOutboxPayload(party: UUID, eventTypePrefix: String): String? =
        openTestDatabase().use { conn ->
            conn.prepareStatement(
                """
                SELECT payload FROM engagement_outbox
                WHERE aggregate_id = ? AND event_type LIKE ?
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
            ).use { st ->
                st.setObject(1, party)
                st.setString(2, "$eventTypePrefix%")
                st.executeQuery().use(::mapPayload)
            }
        }

    private fun mapPayload(rows: ResultSet): String? = if (rows.next()) rows.getString("payload") else null

    private fun openTestDatabase(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val TEST_OPERATOR = "00000000-0000-0000-0000-000000000098"
    }

    private data class StoredAward(
        val challengeId: String,
        val earnSourceId: String,
        val points: Int,
        val ruleVersion: String,
        val correlationEventId: UUID,
    )
}
