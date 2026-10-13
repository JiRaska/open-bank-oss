// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.communication.application.port.out.CommunicationAuditRepository
import com.openbank.communication.application.port.out.PersonaRepository
import com.openbank.communication.application.port.out.StyleEditorState
import com.openbank.communication.application.port.out.StylePublication
import com.openbank.communication.application.port.out.StyleVersionRepository
import com.openbank.communication.domain.Persona
import com.openbank.communication.domain.StyleVersion
import com.openbank.communication.domain.StyleVersionConflictException
import com.openbank.communication.domain.StyleVersionStatus
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

private val jsonMapper = jacksonObjectMapper()

@Entity
@Table(name = "persona")
class PersonaEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var key: String
    lateinit var displayName: String
    lateinit var channel: String
    lateinit var language: String
    lateinit var description: String
}

@Entity
@Table(name = "style_version")
class StyleVersionEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var personaId: UUID
    var version = 1

    @Column(name = "base_published_version")
    var basePublishedVersion: Int? = null
    lateinit var status: String
    lateinit var tone: String
    lateinit var formality: String
    lateinit var formOfAddress: String
    var maxLength: Int? = null

    @Column(columnDefinition = "text")
    lateinit var preferredTerms: String

    @Column(columnDefinition = "text")
    lateinit var forbiddenTerms: String

    @Column(columnDefinition = "text")
    var uiMessages: String = "{}"
    var signature: String? = null
    lateinit var maker: String
    lateinit var createdAt: Instant
    var decidedBy: String? = null
    var decidedAt: Instant? = null
    var publishedAt: Instant? = null
    var retiredAt: Instant? = null
}

@Entity
@Table(name = "communication_audit_event")
class CommunicationAuditEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var type: String
    lateinit var aggregateId: UUID
    lateinit var actor: String
    lateinit var details: String
    lateinit var occurredAt: Instant
}

private fun PersonaEntity.toDomain() = Persona(id, key, displayName, channel, language, description)

private fun StyleVersionEntity.toDomain() = StyleVersion(
    id = id,
    personaId = personaId,
    version = version,
    basePublishedVersion = basePublishedVersion,
    status = StyleVersionStatus.valueOf(status),
    tone = tone,
    formality = formality,
    formOfAddress = formOfAddress,
    maxLength = maxLength,
    preferredTerms = jsonMapper.readValue(preferredTerms, Map::class.java) as Map<String, String>,
    forbiddenTerms = jsonMapper.readValue(forbiddenTerms, List::class.java) as List<String>,
    uiMessages = jsonMapper.readValue(uiMessages, Map::class.java) as Map<String, String>,
    signature = signature,
    maker = maker,
    createdAt = createdAt,
    decidedBy = decidedBy,
    decidedAt = decidedAt,
    publishedAt = publishedAt,
    retiredAt = retiredAt,
)

@ApplicationScoped
class PanachePersonaRepository :
    PersonaRepository,
    PanacheRepository<PersonaEntity> {
    override suspend fun findByKey(key: String) =
        Panache.withSession { find("key", key).firstResult<PersonaEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun find(id: UUID) =
        Panache.withSession { find("id", id).firstResult<PersonaEntity>() }.awaitSuspending()?.toDomain()
}

@ApplicationScoped
class PanacheStyleVersionRepository :
    StyleVersionRepository,
    PanacheRepository<StyleVersionEntity> {

    override suspend fun createNext(personaId: UUID, make: (Int) -> StyleVersion): StyleVersion =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.find(PersonaEntity::class.java, personaId, LockModeType.PESSIMISTIC_WRITE)
                    .flatMap { persona ->
                        if (persona == null) throw StyleVersionConflictException("persona no longer exists")
                        find("personaId = ?1 order by version desc", personaId)
                            .firstResult<StyleVersionEntity>().flatMap { latest ->
                                find(
                                    "personaId = ?1 and (status = ?2 or status = ?3) order by version desc",
                                    personaId,
                                    StyleVersionStatus.PUBLISHED.name,
                                    StyleVersionStatus.RETIRED.name,
                                ).firstResult<StyleVersionEntity>().flatMap { latestGeneration ->
                                    val s = make((latest?.version ?: 0) + 1)
                                    require(s.personaId == personaId) {
                                        "draft persona does not match locked persona"
                                    }
                                    val currentGeneration = latestGeneration?.version ?: 0
                                    if (s.basePublishedVersion != currentGeneration) {
                                        throw StyleVersionConflictException(
                                            "draft base does not match the current published generation",
                                        )
                                    }
                                    persist(
                                        StyleVersionEntity().apply {
                                            id = s.id
                                            this.personaId = s.personaId
                                            version = s.version
                                            basePublishedVersion = s.basePublishedVersion
                                            status = s.status.name
                                            tone = s.tone
                                            formality = s.formality
                                            formOfAddress = s.formOfAddress
                                            maxLength = s.maxLength
                                            uiMessages = jsonMapper.writeValueAsString(s.uiMessages)
                                            preferredTerms = jsonMapper.writeValueAsString(s.preferredTerms)
                                            forbiddenTerms = jsonMapper.writeValueAsString(s.forbiddenTerms)
                                            signature = s.signature
                                            maker = s.maker
                                            createdAt = s.createdAt
                                        },
                                    ).replaceWith(s)
                                }
                            }
                    }
            }
        }.awaitSuspending()

    override suspend fun find(id: UUID) =
        Panache.withSession { find("id", id).firstResult<StyleVersionEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun readEditorState(personaId: UUID): StyleEditorState = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(PersonaEntity::class.java, personaId, LockModeType.PESSIMISTIC_WRITE).flatMap { persona ->
                if (persona == null) throw StyleVersionConflictException("persona no longer exists")
                find(
                    "personaId = ?1 and (status = ?2 or status = ?3) order by version desc",
                    personaId,
                    StyleVersionStatus.PUBLISHED.name,
                    StyleVersionStatus.RETIRED.name,
                ).firstResult<StyleVersionEntity>().flatMap { latest ->
                    find(
                        "personaId = ?1 and status = ?2",
                        personaId,
                        StyleVersionStatus.PUBLISHED.name,
                    ).firstResult<StyleVersionEntity>().map { current ->
                        StyleEditorState(latest?.version ?: 0, current?.toDomain())
                    }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun submit(id: UUID, at: Instant) = Panache.withTransaction {
        find("id = ?1 and status = ?2", id, StyleVersionStatus.DRAFT.name).firstResult<StyleVersionEntity>().map { e ->
            requireNotNull(e)
            e.status = StyleVersionStatus.IN_REVIEW.name
            e.toDomain()
        }
    }.awaitSuspending()

    /** Locks the persona before reading either style row, so publication and retire serialize. */
    override suspend fun publishIfCurrent(id: UUID, checker: String, at: Instant): StylePublication =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(
                    "select persona_id from style_version where id = :id",
                    UUID::class.java,
                ).setParameter("id", id).singleResultOrNull.flatMap { personaId ->
                    if (personaId == null) throw StyleVersionConflictException("style version could not be published")
                    session.find(PersonaEntity::class.java, personaId, LockModeType.PESSIMISTIC_WRITE)
                        .flatMap { persona ->
                            if (persona == null) throw StyleVersionConflictException("persona no longer exists")
                            session.find(StyleVersionEntity::class.java, id).flatMap { draft ->
                                if (draft == null || draft.personaId != personaId) {
                                    throw StyleVersionConflictException("style version could not be published")
                                }
                                if (draft.maker == checker) {
                                    throw StyleVersionConflictException("maker cannot publish their own style version")
                                }
                                if (draft.status != StyleVersionStatus.IN_REVIEW.name) {
                                    throw StyleVersionConflictException("style version is not in review")
                                }
                                find(
                                    "personaId = ?1 and status = ?2",
                                    personaId,
                                    StyleVersionStatus.PUBLISHED.name,
                                ).firstResult<StyleVersionEntity>().flatMap { current ->
                                    find(
                                        "personaId = ?1 and (status = ?2 or status = ?3) order by version desc",
                                        personaId,
                                        StyleVersionStatus.PUBLISHED.name,
                                        StyleVersionStatus.RETIRED.name,
                                    ).firstResult<StyleVersionEntity>().map { latest ->
                                        val base = draft.basePublishedVersion
                                            ?: throw StyleVersionConflictException("draft has no known published base")
                                        if (base != (latest?.version ?: 0)) {
                                            throw StyleVersionConflictException(
                                                "published style changed since this draft was created",
                                            )
                                        }
                                        val retired = current?.apply {
                                            status = StyleVersionStatus.RETIRED.name
                                            decidedBy = decidedBy ?: checker
                                            decidedAt = decidedAt ?: at
                                            retiredAt = at
                                        }?.toDomain()
                                        draft.status = StyleVersionStatus.PUBLISHED.name
                                        draft.decidedBy = checker
                                        draft.decidedAt = at
                                        draft.publishedAt = at
                                        StylePublication(draft.toDomain(), retired)
                                    }
                                }
                            }
                        }
                }
            }
        }.awaitSuspending()

    override suspend fun retire(id: UUID, checker: String, at: Instant) = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(
                "select persona_id from style_version where id = :id",
                UUID::class.java,
            ).setParameter("id", id).singleResultOrNull.flatMap { personaId ->
                if (personaId == null) throw StyleVersionConflictException("style version could not be retired")
                session.find(PersonaEntity::class.java, personaId, LockModeType.PESSIMISTIC_WRITE)
                    .flatMap { persona ->
                        if (persona == null) throw StyleVersionConflictException("persona no longer exists")
                        session.find(StyleVersionEntity::class.java, id).map { existing ->
                            if (
                                existing == null ||
                                existing.personaId != personaId ||
                                existing.status != StyleVersionStatus.PUBLISHED.name
                            ) {
                                throw StyleVersionConflictException("style version is not published")
                            }
                            existing.status = StyleVersionStatus.RETIRED.name
                            existing.decidedBy = existing.decidedBy ?: checker
                            existing.decidedAt = existing.decidedAt ?: at
                            existing.retiredAt = at
                            existing.toDomain()
                        }
                    }
            }
        }
    }.awaitSuspending()

    override suspend fun findPublished(personaId: UUID) = Panache.withSession {
        find(
            "personaId = ?1 and status = ?2",
            personaId,
            StyleVersionStatus.PUBLISHED.name,
        ).firstResult<StyleVersionEntity>()
    }.awaitSuspending()?.toDomain()
}

@ApplicationScoped
class PanacheCommunicationAuditRepository :
    CommunicationAuditRepository,
    PanacheRepository<CommunicationAuditEntity> {
    override suspend fun append(type: String, aggregateId: UUID, actor: String, details: String, at: Instant) {
        Panache.withTransaction {
            persist(
                CommunicationAuditEntity().apply {
                    id = Ids.newId()
                    this.type = type
                    this.aggregateId = aggregateId
                    this.actor = actor
                    this.details = details
                    occurredAt = at
                },
            )
        }.awaitSuspending()
    }
}
