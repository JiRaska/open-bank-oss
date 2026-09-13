// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.communication.application.port.out.GoldenSetRepository
import com.openbank.communication.domain.GoldenSetEntry
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

private val goldenSetJsonMapper = jacksonObjectMapper()

@Entity
@Table(name = "golden_set_entry")
class GoldenSetEntryEntity : PanacheEntityBase() {
    @Id lateinit var id: UUID
    lateinit var personaId: UUID

    @Column(columnDefinition = "text")
    lateinit var question: String
    lateinit var expectedLanguage: String
    var expectNoFigureFromMemory: Boolean = false

    @Column(columnDefinition = "text")
    lateinit var expectedToneMarkers: String
    var requiredComplianceSentence: String? = null
    lateinit var createdBy: String
    lateinit var createdAt: Instant
}

private fun GoldenSetEntryEntity.toDomain() = GoldenSetEntry(
    id = id,
    personaId = personaId,
    question = question,
    expectedLanguage = expectedLanguage,
    expectNoFigureFromMemory = expectNoFigureFromMemory,
    expectedToneMarkers = goldenSetJsonMapper.readValue<List<String>>(expectedToneMarkers),
    requiredComplianceSentence = requiredComplianceSentence,
    createdBy = createdBy,
    createdAt = createdAt,
)

@ApplicationScoped
class PanacheGoldenSetRepository :
    GoldenSetRepository,
    PanacheRepository<GoldenSetEntryEntity> {

    override suspend fun create(entry: GoldenSetEntry) = Panache.withTransaction {
        persist(
            GoldenSetEntryEntity().apply {
                id = entry.id
                personaId = entry.personaId
                question = entry.question
                expectedLanguage = entry.expectedLanguage
                expectNoFigureFromMemory = entry.expectNoFigureFromMemory
                expectedToneMarkers = goldenSetJsonMapper.writeValueAsString(entry.expectedToneMarkers)
                requiredComplianceSentence = entry.requiredComplianceSentence
                createdBy = entry.createdBy
                createdAt = entry.createdAt
            },
        )
    }.awaitSuspending().let { entry }

    override suspend fun find(id: UUID) =
        Panache.withSession { find("id", id).firstResult<GoldenSetEntryEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun findByPersona(personaId: UUID) = Panache.withSession {
        find("personaId", personaId).list<GoldenSetEntryEntity>()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun delete(id: UUID): Boolean = Panache.withTransaction { delete("id", id) }.awaitSuspending() > 0
}
