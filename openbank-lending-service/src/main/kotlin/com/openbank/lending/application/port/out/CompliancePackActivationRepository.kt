// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.infrastructure.persistence.entity.CompliancePackActivationEntity
import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
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

    /**
     * `merge`, not `persist`. [CompliancePackActivationEntity.id] is assigned by the application
     * (`Ids.newId()`), not by `@GeneratedValue`, so a non-null id cannot tell Hibernate a transient
     * entity from a detached one: `persist`/`persistAndFlush` schedules an INSERT for BOTH, and the
     * checker leg — which loads a PROPOSED row, flips its state and saves it — then dies at flush
     * with `duplicate key value violates ... _pkey`. `merge` upserts and handles both.
     *
     * The same defect shipped in consent-service (#1521) and standing-order (#2079). It is invisible
     * to any test that mocks this repository, which is why the coverage for it is a REST-driven
     * integration test against a real database, not a unit test.
     */
    @WithTransaction
    override fun save(entity: CompliancePackActivationEntity): Uni<CompliancePackActivationEntity> =
        Panache.getSession().flatMap { it.merge(entity) }

    @WithSession
    override fun findById(id: UUID): Uni<CompliancePackActivationEntity?> = find("id", id).firstResult()

    @WithSession
    override fun findByState(state: ProposalState): Uni<List<CompliancePackActivationEntity>> =
        list("state = ?1 order by proposed_at", state)

    @WithSession
    override fun findActivated(): Uni<List<CompliancePackActivationEntity>> = list(
        "state in ?1 order by jurisdiction, productType, packVersion",
        listOf(ProposalState.APPROVED, ProposalState.EXECUTED),
    )
}
