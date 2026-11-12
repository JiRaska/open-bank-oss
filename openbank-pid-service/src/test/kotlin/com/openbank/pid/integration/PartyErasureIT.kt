// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.integration

import com.openbank.libs.identity.MatchKey
import com.openbank.libs.testing.containers.PostgresTestResource
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
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
@TestSecurity(user = "operator@openbank.test", roles = ["ROLE_OPERATOR", "ROLE_API"])
@Suppress("LargeClass") // one real boot + Postgres + consumer shared by every erasure scenario
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

    @Inject
    lateinit var repository: com.openbank.pid.infrastructure.persistence.repository.VerificationCaseRepositoryImpl

    @Inject
    lateinit var partyService: com.openbank.pid.application.usecase.PartyService

    private fun <T> onEventLoop(block: suspend () -> T): T = io.quarkus.vertx.VertxContextSupport.subscribeAndAwait {
        io.smallrye.mutiny.coroutines.uni(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        ) {
            block()
        }
    }

    private fun source(): InMemorySource<String> =
        connector.source<String>("party-events-in").also { it.runOnVertxContext(true) }

    private fun <T> jdbc(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun seedParty(id: UUID, blindIndex: String, family: String = "Novakova") = jdbc { c ->
        c.prepareStatement(
            """INSERT INTO parties (id, party_type, status, given_name, family_name, birthdate,
                 birth_number_encrypted, gender, birthplace, nationalities, verification_source, verified_at,
                 email, phone, data_box_id, kyc_level, permanent_address_street, permanent_address_city,
                 case_metadata, case_id, case_type, case_status, case_last_actor, case_last_reason_code,
                 case_last_transition_at, created_at, updated_at)
               VALUES (?, 'NATURAL_PERSON', 'ACTIVE', 'Jana', ?, DATE '1985-03-04',
                 'CIPHERTEXT-OF-RC', 'FEMALE', 'Brno', ARRAY['CZ'], 'BANKID', now(),
                 'jana@example.test', '+420000000000', 'abc1234', 'BASIC', 'Hlavni', 'Brno',
                 '{"note":"free text"}', gen_random_uuid(), 'PID_VERIFICATION', 'OPEN', 'pid:bankid:api',
                 'CREATED', now(), now(), now())""",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, family)
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
               VALUES (?, ?, 'CUSTOMER', 'TERMINATED', 'API', 'customer said something personal')""",
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

        // The id is tombstoned: a shell with no personal data, so a later registration is refused.
        val tombstone = party(unknown)
        assertThat(tombstone.erasedAt).isNotNull()
        assertThat(tombstone.given).isEqualTo("GDPR_ERASED")
        assertThat(tombstone.encrypted).isNull()
        assertThat(
            send(
                "POST",
                "/api/v1/parties/register-identity",
                """{"partyId":"$unknown","givenName":"Jana","familyName":"Novakova","birthdate":"1985-03-04"}""",
            ),
        ).isEqualTo(409)
        assertThat(party(unknown).given).isEqualTo("GDPR_ERASED")
    }

    private fun eraseAndAwait(id: UUID) {
        source().send(erased(id))
        assertThat(await { party(id).erasedAt != null }).describedAs("party never erased").isTrue()
    }

    private fun send(method: String, path: String, json: String): Int {
        val response = io.restassured.RestAssured.given()
            .contentType("application/json")
            .body(json)
            .request(method, path)
        check(response.statusCode() == 409) { "$method $path -> ${response.statusCode()} ${response.body.asString()}" }
        return response.statusCode()
    }

    @Test
    fun `writes to an erased party are refused with 409 and the row stays anonymised`() {
        val victim = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        eraseAndAwait(victim)
        val after = party(victim)

        assertThat(
            send(
                "POST",
                "/api/v1/parties/register-identity",
                """{"partyId":"$victim","givenName":"Jana","familyName":"Novakova","birthdate":"1985-03-04"}""",
            ),
        ).describedAs("register-identity").isEqualTo(409)
        assertThat(send("PATCH", "/api/v1/parties/$victim/contact", """{"email":"back@example.test"}"""))
            .describedAs("update contact").isEqualTo(409)
        assertThat(
            send("POST", "/api/v1/parties/$victim/external-ids", """{"type":"KEYCLOAK_ID","value":"kc-new-$victim"}"""),
        ).describedAs("link external id").isEqualTo(409)

        assertThat(party(victim)).describedAs("the refused writes must leave the row untouched").isEqualTo(after)
        assertThat(count("SELECT count(*) FROM party_external_ids WHERE party_id = ?", victim)).isZero()
    }

    @Test
    fun `a redelivery re-cleans a write that slipped in after the first erase and keeps the original stamp`() {
        val victim = UUID.randomUUID()
        val barrier = UUID.randomUUID()
        seedParty(barrier, "blind-barrier-$barrier")
        seedParty(victim, "blind-victim-$victim")
        eraseAndAwait(victim)
        val first = party(victim)

        // Simulate a write that bypassed the guard (a racing transaction, a manual fix).
        jdbc { c ->
            c.prepareStatement(
                "UPDATE parties SET email = 'slipped@example.test', given_name = 'Jana', " +
                    "birth_number_encrypted = 'SLIPPED' WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO party_external_ids (party_id, id_type, id_value) VALUES (?, 'KEYCLOAK_ID', 'slipped')",
            ).use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
        }
        assertThat(party(victim).email).isEqualTo("slipped@example.test")

        val source = source()
        source.send(erased(victim))
        source.send(erased(barrier))
        assertThat(await { party(barrier).erasedAt != null }).isTrue()

        val after = party(victim)
        assertThat(after.email).isNull()
        assertThat(after.given).isEqualTo("GDPR_ERASED")
        assertThat(after.encrypted).isNull()
        assertThat(after.erasedAt).describedAs("original erased_at kept").isEqualTo(first.erasedAt)
        assertThat(count("SELECT count(*) FROM party_external_ids WHERE party_id = ?", victim)).isZero()
    }

    /** Holds `SELECT ... FOR UPDATE` on the party row from a separate JDBC connection until [release]. */
    private inner class RowLock(id: UUID) {
        private val connection = dataSource.connection.also { it.autoCommit = false }

        init {
            connection.prepareStatement("SELECT 1 FROM parties WHERE id = ? FOR UPDATE").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().close()
            }
        }

        fun <T> inLockedTransaction(block: (Connection) -> T): T = block(connection)

        fun release() {
            connection.commit()
            connection.close()
        }
    }

    /** True once some backend is waiting on a lock — the deterministic "it is blocked" signal, no sleep. */
    private fun anyoneWaitingOnLock(): Boolean = jdbc { c ->
        c.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1) > 0
            }
        }
    }

    @Test
    fun `a write blocks behind the erase that holds the row lock and is then refused`() {
        val victim = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        val lock = RowLock(victim)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit<Int> {
                io.restassured.RestAssured.given().contentType("application/json")
                    .body("""{"email":"racer@example.test"}""")
                    .request("PATCH", "/api/v1/parties/$victim/contact").statusCode()
            }
            assertThat(await { anyoneWaitingOnLock() })
                .describedAs("the write must block on the party row lock, not read-then-write around it").isTrue()
            assertThat(write.isDone).isFalse()

            // The erase "commits" while the write waits.
            lock.inLockedTransaction { c ->
                c.prepareStatement(
                    "UPDATE parties SET erased_at = now(), email = NULL, given_name = 'GDPR_ERASED' WHERE id = ?",
                ).use { ps ->
                    ps.setObject(1, victim)
                    ps.executeUpdate()
                }
            }
            lock.release()

            assertThat(write.get(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                .describedAs("the write that waited must see erased_at under the lock").isEqualTo(409)
            assertThat(party(victim).email).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an erase blocks behind a write holding the row lock and then cleans what the write stored`() {
        val victim = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        val lock = RowLock(victim)

        source().send(erased(victim))
        assertThat(await { anyoneWaitingOnLock() }).describedAs("the erase must wait for the row lock").isTrue()
        assertThat(party(victim).erasedAt).isNull()

        // The write "commits" while the erase waits.
        lock.inLockedTransaction { c ->
            c.prepareStatement("UPDATE parties SET email = 'written@example.test' WHERE id = ?").use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO party_external_ids (party_id, id_type, id_value) VALUES (?, 'KEYCLOAK_ID', 'written')",
            ).use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
        }
        lock.release()

        assertThat(await { party(victim).erasedAt != null }).isTrue()
        assertThat(party(victim).email).describedAs("the erase must clean the write it waited for").isNull()
        assertThat(count("SELECT count(*) FROM party_external_ids WHERE party_id = ?", victim)).isZero()
    }

    @Test
    fun `updating a case whose already-linked party was erased is refused`() {
        val victim = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        seedCase(caseId, null, "blind-case-$caseId")
        jdbc { c ->
            c.prepareStatement(
                "UPDATE identity_verification_case SET status = 'AWAITING_SECOND_APPROVAL', first_approver = 'op-1', " +
                    "first_verdict = 'LINK_TO_EXISTING', first_link_party_id = ?, final_link_party_id = NULL, " +
                    "first_at = now() WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, victim)
                ps.setObject(2, caseId)
                ps.executeUpdate()
            }
            // The linked party is erased AFTER the link; the case row itself is not yet anonymised.
            c.prepareStatement("UPDATE parties SET erased_at = now() WHERE id = ?").use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
        }

        val thrown = runCatching {
            onEventLoop {
                val case = repository.findById(caseId)!!
                repository.update(
                    case.copy(
                        status = com.openbank.pid.domain.model.VerificationCaseStatus.DECIDED,
                        secondApprover = "op-2",
                        secondAt = java.time.Instant.now(),
                        finalVerdict = com.openbank.pid.domain.model.CaseVerdict.LINK_TO_EXISTING,
                        finalLinkPartyId = victim,
                        decidedAt = java.time.Instant.now(),
                    ),
                )
            }
        }.exceptionOrNull()

        assertThat(thrown).describedAs("an already-linked erased party must refuse the write")
            .isInstanceOf(com.openbank.pid.application.usecase.PartyErasedException::class.java)
        assertThat(
            count(
                "SELECT count(*) FROM identity_verification_case WHERE id = '$caseId' AND status <> 'DECIDED' AND ?::uuid IS NOT NULL",
                victim,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `re-pointing a case away from an erased stored link is refused`() {
        val victim = UUID.randomUUID()
        val live = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        seedParty(live, "blind-live-$live")
        seedCase(caseId, null, "blind-case-$caseId")
        jdbc { c ->
            c.prepareStatement(
                "UPDATE identity_verification_case SET status = 'AWAITING_SECOND_APPROVAL', first_approver = 'op-1', " +
                    "first_verdict = 'LINK_TO_EXISTING', first_link_party_id = ?, final_link_party_id = NULL, " +
                    "first_at = now() WHERE id = ?",
            ).use { ps ->
                ps.setObject(1, victim)
                ps.setObject(2, caseId)
                ps.executeUpdate()
            }
            c.prepareStatement("UPDATE parties SET erased_at = now() WHERE id = ?").use { ps ->
                ps.setObject(1, victim)
                ps.executeUpdate()
            }
        }

        val thrown = runCatching {
            onEventLoop {
                val case = repository.findById(caseId)!!
                repository.update(case.copy(firstLinkPartyId = live, updatedAt = java.time.Instant.now()))
            }
        }.exceptionOrNull()

        assertThat(thrown).describedAs("a stored link to an erased party must refuse a re-point")
            .isInstanceOf(com.openbank.pid.application.usecase.PartyErasedException::class.java)
    }

    // ── Unlinked cases of the erased person (#15755 review follow-up) ──────────────────────────────

    /** A family name no other test uses, so each test's namesake set is its own. */
    private fun uniqueFamily() = "Kral" + UUID.randomUUID().toString().filter { it.isLetter() }.take(LETTERS)

    /** The `NK:` body the resolver builds for a party seeded by [seedParty] with [family]. */
    private fun nameKey(family: String) = MatchKey.of(family, "Jana", java.time.LocalDate.of(1985, 3, 4), "Brno")

    @Suppress("LongParameterList") // one row's distinguishing columns
    private fun seedOpenOrDecidedCase(
        id: UUID,
        dedupKey: String,
        trigger: String,
        status: String,
        verdict: String?,
        candidates: List<UUID>,
        family: String,
        blindIndex: String? = null,
    ) = jdbc { c ->
        c.prepareStatement(
            """INSERT INTO identity_verification_case (id, dedup_key, trigger, status, applicant_given_name,
                 applicant_family_name, applicant_birthdate, applicant_birthplace, applicant_nationalities,
                 blind_index, candidate_party_ids, first_verdict, final_verdict, first_notes, created_at, updated_at)
               VALUES (?, ?, ?, ?, 'Jana', ?, DATE '1985-03-04', 'Brno', 'CZ', ?, ?, ?, ?, 'operator note',
                 now(), now())""",
        ).use { ps ->
            ps.setObject(1, id)
            ps.setString(2, dedupKey)
            ps.setString(3, trigger)
            ps.setString(4, status)
            ps.setString(5, family)
            ps.setString(6, blindIndex)
            ps.setString(7, candidates.joinToString(","))
            ps.setString(8, verdict)
            ps.setString(9, verdict?.takeIf { status == "DECIDED" })
            ps.executeUpdate()
        }
    }

    private data class CaseRow(val family: String, val blindIndex: String?, val dedupKey: String, val erasedAt: String?)

    private fun case(id: UUID): CaseRow = jdbc { c ->
        c.prepareStatement(
            "SELECT applicant_family_name, blind_index, dedup_key, erased_at::text FROM identity_verification_case " +
                "WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "case $id missing" }
                CaseRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
            }
        }
    }

    private fun assertCaseAnonymised(id: UUID) {
        val row = case(id)
        assertThat(row.family).describedAs("applicant name of case $id").isEqualTo("GDPR_ERASED")
        assertThat(row.blindIndex).isNull()
        assertThat(row.dedupKey).isEqualTo("ERASED:$id")
        assertThat(row.erasedAt).isNotNull()
    }

    @Test
    fun `a DISTINCT_NEW case of the erased person, never linked to them, is anonymised`() {
        val family = uniqueFamily()
        val victim = UUID.randomUUID()
        val namesake = UUID.randomUUID()
        val distinctNew = UUID.randomUUID()
        seedParty(namesake, "blind-namesake-$namesake", family)
        seedParty(victim, "blind-victim-$victim", family)
        // The victim applied, matched the namesake by name key, and was decided a DISTINCT person:
        // validateLink forces both link columns to null, so nothing points at the victim.
        seedOpenOrDecidedCase(
            distinctNew,
            "NK:${nameKey(family)}",
            "NAMESAKE_CANDIDATE",
            "DECIDED",
            "DISTINCT_NEW",
            listOf(namesake),
            family,
        )

        eraseAndAwait(victim)

        assertCaseAnonymised(distinctNew)
    }

    @Test
    fun `an open unlinked case of the erased person is anonymised, by name key and by RČ blind index`() {
        val family = uniqueFamily()
        val victim = UUID.randomUUID()
        val namesake = UUID.randomUUID()
        val openByName = UUID.randomUUID()
        val openByRc = UUID.randomUUID()
        val distinctByRc = UUID.randomUUID()
        val blind = "blind-victim-$victim"
        seedParty(namesake, "blind-namesake-$namesake", family)
        seedParty(victim, blind, family)
        seedOpenOrDecidedCase(
            openByName,
            "PK:${nameKey(family)}",
            "PROBABILISTIC_CANDIDATE",
            "OPEN",
            null,
            listOf(namesake),
            family,
        )
        seedOpenOrDecidedCase(openByRc, "RN:$blind", "RN_COLLISION", "OPEN", null, listOf(victim), family, blind)
        // Same identifier, but four eyes decided the applicant is someone else: only the identifier goes.
        seedOpenOrDecidedCase(
            distinctByRc,
            "RN:$blind",
            "RN_COLLISION",
            "DECIDED",
            "DISTINCT_NEW",
            listOf(victim),
            "Jiny",
            blind,
        )

        eraseAndAwait(victim)

        assertCaseAnonymised(openByName)
        assertCaseAnonymised(openByRc)
        val distinct = case(distinctByRc)
        assertThat(distinct.blindIndex).describedAs("the erased person's identifier").isNull()
        assertThat(distinct.dedupKey).isEqualTo("ERASED:$distinctByRc")
        assertThat(distinct.family).describedAs("a decided different person is kept").isEqualTo("Jiny")
        assertThat(distinct.erasedAt).isNull()
    }

    @Test
    fun `another person's case that lists the erased party only as a candidate is not touched`() {
        val family = uniqueFamily()
        val victim = UUID.randomUUID()
        val otherApplicantCase = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim", family)
        // Someone else, with the same name key, was compared against the victim and decided distinct.
        seedOpenOrDecidedCase(
            otherApplicantCase,
            "NK:${nameKey(family)}",
            "NAMESAKE_CANDIDATE",
            "DECIDED",
            "DISTINCT_NEW",
            listOf(victim),
            family,
        )

        // And an ambiguous one: a live namesake who is NOT a candidate could be that case's applicant.
        val family2 = uniqueFamily()
        val victim2 = UUID.randomUUID()
        val candidate2 = UUID.randomUUID()
        val liveNamesake2 = UUID.randomUUID()
        val ambiguousCase = UUID.randomUUID()
        seedParty(candidate2, "blind-candidate-$candidate2", family2)
        seedParty(victim2, "blind-victim-$victim2", family2)
        seedParty(liveNamesake2, "blind-namesake-$liveNamesake2", family2)
        seedOpenOrDecidedCase(
            ambiguousCase,
            "NK:${nameKey(family2)}",
            "NAMESAKE_CANDIDATE",
            "DECIDED",
            "DISTINCT_NEW",
            listOf(candidate2),
            family2,
        )
        val before = case(otherApplicantCase)
        val ambiguousBefore = case(ambiguousCase)

        eraseAndAwait(victim)
        eraseAndAwait(victim2)

        assertThat(case(otherApplicantCase)).describedAs("the erased party was only a candidate").isEqualTo(before)
        assertThat(case(ambiguousCase)).describedAs("a live namesake could be the applicant").isEqualTo(ambiguousBefore)
    }

    @Test
    fun `linking case evidence to an erased party is refused and publishes no event`() {
        val victim = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        eraseAndAwait(victim)
        val sink = connector.sink<String>("party-events-out")
        sink.clear()

        val thrown = runCatching {
            onEventLoop {
                partyService.linkCaseEvidence(
                    com.openbank.pid.application.port.`in`.LinkCaseEvidenceCommand(
                        partyId = victim,
                        evidenceRef = "evidence-after-erasure",
                        actor = "pid-reviewer",
                    ),
                )
            }
        }.exceptionOrNull()

        assertThat(thrown).describedAs("409 PartyErasedException")
            .isInstanceOf(com.openbank.pid.application.usecase.PartyErasedException::class.java)
        assertThat(sink.received().map { it.payload }.filter { it.contains(victim.toString()) })
            .describedAs("no party event for the erased party").isEmpty()
    }

    @Test
    fun `a relationship terminate blocks behind the erase that holds the row lock and is then refused`() {
        val victim = UUID.randomUUID()
        val relationship = UUID.randomUUID()
        seedParty(victim, "blind-victim-$victim")
        jdbc { c ->
            c.prepareStatement(
                "INSERT INTO party_relationships (id, party_id, role, status, onboarding_channel) " +
                    "VALUES (?, ?, 'CUSTOMER', 'ACTIVE', 'API')",
            ).use { ps ->
                ps.setObject(1, relationship)
                ps.setObject(2, victim)
                ps.executeUpdate()
            }
        }
        val lock = RowLock(victim)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit<Int> {
                io.restassured.RestAssured.given().contentType("application/json")
                    .body("""{"reason":"free text written after the erase"}""")
                    .request("DELETE", "/api/v1/parties/$victim/relationships/$relationship").statusCode()
            }
            assertThat(await { anyoneWaitingOnLock() })
                .describedAs("the terminate must block on the party row lock").isTrue()

            lock.inLockedTransaction { c ->
                c.prepareStatement("UPDATE parties SET erased_at = now() WHERE id = ?").use { ps ->
                    ps.setObject(1, victim)
                    ps.executeUpdate()
                }
            }
            lock.release()

            assertThat(write.get(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409)
            assertThat(
                count(
                    "SELECT count(*) FROM party_relationships WHERE party_id = ? AND termination_reason IS NOT NULL " +
                        "AND status = 'ACTIVE'",
                    victim,
                ),
            ).isZero()
            assertThat(
                count("SELECT count(*) FROM party_relationships WHERE party_id = ? AND status = 'ACTIVE'", victim),
            )
                .describedAs("the refused terminate wrote nothing").isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ATTEMPTS = 100
        const val POLL_MILLIS = 100L
        const val WAIT_SECONDS = 30L
        const val LETTERS = 8
    }
}
