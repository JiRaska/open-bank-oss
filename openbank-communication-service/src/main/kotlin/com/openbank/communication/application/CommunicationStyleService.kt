// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application

import com.openbank.communication.application.port.out.CommunicationAuditRepository
import com.openbank.communication.application.port.out.CommunicationEventPublisher
import com.openbank.communication.application.port.out.PersonaRepository
import com.openbank.communication.application.port.out.StyleVersionRepository
import com.openbank.communication.domain.CommStyleLinter
import com.openbank.communication.domain.PersonaNotFoundException
import com.openbank.communication.domain.PublishedStyle
import com.openbank.communication.domain.StyleLintRejectedException
import com.openbank.communication.domain.StyleVersion
import com.openbank.communication.domain.StyleVersionConflictException
import com.openbank.communication.domain.StyleVersionNotFoundException
import com.openbank.communication.domain.StyleVersionStatus
import com.openbank.communication.domain.StyleVersionValidationException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Every editable field of a draft, bundled so `draft()` stays under detekt's 9-param ceiling. */
data class DraftStyleVersionCommand(
    val personaKey: String,
    val tone: String,
    val formality: String,
    val formOfAddress: String,
    val maxLength: Int?,
    val preferredTerms: Map<String, String>,
    val forbiddenTerms: List<String>,
    val signature: String?,
    val maker: String,
)

@ApplicationScoped
class CommunicationStyleService(
    private val personas: PersonaRepository,
    private val styleVersions: StyleVersionRepository,
    private val audit: CommunicationAuditRepository,
    private val events: CommunicationEventPublisher,
    private val clock: Clock,
) {

    /**
     * D3 control #1 (lint on save): draft a new version. Every field is checked; the version is
     * only ever persisted when [CommStyleLinter.lint] returns clean. Version numbers are
     * per-persona and monotonic (`unique(persona_id, version)`), never reused.
     */
    suspend fun draft(command: DraftStyleVersionCommand): StyleVersion {
        val persona = personas.findByKey(command.personaKey)
            ?: throw PersonaNotFoundException("persona '${command.personaKey}' not found")
        val fields = buildMap {
            put("tone", command.tone)
            put("formality", command.formality)
            put("formOfAddress", command.formOfAddress)
            command.preferredTerms.forEach { (k, v) -> put("preferredTerms.$k", v) }
            command.forbiddenTerms.forEachIndexed { i, v -> put("forbiddenTerms[$i]", v) }
            command.signature?.let { put("signature", it) }
        }
        val violations = CommStyleLinter.lint(fields)
        if (violations.isNotEmpty()) {
            throw StyleLintRejectedException(violations.map { "${it.rule} in ${it.field}" })
        }
        val now = Instant.now(clock)
        val nextVersion = styleVersions.latestVersionNumber(persona.id) + 1
        val draft = StyleVersion(
            id = Ids.newId(),
            personaId = persona.id,
            version = nextVersion,
            status = StyleVersionStatus.DRAFT,
            tone = command.tone,
            formality = command.formality,
            formOfAddress = command.formOfAddress,
            maxLength = command.maxLength,
            preferredTerms = command.preferredTerms,
            forbiddenTerms = command.forbiddenTerms,
            signature = command.signature,
            maker = command.maker,
            createdAt = now,
            decidedBy = null,
            decidedAt = null,
            publishedAt = null,
            retiredAt = null,
        )
        val created = styleVersions.create(draft)
        audit.append("STYLE_DRAFTED", created.id, command.maker, "${command.personaKey}@${created.version}", now)
        return created
    }

    /** DRAFT -> IN_REVIEW: the maker's declaration "this is ready for a checker" (D4). */
    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun submit(id: UUID, actor: String): StyleVersion {
        val existing = styleVersions.find(id) ?: throw StyleVersionNotFoundException("style version $id not found")
        if (existing.status != StyleVersionStatus.DRAFT) {
            throw StyleVersionConflictException("style version is not a draft")
        }
        val now = Instant.now(clock)
        val submitted = styleVersions.submit(id, now)
            ?: throw StyleVersionConflictException("style version could not be submitted")
        audit.append("STYLE_SUBMITTED", id, actor, "version=${existing.version}", now)
        return submitted
    }

    /**
     * D3 control #2 (four-eyes at publish) + D4 (publish retires the previous PUBLISHED version
     * of the same persona, so `style_version_one_published_per_persona` never has two rows to
     * violate). The caller is expected to already be behind `@Authorize(action =
     * "commstyle.publish")` (ADR-0285 D3) — this method additionally enforces maker != checker
     * itself, so segregation of duties holds even if a future caller forgets the annotation.
     *
     * ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
     * reason the caller can branch on — collapsing them into one exception would erase that.
     */
    @Suppress("ThrowsCount")
    suspend fun publish(id: UUID, checker: String): StyleVersion {
        val existing = styleVersions.find(id) ?: throw StyleVersionNotFoundException("style version $id not found")
        if (existing.maker == checker) {
            throw StyleVersionConflictException("maker cannot publish their own style version")
        }
        if (existing.status != StyleVersionStatus.IN_REVIEW) {
            throw StyleVersionConflictException("style version is not in review")
        }
        val now = Instant.now(clock)
        val previouslyPublished = styleVersions.findPublished(existing.personaId)
        if (previouslyPublished != null) {
            styleVersions.retire(previouslyPublished.id, checker, now)
            audit.append(
                "STYLE_RETIRED",
                previouslyPublished.id,
                checker,
                "superseded by version=${existing.version}",
                now,
            )
        }
        val published = styleVersions.publish(id, checker, now)
            ?: throw StyleVersionConflictException("style version could not be published")
        audit.append("STYLE_PUBLISHED", id, checker, "version=${published.version}", now)
        val persona =
            personas.find(published.personaId)
                ?: throw PersonaNotFoundException("persona ${published.personaId} not found")
        val outcome = events.publishPersonaPublished(persona.key, published.toPublishedStyle(persona.key))
        if (!outcome.isHandedOff) {
            // Not a correctness gap on its own: D5's consumers poll GET .../published on a short
            // TTL regardless, so a dropped event only delays refresh. Still recorded, so a
            // dropped-forever transport is visible in the evidentiary trail, not only in a log.
            audit.append("EVENT_NOT_PUBLISHED", id, checker, "type=persona.published outcome=${outcome.name}", now)
        }
        return published
    }

    /** D4: retiring a still-PUBLISHED version without a replacement — an explicit checker act. */
    // ThrowsCount: three distinct guard-clause rejections, each a different machine-readable
    // reason the caller can branch on — collapsing them into one exception would erase that.
    @Suppress("ThrowsCount")
    suspend fun retire(id: UUID, checker: String): StyleVersion {
        val existing = styleVersions.find(id) ?: throw StyleVersionNotFoundException("style version $id not found")
        if (existing.status != StyleVersionStatus.PUBLISHED) {
            throw StyleVersionConflictException("style version is not published")
        }
        val now = Instant.now(clock)
        val retired = styleVersions.retire(id, checker, now)
            ?: throw StyleVersionConflictException("style version could not be retired")
        audit.append("STYLE_RETIRED", id, checker, "explicit retire, version=${retired.version}", now)
        return retired
    }

    /** D5's `GET /api/v1/personas/{id}/published` — the composed style a consumer caches. */
    suspend fun published(personaKey: String): PublishedStyle {
        val persona =
            personas.findByKey(personaKey) ?: throw PersonaNotFoundException("persona '$personaKey' not found")
        val published = styleVersions.findPublished(persona.id)
            ?: throw StyleVersionNotFoundException("persona '$personaKey' has no published style version")
        return published.toPublishedStyle(personaKey)
    }

    private fun StyleVersion.toPublishedStyle(personaKey: String) = PublishedStyle(
        personaKey = personaKey,
        styleVersion = version,
        tone = tone,
        formality = formality,
        formOfAddress = formOfAddress,
        maxLength = maxLength,
        preferredTerms = preferredTerms,
        forbiddenTerms = forbiddenTerms,
        signature = signature,
        publishedAt = publishedAt ?: throw StyleVersionValidationException("published style has no publishedAt"),
    )
}
