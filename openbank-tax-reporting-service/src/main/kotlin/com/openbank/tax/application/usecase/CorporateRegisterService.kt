// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.tax.application.port.out.CorporateFactsPort
import com.openbank.tax.application.port.out.CorporateRegisterRepository
import com.openbank.tax.domain.corporate.CorporateFact
import com.openbank.tax.domain.corporate.CorporateRegisterEntry
import com.openbank.tax.domain.corporate.effectiveEntry
import com.openbank.tax.domain.model.requireValid
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProposeCorporateFact(
    val entityId: String,
    val fact: CorporateFact,
    val value: BigDecimal,
    val effectiveFrom: LocalDate,
    val reason: String,
    val evidence: String,
)

class CorporateRegisterEntryNotFoundException(message: String) : RuntimeException(message)

/**
 * The operator-maintained corporate register (#12425): versioned, effective-dated, four-eyes.
 * A proposal never feeds a return; only an approved entry does, and the rows are the audit trail —
 * who proposed which figure from which document, who approved it and when.
 */
@ApplicationScoped
class CorporateRegisterService(
    private val repository: CorporateRegisterRepository,
    private val entities: ReportingEntities,
    private val clock: Clock,
) : CorporateFactsPort {

    suspend fun propose(command: ProposeCorporateFact, by: String): CorporateRegisterEntry {
        requireValid(command.entityId == entities.companyId) {
            "the corporate register holds the reporting company '${entities.companyId}' only"
        }
        val draft = CorporateRegisterEntry(
            id = Ids.newId(),
            entityId = command.entityId,
            fact = command.fact,
            value = command.value,
            effectiveFrom = command.effectiveFrom,
            version = 1,
            reason = command.reason,
            evidence = command.evidence,
            proposedBy = by,
            proposedAt = Instant.now(clock),
        )
        return repository.insert(draft.copy(version = repository.latestVersion(draft) + 1))
    }

    suspend fun approve(id: UUID, by: String): CorporateRegisterEntry =
        repository.decide(get(id).approve(by, Instant.now(clock)))

    suspend fun reject(id: UUID, by: String): CorporateRegisterEntry =
        repository.decide(get(id).reject(by, Instant.now(clock)))

    suspend fun get(id: UUID): CorporateRegisterEntry =
        repository.find(id) ?: throw CorporateRegisterEntryNotFoundException("No corporate register entry $id")

    suspend fun entries(entityId: String): List<CorporateRegisterEntry> = repository.entries(entityId)

    override suspend fun effective(
        entityId: String,
        facts: Collection<CorporateFact>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
    ): Map<CorporateFact, BigDecimal> {
        val entries = repository.entries(entityId)
        return facts.mapNotNull { fact ->
            effectiveEntry(entries, fact, periodStart, periodEnd)?.let { fact to it.value }
        }
            .toMap()
    }
}
