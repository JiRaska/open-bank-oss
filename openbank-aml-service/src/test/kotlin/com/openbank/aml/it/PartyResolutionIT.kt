// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.aml.it

import com.openbank.aml.infrastructure.persistence.repository.AmlCaseRepositoryImpl
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    lateinit var pool: PgPool

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    @BeforeEach
    fun clear() {
        onEventLoop { pool.query("DELETE FROM aml_cases").execute().awaitSuspending() }
    }

    private fun case(key: String, partyId: UUID, accountId: UUID?): UUID {
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

    private fun partyOf(caseId: UUID): UUID = onEventLoop {
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

        onEventLoop { repository.resolveParty(caseId, realParty) }

        assertThat(partyOf(caseId)).isEqualTo(realParty)
        assertThat(onEventLoop { repository.countUnresolvedParty() })
            .describedAs("a resolved case must stop being re-swept")
            .isZero()
        assertThat(onEventLoop { repository.findUnresolvedParty(50) }).isEmpty()
    }
}
