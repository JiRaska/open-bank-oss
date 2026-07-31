// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.infrastructure.persistence.entity.CompliancePackActivationEntity
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Persistence of the compliance-pack four-eyes workflow (ADR-0212 D4). */
interface CompliancePackActivationRepository {
    fun save(entity: CompliancePackActivationEntity): Uni<CompliancePackActivationEntity>
    fun findById(id: UUID): Uni<CompliancePackActivationEntity?>
    fun findByState(state: ProposalState): Uni<List<CompliancePackActivationEntity>>
    fun findActivated(): Uni<List<CompliancePackActivationEntity>>
}

@ApplicationScoped
class JpaCompliancePackActivationRepository :
    CompliancePackActivationRepository,
    PanacheRepositoryBase<CompliancePackActivationEntity, UUID> {

    override fun save(entity: CompliancePackActivationEntity): Uni<CompliancePackActivationEntity> =
        persistAndFlush(entity).replaceWith(entity)

    override fun findById(id: UUID): Uni<CompliancePackActivationEntity?> = find("id", id).firstResult()

    override fun findByState(state: ProposalState): Uni<List<CompliancePackActivationEntity>> =
        list("state = ?1 order by proposed_at", state)

    override fun findActivated(): Uni<List<CompliancePackActivationEntity>> = list(
        "state in ?1 order by jurisdiction, productType, packVersion",
        listOf(ProposalState.APPROVED, ProposalState.EXECUTED),
    )
}
