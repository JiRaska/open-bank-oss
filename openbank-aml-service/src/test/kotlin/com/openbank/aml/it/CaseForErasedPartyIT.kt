// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.CaseOrigin
import com.openbank.aml.domain.model.ScreeningType
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A party that was erased must not reappear in a case opened for it AFTER the erasure. Every writer
 * that attaches a party to a case goes through the one repository operation that reads the erasure
 * record under the party's lock, so on real Postgres:
 *
 *  - a case created for an erased party is stored, and announced, already anonymised;
 *  - a case created for a party that was not erased is stored as given (the control);
 *  - an erasure racing a creation for the same party ends with the case anonymised whichever
 *    commits first: either the creation sees the record, or the erasure's update sees the row.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class CaseForErasedPartyIT {

    @Inject
    lateinit var cases: AmlCaseUseCase

    @Inject
    lateinit var repository: AmlCaseRepository

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun command(party: UUID) = CreateAmlCaseCommand(
        idempotencyKey = "erasure-it:${UUID.randomUUID()}",
        origin = CaseOrigin.REST,
        partyId = party,
        accountId = UUID.randomUUID(),
        transactionId = null,
        customerReference = "CUST-ERASURE-IT",
        screeningType = ScreeningType.MANUAL_INVESTIGATION,
        riskLevel = AmlRiskLevel.HIGH,
        alertCode = "MANUAL",
        alertDetail = "synthetic detail",
        matchedEntity = "Synthetic Listed Name",
    )

    private data class Row(val reference: String, val detail: String?, val matched: String?, val createdPayload: String)

    private fun row(caseId: UUID): Row = dataSource.connection.use { c ->
        c.prepareStatement(
            "select c.customer_reference, c.alert_detail, c.matched_entity, o.payload from aml_cases c " +
                "join aml_outbox o on o.aggregate_id = c.case_id and o.event_type = 'aml.case.created.v1' " +
                "where c.case_id = ?",
        ).use { s ->
            s.setObject(1, caseId)
            s.executeQuery().use { r ->
                check(r.next()) { "no case $caseId" }
                Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4))
            }
        }
    }

    private fun referenceOf(caseId: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("select customer_reference from aml_cases where case_id = ?").use { s ->
            s.setObject(1, caseId)
            s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }
    }

    @Test
    fun `a case created for an erased party is stored and announced anonymised`() {
        val party = UUID.randomUUID()
        onEventLoop { repository.anonymizeByPartyId(party, Instant.now()) }

        val created = onEventLoop { cases.createCase(command(party)) }

        val row = row(created.id)
        assertThat(row.reference).isEqualTo("ERASED-$party")
        assertThat(row.detail).isNull()
        assertThat(row.matched).isNull()
        assertThat(created.customerReference).isEqualTo("ERASED-$party")
        assertThat(row.createdPayload).doesNotContain("CUST-ERASURE-IT", "Synthetic Listed Name")
    }

    @Test
    fun `a case created for a party that was not erased is stored as given`() {
        val party = UUID.randomUUID()

        val created = onEventLoop { cases.createCase(command(party)) }

        val row = row(created.id)
        assertThat(row.reference).isEqualTo("CUST-ERASURE-IT")
        assertThat(row.detail).isEqualTo("synthetic detail")
        assertThat(row.matched).isEqualTo("Synthetic Listed Name")
        assertThat(row.createdPayload).contains("CUST-ERASURE-IT")
    }

    @Test
    fun `an erasure racing a creation for the same party always ends anonymised`(): Unit = runBlocking {
        repeat(ROUNDS) {
            val party = UUID.randomUUID()
            val (createdId) = listOf(
                async(Dispatchers.IO) { onEventLoop { cases.createCase(command(party)) }.id },
                async(Dispatchers.IO) { onEventLoop { repository.anonymizeByPartyId(party, Instant.now()) }; null },
            ).awaitAll().filterNotNull()
            assertThat(referenceOf(createdId as UUID)).isEqualTo("ERASED-$party")
        }
    }

    private companion object {
        const val ROUNDS = 10
    }
}
