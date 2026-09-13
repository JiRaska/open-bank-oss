// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.communication.application.port.out.PlaybookVersionRepository
import com.openbank.communication.domain.ApprovedAnswer
import com.openbank.communication.domain.CallScriptStep
import com.openbank.communication.domain.PlaybookVersion
import com.openbank.communication.domain.PlaybookVersionConflictException
import com.openbank.communication.domain.PlaybookVersionStatus
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

private val playbookJsonMapper = jacksonObjectMapper()

@Entity
@Table(name = "playbook_version")
class PlaybookVersionEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var personaId: UUID
    var version = 1
    lateinit var status: String

    @Column(columnDefinition = "text")
    lateinit var callScript: String

    @Column(columnDefinition = "text")
    lateinit var approvedAnswers: String
    lateinit var maker: String
    lateinit var createdAt: Instant
    var decidedBy: String? = null
    var decidedAt: Instant? = null
    var publishedAt: Instant? = null
    var retiredAt: Instant? = null
}

private fun PlaybookVersionEntity.toDomain() = PlaybookVersion(
    id = id,
    personaId = personaId,
    version = version,
    status = PlaybookVersionStatus.valueOf(status),
    callScript = playbookJsonMapper.readValue<List<CallScriptStep>>(callScript),
    approvedAnswers = playbookJsonMapper.readValue<List<ApprovedAnswer>>(approvedAnswers),
    maker = maker,
    createdAt = createdAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    publishedAt = publishedAt,
    retiredAt = retiredAt,
)

@ApplicationScoped
class PanachePlaybookVersionRepository :
    PlaybookVersionRepository,
    PanacheRepository<PlaybookVersionEntity> {

    override suspend fun create(v: PlaybookVersion) = Panache.withTransaction {
        persist(
            PlaybookVersionEntity().apply {
                id = v.id
                personaId = v.personaId
                version = v.version
                status = v.status.name
                callScript = playbookJsonMapper.writeValueAsString(v.callScript)
                approvedAnswers = playbookJsonMapper.writeValueAsString(v.approvedAnswers)
                maker = v.maker
                createdAt = v.createdAt
            },
        )
    }.awaitSuspending().let { v }

    override suspend fun find(id: UUID) =
        Panache.withSession { find("id", id).firstResult<PlaybookVersionEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun latestVersionNumber(personaId: UUID) = Panache.withSession {
        find("personaId", personaId).list<PlaybookVersionEntity>()
    }.awaitSuspending().maxOfOrNull { it.version } ?: 0

    override suspend fun submit(id: UUID, at: Instant) = Panache.withTransaction {
        find(
            "id = ?1 and status = ?2",
            id,
            PlaybookVersionStatus.DRAFT.name,
        ).firstResult<PlaybookVersionEntity>().map { e ->
            requireNotNull(e)
            e.status = PlaybookVersionStatus.IN_REVIEW.name
            e.toDomain()
        }
    }.awaitSuspending()

    override suspend fun publish(id: UUID, checker: String, at: Instant) = Panache.withTransaction {
        find(
            "id = ?1 and status = ?2",
            id,
            PlaybookVersionStatus.IN_REVIEW.name,
        ).firstResult<PlaybookVersionEntity>().map { e ->
            requireNotNull(e)
            if (e.maker ==
                checker
            ) {
                throw PlaybookVersionConflictException("maker cannot publish their own playbook version")
            }
            e.status = PlaybookVersionStatus.PUBLISHED.name
            e.decidedBy = checker
            e.decidedAt = at
            e.publishedAt = at
            e.toDomain()
        }
    }.awaitSuspending()

    override suspend fun retire(id: UUID, checker: String, at: Instant) = Panache.withTransaction {
        find("id", id).firstResult<PlaybookVersionEntity>().map { e ->
            requireNotNull(e)
            e.status = PlaybookVersionStatus.RETIRED.name
            e.decidedBy = e.decidedBy ?: checker
            e.decidedAt = e.decidedAt ?: at
            e.retiredAt = at
            e.toDomain()
        }
    }.awaitSuspending()

    override suspend fun findPublished(personaId: UUID) = Panache.withSession {
        find(
            "personaId = ?1 and status = ?2",
            personaId,
            PlaybookVersionStatus.PUBLISHED.name,
        ).firstResult<PlaybookVersionEntity>()
    }.awaitSuspending()?.toDomain()
}
