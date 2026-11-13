// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.it

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.CaseChangedConcurrentlyException
import com.openbank.aml.application.usecase.CasePartyResolver
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.infrastructure.persistence.repository.AmlCaseRepositoryImpl
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.vertx.mutiny.pgclient.PgPool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Real-Postgres cover for the #3413 detector and the update it drives.
 *
 * The whole sweep rests on one claim: a case that was never resolved has `party_id` byte-for-byte
 * equal to `account_id`, because the rails satisfied a `NOT NULL` column with the debtor ACCOUNT id
 * (measured: 6 of 6 payment cases in sandbox). If that predicate is wrong in either direction the
 * sweep either misses every broken row or rewrites correct ones — on a compliance store, the second
 * is much worse than the first, so both directions are asserted here.
 *
 * The lookup itself is not exercised: it is an HTTP call to account-service, and what needs pinning
 * is which rows the sweep selects and what it writes, not that a rest-client works.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class PartyResolutionIT {

    @Inject
    lateinit var repository: AmlCaseRepositoryImpl

    @Inject
    lateinit var resolver: CasePartyResolver

    @Inject
    lateinit var mapper: ObjectMapper

    @Inject
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clear() {
        onEventLoop {
            for (table in listOf("aml_cases", "aml_outbox", "aml_erased_party", "aml_account_party")) {
                pool.query("DELETE FROM $table").execute().awaitSuspending()
            }
        }
    }

    private fun case(key: String, partyId: UUID?, accountId: UUID?): UUID {
        val caseId = UUID.randomUUID()
        onEventLoop {
            pool.preparedQuery(
                """
                INSERT INTO aml_cases
                  (case_id, idempotency_key, party_id, account_id, customer_reference,
                   screening_type, risk_level, status, alert_code, screened_at, created_at, updated_at)
                VALUES ($1,$2,$3,$4,'ref','TRANSACTION_MONITORING','MEDIUM','OPEN','X',$5,$5,$5)
                """.trimIndent(),
            ).execute(Tuple.of(caseId, key, partyId, accountId).addOffsetDateTime(OffsetDateTime.now()))
                .awaitSuspending()
        }
        return caseId
    }

    private fun partyOf(caseId: UUID): UUID? = onEventLoop {
        pool.preparedQuery("SELECT party_id FROM aml_cases WHERE case_id = $1")
            .execute(Tuple.of(caseId)).awaitSuspending().iterator().next().getUUID("party_id")
    }

    @Test
    fun `the detector selects exactly the cases that carry an account id as their party`() {
        val account = UUID.randomUUID()
        val brokenCase = case("broken", partyId = account, accountId = account)
        // A correctly-resolved payment case: real party, real account, different values.
        val healthy = case("healthy", partyId = UUID.randomUUID(), accountId = UUID.randomUUID())
        // An onboarding case: real party, NO account. 51 of 57 rows in sandbox look like this and
        // the sweep must never touch them — `account_id IS NULL` is why.
        val onboarding = case("onboarding", partyId = UUID.randomUUID(), accountId = null)

        val found = onEventLoop { repository.findUnresolvedParty(50) }

        assertThat(found.map { it.first })
            .describedAs("rewriting a correctly-resolved or onboarding case would corrupt compliance data")
            .containsExactly(brokenCase)
        assertThat(found.single().second).isEqualTo(account)
        assertThat(onEventLoop { repository.countUnresolvedParty() }).isEqualTo(1)

        // Untouched.
        assertThat(partyOf(healthy)).isNotEqualTo(partyOf(brokenCase))
        assertThat(partyOf(onboarding)).isNotNull()
    }

    @Test
    fun `resolving a case points it at the real party and clears it from the backlog`() {
        val account = UUID.randomUUID()
        val realParty = UUID.randomUUID()
        val caseId = case("to-resolve", partyId = account, accountId = account)

        assertThat(onEventLoop { resolver.resolve(caseId, realParty) }).isTrue()

        assertThat(partyOf(caseId)).isEqualTo(realParty)
        assertThat(onEventLoop { repository.countUnresolvedParty() })
            .describedAs("a resolved case must stop being re-swept")
            .isZero()
        assertThat(onEventLoop { repository.findUnresolvedParty(50) }).isEmpty()
    }

    @Test
    fun `a case with no party at all is selected, counted and resolved like one holding an account id`() {
        val account = UUID.randomUUID()
        val noParty = case("no-party", partyId = null, accountId = account)
        // Neither party nor account: nothing to resolve it from, so the sweep must not pick it up.
        case("nothing-known", partyId = null, accountId = null)
        case("healthy", partyId = UUID.randomUUID(), accountId = UUID.randomUUID())

        assertThat(onEventLoop { repository.findUnresolvedParty(50) }).containsExactly(noParty to account)
        assertThat(onEventLoop { repository.countUnresolvedParty() }).isEqualTo(1)

        val realParty = UUID.randomUUID()
        assertThat(onEventLoop { resolver.resolve(noParty, realParty) }).isTrue()

        assertThat(partyOf(noParty)).isEqualTo(realParty)
        assertThat(onEventLoop { repository.findUnresolvedParty(50) }).isEmpty()
    }

    @Test
    fun `a failed attempt sends a case to the back, so one that cannot resolve does not starve the rest`() {
        val stuck = case("stuck", partyId = null, accountId = UUID.randomUUID())
        val waiting = case("waiting", partyId = null, accountId = UUID.randomUUID())
        // Inserted first, so with no attempt recorded it heads a batch of one.
        assertThat(onEventLoop { repository.findUnresolvedParty(1) }.map { it.first }).containsExactly(stuck)

        onEventLoop { repository.markPartyResolutionAttempted(stuck, Instant.now()) }

        assertThat(onEventLoop { repository.findUnresolvedParty(1) }.map { it.first })
            .describedAs("the never-attempted case is taken before the one that just failed")
            .containsExactly(waiting)
        // Still unresolved and still counted: an attempt is not a resolution.
        assertThat(onEventLoop { repository.countUnresolvedParty() }).isEqualTo(2)
        assertThat(onEventLoop { repository.findUnresolvedParty(50) }.map { it.first }).containsExactly(waiting, stuck)
    }

    @Test
    fun `the list can be narrowed to cases whose party is or is not established`() {
        val account = UUID.randomUUID()
        val noParty = case("no-party", partyId = null, accountId = account)
        val accountAsParty = case("account-as-party", partyId = account, accountId = account)
        val healthy = case("healthy", partyId = UUID.randomUUID(), accountId = UUID.randomUUID())
        // A party and no account: `party_id <> account_id` is NULL here, and must not read as unresolved.
        val onboarding = case("onboarding", partyId = UUID.randomUUID(), accountId = null)

        fun ids(resolved: Boolean?) = onEventLoop { repository.list(null, null, resolved, null, 50, 0) }.map { it.id }

        assertThat(ids(false)).containsExactlyInAnyOrder(noParty, accountAsParty)
        assertThat(ids(true)).containsExactlyInAnyOrder(healthy, onboarding)
        assertThat(ids(null)).hasSize(4)
        assertThat(onEventLoop { repository.findById(noParty) }!!.partyId).isNull()
    }

    private data class Pii(val reference: String, val detail: String?, val matched: String?)

    private fun withPii(caseId: UUID) = onEventLoop {
        pool.preparedQuery(
            "UPDATE aml_cases SET customer_reference = 'acct-1/0800', alert_detail = 'why', matched_entity = 'who' " +
                "WHERE case_id = $1",
        ).execute(Tuple.of(caseId)).awaitSuspending()
    }.let { caseId }

    private fun piiOf(caseId: UUID): Pii = onEventLoop {
        pool.preparedQuery("SELECT customer_reference, alert_detail, matched_entity FROM aml_cases WHERE case_id = $1")
            .execute(Tuple.of(caseId)).awaitSuspending().iterator().next()
            .let { Pii(it.getString(0), it.getString(1), it.getString(2)) }
    }

    private fun eventsOf(caseId: UUID): List<JsonNode> = onEventLoop {
        pool.preparedQuery("SELECT event_type, payload FROM aml_outbox WHERE aggregate_id = $1 ORDER BY id")
            .execute(Tuple.of(caseId)).awaitSuspending().map {
                assertThat(it.getString(0)).isEqualTo("aml.case.status_changed.v1")
                mapper.readTree(it.getString(1))
            }
    }

    private fun erase(party: UUID): Int = onEventLoop { repository.anonymizeByPartyId(party, Instant.now()) }

    private val untouched = Pii("acct-1/0800", "why", "who")

    private fun setStatus(caseId: UUID, status: String) = onEventLoop {
        pool.preparedQuery("UPDATE aml_cases SET status = $2, decided_by = 'analyst' WHERE case_id = $1")
            .execute(Tuple.of(caseId, status)).awaitSuspending()
    }.let { caseId }

    @Test
    fun `a BLOCK decided before the party was known is stated again for the party, once, when it is resolved`() {
        val party = UUID.randomUUID()
        val caseId = setStatus(case("blocked-unresolved", partyId = null, accountId = UUID.randomUUID()), "BLOCKED")
        assertThat(eventsOf(caseId)).isEmpty()

        assertThat(onEventLoop { resolver.resolve(caseId, party) }).isTrue()

        val restated = eventsOf(caseId).single()
        assertThat(restated.path("partyId").asText()).isEqualTo(party.toString())
        assertThat(restated.path("previousStatus").asText()).isEqualTo("BLOCKED")
        assertThat(restated.path("newStatus").asText()).isEqualTo("BLOCKED")
        assertThat(restated.path("decidedBy").asText()).isEqualTo("analyst")
        // Nothing a payment rail matches a decision on.
        assertThat(restated.path("idempotencyKey").isNull).isTrue()
        assertThat(restated.path("alertCode").isNull).isTrue()
        assertThat(restated.path("transactionId").isNull).isTrue()

        // A second resolution finds a case that names its party: nothing written, nothing restated.
        assertThat(onEventLoop { resolver.resolve(caseId, UUID.randomUUID()) }).isFalse()
        assertThat(partyOf(caseId)).isEqualTo(party)
        assertThat(eventsOf(caseId)).hasSize(1)
        assertThat(onEventLoop { resolver.resolve(UUID.randomUUID(), party) }).describedAs("no such case").isFalse()
    }

    /**
     * A restatement reaches a consumer that keeps ONE AML status per party and lets the last event
     * win. So the only status ever restated is BLOCKED: it is the one whose loss would leave a party
     * active that an analyst blocked, and it can never re-activate anybody. A cleared or undecided
     * case has nothing owed to the party.
     */
    @Test
    fun `resolving a case that is not BLOCKED publishes nothing`() {
        for (status in listOf("OPEN", "UNDER_REVIEW", "ESCALATED", "CLEARED")) {
            val caseId = setStatus(case("unresolved-$status", partyId = null, accountId = UUID.randomUUID()), status)

            assertThat(onEventLoop { resolver.resolve(caseId, UUID.randomUUID()) }).isTrue()

            assertThat(eventsOf(caseId)).describedAs("events of a resolved %s case", status).isEmpty()
        }
    }

    /**
     * The other order: resolved first, decided afterwards. The decision's own event then names the
     * party and there is nothing to restate.
     */
    @Test
    fun `a case resolved before it is decided publishes its decision once, with the party`() {
        val party = UUID.randomUUID()
        val caseId = case("resolved-then-blocked", partyId = null, accountId = UUID.randomUUID())
        assertThat(onEventLoop { resolver.resolve(caseId, party) }).isTrue()
        assertThat(eventsOf(caseId)).isEmpty()

        val read = onEventLoop { repository.findById(caseId) }!!
        assertThat(read.partyId).isEqualTo(party)
        val decision = OutboxMessage(UUID.randomUUID(), caseId, "aml.case.status_changed.v1", """{"decision":true}""")
        onEventLoop { repository.update(read.copy(status = AmlCaseStatus.BLOCKED, decidedBy = "analyst"), decision) }

        assertThat(eventsOf(caseId).map { it.path("decision").asBoolean(false) }).containsExactly(true)
    }

    /**
     * A case that carries the account id where the party belongs is resolved exactly as it always
     * was: the party is corrected and NOTHING is published, whatever its status. Those rows exist
     * with every switch of the hold intake off.
     */
    @Test
    fun `resolving a case whose party was the account id publishes nothing, even when it is BLOCKED`() {
        for (status in listOf("OPEN", "CLEARED", "BLOCKED")) {
            val account = UUID.randomUUID()
            val party = UUID.randomUUID()
            val caseId = setStatus(case("account-as-party-$status", partyId = account, accountId = account), status)

            assertThat(onEventLoop { resolver.resolve(caseId, party) }).isTrue()

            assertThat(partyOf(caseId)).isEqualTo(party)
            assertThat(eventsOf(caseId)).describedAs("events of a resolved %s account-as-party case", status).isEmpty()
        }
    }

    @Test
    fun `a decision built before the case was resolved is refused rather than published with no party`() {
        val caseId = case("raced", partyId = null, accountId = UUID.randomUUID())
        // Read while unresolved, as `updateDecision` reads it before building its event.
        val readBefore = onEventLoop { repository.findById(caseId) }!!
        assertThat(onEventLoop { resolver.resolve(caseId, UUID.randomUUID()) }).isTrue()

        val decided = readBefore.copy(status = AmlCaseStatus.BLOCKED, decidedBy = "analyst")
        val stale = OutboxMessage(UUID.randomUUID(), caseId, "aml.case.status_changed.v1", """{"stale":true}""")
        assertThatThrownBy { onEventLoop { repository.update(decided, stale) } }
            .isInstanceOf(CaseChangedConcurrentlyException::class.java)

        // Nothing of the refused write stayed: not the status, not the event.
        assertThat(onEventLoop { repository.findById(caseId) }!!.status).isEqualTo(AmlCaseStatus.OPEN)
        assertThat(eventsOf(caseId)).isEmpty()

        // Read again, the same decision goes through.
        val readAfter = onEventLoop { repository.findById(caseId) }!!
        val retried = OutboxMessage(UUID.randomUUID(), caseId, "aml.case.status_changed.v1", """{"stale":false}""")
        onEventLoop {
            repository.update(readAfter.copy(status = AmlCaseStatus.BLOCKED, decidedBy = "analyst"), retried)
        }
        assertThat(onEventLoop { repository.findById(caseId) }!!.status).isEqualTo(AmlCaseStatus.BLOCKED)
        assertThat(eventsOf(caseId).map { it.path("stale").asBoolean(true) }).containsExactly(false)
    }

    @Test
    fun `a case resolved to a party that was erased meanwhile ends anonymised, and no other case does`() {
        val erasedParty = UUID.randomUUID()
        val livingParty = UUID.randomUUID()
        // Nothing here tells the erasure whose account this is: the case can only be caught on resolution.
        val late = withPii(case("resolved-after-erasure", partyId = null, accountId = UUID.randomUUID()))
        val legacy = UUID.randomUUID().let { withPii(case("legacy-after-erasure", partyId = it, accountId = it)) }
        val control = withPii(case("resolved-to-a-living-party", partyId = null, accountId = UUID.randomUUID()))

        val named = withPii(case("names-the-erased-party", partyId = erasedParty, accountId = null))
        val other = withPii(case("names-another-party", partyId = livingParty, accountId = null))

        assertThat(erase(erasedParty)).describedAs("only the case that names the party is found").isEqualTo(1)
        assertThat(piiOf(named)).isEqualTo(Pii("ERASED-$erasedParty", null, null))
        assertThat(listOf(late, legacy, control, other).map(::piiOf)).containsOnly(untouched)
        // Idempotent, as a redelivered PARTY_ERASED requires.
        assertThat(erase(erasedParty)).isEqualTo(1)

        assertThat(onEventLoop { resolver.resolve(late, erasedParty) }).isTrue()
        assertThat(onEventLoop { resolver.resolve(legacy, erasedParty) }).isTrue()
        assertThat(onEventLoop { resolver.resolve(control, livingParty) }).isTrue()

        val erased = Pii("ERASED-$erasedParty", null, null)
        assertThat(piiOf(late)).isEqualTo(erased)
        assertThat(piiOf(legacy)).isEqualTo(erased)
        assertThat(partyOf(late)).isEqualTo(erasedParty)
        assertThat(piiOf(control)).describedAs("a party that was never erased").isEqualTo(untouched)
    }
}
