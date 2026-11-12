// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.smallrye.reactive.messaging.memory.InMemorySource
import jakarta.enterprise.inject.Any
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * GDPR Art. 17 (#15755): a real boot, a real Postgres, the real Kafka consumer (channel switched
 * to the in-memory connector, so the SmallRye dispatch path and its Vert.x context are the real
 * ones) and the real reactive repositories. Asserts on the rows with plain JDBC.
 *
 * Why not call the service: a reactive Panache repository cannot be called from a bare test
 * thread (no Vert.x context), and a mocked repository would pass against an erase that deletes
 * nothing. Only the event path proves the identifier and its blind index are actually gone.
 */
@QuarkusTest
@QuarkusTestResource(PartyErasureIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class PartyErasureIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchIncomingChannelsToInMemory("party-events-in") +
                InMemoryConnector.switchOutgoingChannelsToInMemory("party-events-out", "pid-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Any
    @Inject
    lateinit var connector: InMemoryConnector

    @Inject
    lateinit var dataSource: AgroalDataSource

    private fun source(): InMemorySource<String> =
        connector.source<String>("party-events-in").also { it.runOnVertxContext(true) }

    private fun <T> jdbc(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun seedParty(id: UUID, blindIndex: String) = jdbc { c ->
        c.prepareStatement(
            """INSERT INTO parties (id, party_type, status, given_name, family_name, birthdate,
                 birth_number_encrypted, gender, birthplace, nationalities, verification_source, verified_at,
                 email, phone, data_box_id, kyc_level, permanent_address_street, permanent_address_city,
                 case_metadata, created_at, updated_at)
               VALUES (?, 'NATURAL_PERSON', 'ACTIVE', 'Jana', 'Novakova', DATE '1985-03-04',
                 'CIPHERTEXT-OF-RC', 'FEMALE', 'Brno', ARRAY['CZ'], 'BANKID', now(),
                 'jana@example.test', '+420000000000', 'abc1234', 'BASIC', 'Hlavni', 'Brno',
                 '{"note":"free text"}', now(), now())""",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO party_external_ids (party_id, id_type, id_value) " +
                "VALUES (?, 'BIRTH_NUMBER', ?), (?, 'KEYCLOAK_ID', ?)",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, blindIndex)
            ps.setObject(3, id)
            ps.setString(4, "kc-$id")
            ps.executeUpdate()
        }
        c.prepareStatement(
            "INSERT INTO party_id_documents (party_id, doc_type, doc_number, issuing_country) " +
                "VALUES (?, 'PASSPORT', '12345678', 'CZ')",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeUpdate()
        }
        c.prepareStatement(
            """INSERT INTO party_relationships (id, party_id, role, status, onboarding_channel, termination_reason)
               VALUES (?, ?, 'CUSTOMER', 'TERMINATED', 'WEB', 'customer said something personal')""",
        ).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, id)
            ps.executeUpdate()
        }
    }

    private fun seedCase(id: UUID, linkedParty: UUID?, blindIndex: String) = jdbc { c ->
        c.prepareStatement(
            """INSERT INTO identity_verification_case (id, dedup_key, trigger, status, applicant_given_name,
                 applicant_family_name, applicant_birthdate, applicant_birthplace, applicant_nationalities,
                 blind_index, final_link_party_id, first_notes, created_at, updated_at)
               VALUES (?, ?, 'RN_COLLISION', 'DECIDED', 'Jana', 'Novakova', DATE '1985-03-04', 'Brno', 'CZ',
                 ?, ?, 'operator note', now(), now())""",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, "RN:$blindIndex")
            ps.setString(3, blindIndex)
            ps.setObject(4, linkedParty)
            ps.executeUpdate()
        }
    }

    private data class PartyRow(
        val given: String,
        val family: String,
        val birthdate: String,
        val encrypted: String?,
        val email: String?,
        val status: String,
        val erasedAt: String?,
        val version: Long,
        val updatedAt: String,
    )

    private fun party(id: UUID): PartyRow = jdbc { c ->
        c.prepareStatement(
            "SELECT given_name, family_name, birthdate::text, birth_number_encrypted, email, status, " +
                "erased_at::text, version, updated_at::text FROM parties WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "party $id missing" }
                PartyRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
                )
            }
        }
    }

    private fun count(sql: String, id: UUID): Int = jdbc { c ->
        c.prepareStatement(sql).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun await(ready: () -> Boolean): Boolean {
        repeat(ATTEMPTS) {
            if (ready()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return ready()
    }

    private fun erased(id: UUID) = """{"eventType":"PARTY_ERASED","partyId":"$id","erasedAt":"2026-11-12T10:00:00Z"}"""

    private fun assertErased(victim: UUID, victimCase: UUID): PartyRow {
        val after = party(victim)
        assertThat(after.encrypted).describedAs("encrypted national identifier").isNull()
        assertThat(after.given).isEqualTo("GDPR_ERASED")
        assertThat(after.family).isEqualTo("GDPR_ERASED")
        assertThat(after.birthdate).isEqualTo("1900-01-01")
        assertThat(after.email).isNull()
        assertThat(after.status).isEqualTo("TERMINATED")
        assertThat(count("SELECT count(*) FROM party_external_ids WHERE party_id = ?", victim))
            .describedAs("RČ blind index and every other external id").isZero()
        assertThat(count("SELECT count(*) FROM party_id_documents WHERE party_id = ?", victim)).isZero()
        assertThat(
            count(
                "SELECT count(*) FROM party_relationships WHERE party_id = ? AND termination_reason IS NOT NULL",
                victim,
            ),
        ).isZero()
        assertThat(
            count(
                "SELECT count(*) FROM parties WHERE id = ? AND (phone IS NOT NULL OR gender IS NOT NULL " +
                    "OR birthplace IS NOT NULL OR data_box_id IS NOT NULL OR permanent_address_city IS NOT NULL " +
                    "OR case_metadata IS NOT NULL OR cardinality(nationalities) > 0)",
                victim,
            ),
        ).describedAs("remaining personal columns").isZero()
        assertThat(
            count(
                "SELECT count(*) FROM identity_verification_case WHERE id = '$victimCase' AND blind_index IS NULL " +
                    "AND applicant_family_name = 'GDPR_ERASED' AND first_notes IS NULL AND erased_at IS NOT NULL " +
                    "AND ?::uuid IS NOT NULL",
                victim,
            ),
        ).describedAs("linked verification case anonymised").isEqualTo(1)
        return after
    }

    private fun assertUntouched(bystander: UUID, bystanderCase: UUID) {
        val other = party(bystander)
        assertThat(other.encrypted).isEqualTo("CIPHERTEXT-OF-RC")
        assertThat(other.family).isEqualTo("Novakova")
        assertThat(other.erasedAt).isNull()
        assertThat(count("SELECT count(*) FROM party_external_ids WHERE party_id = ?", bystander)).isEqualTo(2)
        assertThat(
            count(
                "SELECT count(*) FROM identity_verification_case WHERE id = '$bystanderCase' " +
                    "AND blind_index IS NOT NULL AND erased_at IS NULL AND ?::uuid IS NOT NULL",
                bystander,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `PARTY_ERASED removes identifier and blind index and personal columns, redelivery is a no-op`() {
        val victim = UUID.randomUUID()
        val bystander = UUID.randomUUID()
        val barrier = UUID.randomUUID()
        val victimCase = UUID.randomUUID()
        val bystanderCase = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        seedParty(bystander, "blind-bystander-$bystander")
        seedParty(barrier, "blind-barrier-$barrier")
        seedCase(victimCase, victim, "blind-victim-$victim")
        seedCase(bystanderCase, bystander, "blind-bystander-$bystander")
        assertThat(party(victim).encrypted).isEqualTo("CIPHERTEXT-OF-RC")

        val source = source()
        source.send("""{"eventType":"PARTY_CREATED","partyId":"$bystander"}""") // other type: ignored
        source.send(erased(victim))

        assertThat(await { party(victim).erasedAt != null })
            .describedAs("the consumer never erased the party").isTrue()

        val after = assertErased(victim, victimCase)

        // Second delivery: nothing changes (erased_at, version and updated_at are untouched), and the
        // barrier party sent after it proves the consumer processed the redelivery.
        source.send(erased(victim))
        source.send(erased(barrier))
        assertThat(await { party(barrier).erasedAt != null }).isTrue()
        assertThat(party(victim)).describedAs("a re-delivery must not touch the row again").isEqualTo(after)

        assertUntouched(bystander, bystanderCase)
    }

    @Test
    fun `an event for a party this service never indexed is acked without error`() {
        val unknown = UUID.randomUUID()
        val barrier = UUID.randomUUID()
        seedParty(barrier, "blind-barrier-$barrier")

        val source = source()
        source.send(erased(unknown))
        source.send(erased(barrier))

        assertThat(await { party(barrier).erasedAt != null })
            .describedAs("an unknown party must not wedge the channel").isTrue()
    }

    private companion object {
        const val ATTEMPTS = 100
        const val POLL_MILLIS = 100L
    }
}
