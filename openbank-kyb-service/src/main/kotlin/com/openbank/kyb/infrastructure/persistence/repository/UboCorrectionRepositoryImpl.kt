// SPDX-License-Identifier: Apache-2.0
package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.KybOutboxRepository
import com.openbank.kyb.application.port.out.UboCorrectionRepository
import com.openbank.kyb.domain.model.UboCorrection
import com.openbank.kyb.domain.model.UboCorrectionStatus
import com.openbank.kyb.domain.model.UboFinding
import com.openbank.kyb.domain.model.UboObservation
import com.openbank.kyb.domain.model.UboSource
import com.openbank.kyb.infrastructure.messaging.UboObservationReference
import com.openbank.kyb.infrastructure.persistence.entity.BusinessOnboardingCaseEntity
import com.openbank.kyb.infrastructure.persistence.entity.UboCorrectionEntity
import com.openbank.kyb.infrastructure.persistence.entity.UboCorrectionReadEntity
import com.openbank.kyb.infrastructure.persistence.entity.UboObservationEntity
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Case lock serialises correction decisions with normal observation appends. Database triggers enforce the same facts. */
@ApplicationScoped
class UboCorrectionRepositoryImpl(private val outbox: KybOutboxRepository) : UboCorrectionRepository {
    private companion object {
        const val MAX_CORRECTION_BYTES = 262_144
    }

    override suspend fun propose(
        caseId: UUID,
        priorObservationId: UUID,
        candidate: UboFinding,
        reasonCode: String,
        actorId: String,
        proposedAt: Instant,
    ): UboCorrection? {
        require(candidate.source == UboSource.REGISTER) { "only a fresh register finding can be proposed" }
        val json = KybUboJson.write(candidate)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_CORRECTION_BYTES) { "UBO correction exceeds size limit" }
        val hash = sha256(json)
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "from BusinessOnboardingCaseEntity c where c.caseId = :caseId",
                    BusinessOnboardingCaseEntity::class.java,
                ).setParameter("caseId", caseId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .resultList.flatMap { cases ->
                        val case = cases.firstOrNull()
                        if (case == null ||
                            candidate.identifier.scheme.name != case.identifierScheme ||
                            candidate.identifier.value != case.identifierValue
                        ) {
                            Uni.createFrom().nullItem<UboCorrection>()
                        } else {
                            session.createQuery(
                                "from UboObservationEntity o where o.caseId = :caseId " +
                                    "order by o.revision desc",
                                UboObservationEntity::class.java,
                            ).setParameter("caseId", caseId).setMaxResults(1).resultList.flatMap { latest ->
                                val prior = latest.firstOrNull()
                                if (prior == null ||
                                    prior.observationId != priorObservationId ||
                                    prior.sourceSha256 == hash
                                ) {
                                    Uni.createFrom().nullItem<UboCorrection>()
                                } else {
                                    session.createQuery(
                                        "select count(r) from UboObservationRestrictionEntity r " +
                                            "where r.observationId = :priorId",
                                        java.lang.Long::class.java,
                                    ).setParameter("priorId", priorObservationId).singleResult.flatMap { restricted ->
                                        if (restricted.toLong() > 0) {
                                            Uni.createFrom().nullItem<UboCorrection>()
                                        } else {
                                            val row = newProposal(
                                                caseId,
                                                priorObservationId,
                                                candidate,
                                                json,
                                                hash,
                                                reasonCode,
                                                actorId,
                                                proposedAt,
                                            )
                                            session.persist(row).replaceWith(row.toDomain())
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }.awaitSuspending()
    }

    override suspend fun readAndAudit(
        caseId: UUID,
        correctionId: UUID,
        principalId: String,
        readAt: Instant,
    ): UboCorrection? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(UboCorrectionEntity::class.java, correctionId).flatMap { row ->
                if (row == null || row.caseId != caseId) {
                    Uni.createFrom().nullItem<UboCorrection>()
                } else {
                    session.createQuery(
                        "select count(r) from UboObservationRestrictionEntity r where r.observationId = :priorId",
                        java.lang.Long::class.java,
                    ).setParameter("priorId", row.priorObservationId).singleResult.flatMap { restricted ->
                        if (restricted.toLong() > 0) {
                            Uni.createFrom().nullItem<UboCorrection>()
                        } else {
                            session.persist(
                                UboCorrectionReadEntity().apply {
                                    readId = Ids.newId()
                                    this.caseId = caseId
                                    this.correctionId = correctionId
                                    this.principalId = principalId
                                    purpose = "KYB_OWNERSHIP_REVIEW"
                                    this.readAt = readAt
                                },
                            ).replaceWith(row.toDomain())
                        }
                    }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun decide(
        caseId: UUID,
        correctionId: UUID,
        actorId: String,
        approved: Boolean,
        decidedAt: Instant,
    ): Boolean? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "from BusinessOnboardingCaseEntity c where c.caseId = :caseId",
                BusinessOnboardingCaseEntity::class.java,
            ).setParameter("caseId", caseId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .resultList.flatMap { cases ->
                    if (cases.isEmpty()) {
                        Uni.createFrom().nullItem<Boolean>()
                    } else {
                        session.find(UboCorrectionEntity::class.java, correctionId).flatMap { row ->
                            if (row?.caseId != caseId || row.status != "PENDING" || row.proposedBy == actorId) {
                                Uni.createFrom().nullItem<Boolean>()
                            } else {
                                hasReviewed(session, correctionId, actorId).flatMap { reviewed ->
                                    if (reviewed) {
                                        decidePending(session, row, actorId, approved, decidedAt)
                                    } else {
                                        Uni.createFrom().nullItem<Boolean>()
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }.awaitSuspending()

    private fun hasReviewed(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        correctionId: UUID,
        actorId: String,
    ): Uni<Boolean> = session.createQuery(
        "select count(a) from UboCorrectionReadEntity a where a.correctionId = :id and a.principalId = :actor",
        java.lang.Long::class.java,
    ).setParameter("id", correctionId).setParameter("actor", actorId).singleResult.map { it.toLong() > 0 }

    private fun newProposal(
        caseId: UUID,
        priorObservationId: UUID,
        candidate: UboFinding,
        json: String,
        hash: String,
        reasonCode: String,
        actorId: String,
        proposedAt: Instant,
    ) = UboCorrectionEntity().apply {
        correctionId = Ids.newId()
        this.caseId = caseId
        this.priorObservationId = priorObservationId
        candidateFindingJson = json
        candidateSha256 = hash
        candidateSource = candidate.source.name
        candidateFetchedAt = candidate.fetchedAt
        this.reasonCode = reasonCode
        proposedBy = actorId
        this.proposedAt = proposedAt
        status = "PENDING"
    }

    private fun decidePending(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        row: UboCorrectionEntity,
        actorId: String,
        approved: Boolean,
        decidedAt: Instant,
    ): Uni<Boolean?> = session.createQuery(
        "from UboObservationEntity o where o.caseId = :caseId order by o.revision desc",
        UboObservationEntity::class.java,
    ).setParameter("caseId", row.caseId).setMaxResults(1).resultList.flatMap { latest ->
        val prior = latest.firstOrNull()
        if (prior == null || prior.observationId != row.priorObservationId) {
            Uni.createFrom().nullItem<Boolean>()
        } else {
            session.createQuery(
                "select count(r) from UboObservationRestrictionEntity r where r.observationId = :priorId",
                java.lang.Long::class.java,
            ).setParameter("priorId", prior.observationId).singleResult.flatMap { restricted ->
                if (restricted.toLong() > 0) {
                    Uni.createFrom().nullItem<Boolean>()
                } else {
                    session.createNativeMutationQuery(
                        """UPDATE kyb_ubo_observation_corrections
                           SET status = :status, decided_by = :actor, decided_at = :decidedAt
                           WHERE correction_id = :correctionId AND case_id = :caseId AND status = 'PENDING'""",
                    ).setParameter("status", if (approved) "APPROVED" else "REJECTED")
                        .setParameter("actor", actorId).setParameter("decidedAt", decidedAt)
                        .setParameter("correctionId", row.correctionId).setParameter("caseId", row.caseId)
                        .executeUpdate().flatMap { changed ->
                            if (changed != 1) {
                                Uni.createFrom().nullItem<Boolean>()
                            } else if (!approved) {
                                Uni.createFrom().item<Boolean?>(true)
                            } else {
                                persistSuccessor(session, row, prior.revision + 1, decidedAt)
                            }
                        }
                }
            }
        }
    }

    private fun persistSuccessor(
        session: org.hibernate.reactive.mutiny.Mutiny.Session,
        row: UboCorrectionEntity,
        revision: Long,
        recordedAt: Instant,
    ): Uni<Boolean?> {
        val observation = UboObservation(
            id = Ids.newId(),
            caseId = row.caseId,
            revision = revision,
            finding = KybUboJson.read(row.candidateFindingJson),
            sourceSha256 = row.candidateSha256,
            recordedAt = recordedAt,
            supersedesObservationId = row.priorObservationId,
        )
        return session.persist(
            UboObservationEntity().apply {
                observationId = observation.id
                caseId = observation.caseId
                this.revision = observation.revision
                source = observation.finding.source.name
                sourceSha256 = observation.sourceSha256
                findingJson = row.candidateFindingJson
                fetchedAt = row.candidateFetchedAt
                this.recordedAt = recordedAt
                supersedesObservationId = row.priorObservationId
                correctionId = row.correctionId
            },
        ).flatMap {
            outbox.persistInTransaction(UboObservationReference.from(observation).toOutboxMessage(recordedAt))
                .replaceWith(true)
        }
    }

    private fun UboCorrectionEntity.toDomain() = UboCorrection(
        id = correctionId,
        caseId = caseId,
        priorObservationId = priorObservationId,
        candidate = KybUboJson.read(candidateFindingJson),
        candidateSha256 = candidateSha256,
        reasonCode = reasonCode,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        status = UboCorrectionStatus.valueOf(status),
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )

    private fun sha256(json: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)),
    )
}
