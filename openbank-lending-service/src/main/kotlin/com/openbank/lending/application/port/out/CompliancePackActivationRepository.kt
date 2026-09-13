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

    /**
     * Apply a four-eyes decision to [entity] **only if** the stored row is still
     * [ProposalState.PROPOSED], as a single statement. Returns the number of rows claimed: `1` when
     * this caller won the decision, `0` when someone else already decided it.
     *
     * The caller must treat `0` as a refusal and must not perform any side effect of the decision.
     */
    fun compareAndSetDecision(entity: CompliancePackActivationEntity): Uni<Int>
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

    /**
     * The decision leg does NOT go through [save].
     *
     * `save` is a blind upsert: it writes whatever the caller loaded, whenever the caller gets round
     * to it. The checker leg reads the row in one transaction (`findById`, `@WithSession`), decides
     * against that snapshot in memory, and writes in another — so two decisions arriving together
     * both observe `PROPOSED`, both pass the service's `require(state == PROPOSED)` guard, and both
     * write. A lost update on a segregation-of-duties control, and worse than a lost update: the
     * approve leg activates the pack in the in-memory registry whether or not its write survived, so
     * a REJECTED row can coexist with a pod enforcing the pack that rejection refused.
     *
     * Making the state test part of the UPDATE closes the window without a lock, a version column or
     * a schema change — the database evaluates `state = PROPOSED` and the assignment in one
     * statement, so exactly one of any number of concurrent deciders can claim the row and the rest
     * get `0` back. `CompliancePackConcurrentDecideIT` measures it; on the previous code it observed
     * both decisions accepted in 10 of 12 rounds.
     */
    @WithTransaction
    override fun compareAndSetDecision(entity: CompliancePackActivationEntity): Uni<Int> =
        Panache.getSession().flatMap { session ->
            session.createMutationQuery(DECIDE_HQL)
                .setParameter("state", entity.state)
                .setParameter("decidedBy", entity.decidedBy)
                .setParameter("decidedAt", entity.decidedAt)
                .setParameter("reason", entity.decisionReason)
                .setParameter("updatedAt", entity.updatedAt)
                .setParameter("id", entity.id)
                .setParameter("from", ProposalState.PROPOSED)
                .executeUpdate()
        }

    @WithSession
    override fun findById(id: UUID): Uni<CompliancePackActivationEntity?> = find("id", id).firstResult()

    @WithSession
    override fun findByState(state: ProposalState): Uni<List<CompliancePackActivationEntity>> =
        // `proposedAt`, not `proposed_at`: this is HQL, so the ORDER BY names the ENTITY PROPERTY.
        // The column name parses fine to the eye and throws `SemanticException: Could not interpret
        // path expression` at runtime, which is how this endpoint answered 500 on every call from the
        // day it shipped until the fuzz lane got past authentication (#5913).
        list("state = ?1 order by proposedAt", state)

    @WithSession
    override fun findActivated(): Uni<List<CompliancePackActivationEntity>> = list(
        "state in ?1 order by jurisdiction, productType, packVersion",
        listOf(ProposalState.APPROVED, ProposalState.EXECUTED),
    )

    private companion object {
        /**
         * The `and state = :from` clause is the whole point — without it this is [save] with extra
         * steps. Named parameters because [CompliancePackActivationEntity.decisionReason] is
         * nullable and Panache's positional `update(String, vararg Any)` cannot carry a null.
         */
        const val DECIDE_HQL =
            "update CompliancePackActivationEntity " +
                "set state = :state, decidedBy = :decidedBy, decidedAt = :decidedAt, " +
                "decisionReason = :reason, updatedAt = :updatedAt " +
                "where id = :id and state = :from"
    }
}
