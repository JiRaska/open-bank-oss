// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application

import com.openbank.communication.application.port.out.CommunicationAuditRepository
import com.openbank.communication.application.port.out.PersonaRepository
import com.openbank.communication.application.port.out.PlaybookVersionRepository
import com.openbank.communication.domain.ApprovedAnswer
import com.openbank.communication.domain.CallScriptStep
import com.openbank.communication.domain.CommStyleLinter
import com.openbank.communication.domain.PersonaNotFoundException
import com.openbank.communication.domain.PlaybookAnswerSearch
import com.openbank.communication.domain.PlaybookVersion
import com.openbank.communication.domain.PlaybookVersionConflictException
import com.openbank.communication.domain.PlaybookVersionNotFoundException
import com.openbank.communication.domain.PlaybookVersionStatus
import com.openbank.communication.domain.PlaybookVersionValidationException
import com.openbank.communication.domain.PublishedPlaybook
import com.openbank.communication.domain.StyleLintRejectedException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Bundled for the same detekt-LongParameterList reason as `DraftStyleVersionCommand`. */
data class DraftPlaybookVersionCommand(
    val personaKey: String,
    val callScript: List<CallScriptStep>,
    val approvedAnswers: List<ApprovedAnswer>,
    val maker: String,
)

@ApplicationScoped
class CommunicationPlaybookService(
    private val personas: PersonaRepository,
    private val playbookVersions: PlaybookVersionRepository,
    private val audit: CommunicationAuditRepository,
    private val clock: Clock,
) {

    /** D3 control #1 (lint on save), same linter as style — playbook text is exactly as much of an injection surface. */
    suspend fun draft(command: DraftPlaybookVersionCommand): PlaybookVersion {
        val persona = personas.findByKey(command.personaKey)
            ?: throw PersonaNotFoundException("persona '${command.personaKey}' not found")
        val fields = buildMap {
            command.callScript.forEachIndexed { i, step -> put("callScript[$i].text", step.text) }
            command.approvedAnswers.forEachIndexed { i, a ->
                put("approvedAnswers[$i].situation", a.situation)
                put("approvedAnswers[$i].answer", a.answer)
            }
        }
        val violations = CommStyleLinter.lint(fields)
        if (violations.isNotEmpty()) {
            throw StyleLintRejectedException(violations.map { "${it.rule} in ${it.field}" })
        }
        val now = Instant.now(clock)
        val nextVersion = playbookVersions.latestVersionNumber(persona.id) + 1
        val draft = PlaybookVersion(
            id = Ids.newId(),
            personaId = persona.id,
            version = nextVersion,
            status = PlaybookVersionStatus.DRAFT,
            callScript = command.callScript,
            approvedAnswers = command.approvedAnswers,
            maker = command.maker,
            createdAt = now,
            decidedBy = null,
            decidedAt = null,
            publishedAt = null,
            retiredAt = null,
        )
        val created = playbookVersions.create(draft)
        audit.append("PLAYBOOK_DRAFTED", created.id, command.maker, "${command.personaKey}@${created.version}", now)
        return created
    }

    /** DRAFT -> IN_REVIEW: the maker's declaration "this is ready for a checker" (D4). */
    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun submit(id: UUID, actor: String): PlaybookVersion {
        val existing =
            playbookVersions.find(id) ?: throw PlaybookVersionNotFoundException("playbook version $id not found")
        if (existing.status != PlaybookVersionStatus.DRAFT) {
            throw PlaybookVersionConflictException("playbook version is not a draft")
        }
        val now = Instant.now(clock)
        val submitted = playbookVersions.submit(id, now)
            ?: throw PlaybookVersionConflictException("playbook version could not be submitted")
        audit.append("PLAYBOOK_SUBMITTED", id, actor, "version=${existing.version}", now)
        return submitted
    }

    /**
     * D3 control #2 (four-eyes at publish, `commstyle.publish` — the SAME action name D3 names
     * for style: its own text is "publishing a style OR PLAYBOOK version"). D4 (publish retires
     * the previous PUBLISHED version of the same persona's playbook).
     */
    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun publish(id: UUID, checker: String): PlaybookVersion {
        val existing =
            playbookVersions.find(id) ?: throw PlaybookVersionNotFoundException("playbook version $id not found")
        if (existing.maker == checker) {
            throw PlaybookVersionConflictException("maker cannot publish their own playbook version")
        }
        if (existing.status != PlaybookVersionStatus.IN_REVIEW) {
            throw PlaybookVersionConflictException("playbook version is not in review")
        }
        val now = Instant.now(clock)
        val previouslyPublished = playbookVersions.findPublished(existing.personaId)
        if (previouslyPublished != null) {
            playbookVersions.retire(previouslyPublished.id, checker, now)
            audit.append(
                "PLAYBOOK_RETIRED",
                previouslyPublished.id,
                checker,
                "superseded by version=${existing.version}",
                now,
            )
        }
        val published = playbookVersions.publish(id, checker, now)
            ?: throw PlaybookVersionConflictException("playbook version could not be published")
        audit.append("PLAYBOOK_PUBLISHED", id, checker, "version=${published.version}", now)
        return published
    }

    /** D4: retiring a still-PUBLISHED version without a replacement — an explicit checker act. */
    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun retire(id: UUID, checker: String): PlaybookVersion {
        val existing =
            playbookVersions.find(id) ?: throw PlaybookVersionNotFoundException("playbook version $id not found")
        if (existing.status != PlaybookVersionStatus.PUBLISHED) {
            throw PlaybookVersionConflictException("playbook version is not published")
        }
        val now = Instant.now(clock)
        val retired = playbookVersions.retire(id, checker, now)
            ?: throw PlaybookVersionConflictException("playbook version could not be retired")
        audit.append("PLAYBOOK_RETIRED", id, checker, "explicit retire, version=${retired.version}", now)
        return retired
    }

    /** The composed playbook the agent-assist view renders (D2, D7). */
    suspend fun published(personaKey: String): PublishedPlaybook {
        val persona =
            personas.findByKey(personaKey) ?: throw PersonaNotFoundException("persona '$personaKey' not found")
        val published = playbookVersions.findPublished(persona.id)
            ?: throw PlaybookVersionNotFoundException("persona '$personaKey' has no published playbook version")
        return published.toPublishedPlaybook(personaKey)
    }

    /** Keyword retrieval over the persona's published approved answers — see [PlaybookAnswerSearch]. */
    suspend fun searchApprovedAnswers(personaKey: String, query: String, limit: Int): List<PlaybookAnswerSearch.Hit> {
        val persona =
            personas.findByKey(personaKey) ?: throw PersonaNotFoundException("persona '$personaKey' not found")
        val published = playbookVersions.findPublished(persona.id) ?: return emptyList()
        return PlaybookAnswerSearch.search(query, published.approvedAnswers, limit)
    }

    private fun PlaybookVersion.toPublishedPlaybook(personaKey: String) = PublishedPlaybook(
        personaKey = personaKey,
        playbookVersion = version,
        callScript = callScript,
        approvedAnswers = approvedAnswers,
        publishedAt = publishedAt ?: throw PlaybookVersionValidationException("published playbook has no publishedAt"),
    )
}
