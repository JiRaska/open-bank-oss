// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.funding

import com.openbank.pension.application.port.out.AnnualStatement
import com.openbank.pension.application.port.out.AnnualStatementContent
import com.openbank.pension.application.port.out.AnnualStatementDocumentPort
import com.openbank.pension.application.port.out.AnnualStatementRepository
import com.openbank.pension.application.port.out.ContractNotFoundException
import com.openbank.pension.application.port.out.RenderedDocument
import com.openbank.pension.application.usecase.AnnualStatementService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AnnualStatementServiceTest {
    private val f = InMemoryFunding()
    private val renders = mutableListOf<AnnualStatementContent>()
    private val documents = AnnualStatementDocumentPort { content ->
        renders += content
        RenderedDocument("doc-${renders.size}", "a".repeat(64))
    }
    private val store = object : AnnualStatementRepository {
        val rows = ConcurrentHashMap<Pair<UUID, Int>, AnnualStatement>()

        override suspend fun find(contractId: UUID, year: Int) = rows[contractId to year]

        override suspend fun saveOnce(statement: AnnualStatement) =
            rows.putIfAbsent(statement.contractId to statement.year, statement) ?: statement
    }
    private val service =
        AnnualStatementService(f.directory, f.references, f.incentiveService, f.fund, documents, store, f.clock)

    private val lastYear = LocalDate.now(f.clock).year - 1

    @Test
    fun `a closed year's statement is rendered once and the stored one is returned afterwards`(): Unit = runBlocking {
        val c = f.contract()
        val first = service.issue(c.contractId, lastYear)
        val again = service.issue(c.contractId, lastYear)
        assertThat(renders).hasSize(1)
        assertThat(again).isEqualTo(first)
        assertThat(first.documentId).isEqualTo("doc-1")
        assertThat(first.sha256).hasSize(64)
        assertThat(renders.single().participantPartyId).isEqualTo(c.participantPartyId)
        assertThat(renders.single().summary.taxYear).isEqualTo(lastYear)
    }

    @Test
    fun `the running year is refused and nothing is rendered`(): Unit = runBlocking {
        val c = f.contract()
        assertThatThrownBy { runBlocking { service.issue(c.contractId, lastYear + 1) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(renders).isEmpty()
    }

    @Test
    fun `an unknown contract is not found`() {
        assertThatThrownBy { runBlocking { service.issue(UUID.randomUUID(), lastYear) } }
            .isInstanceOf(ContractNotFoundException::class.java)
    }
}
