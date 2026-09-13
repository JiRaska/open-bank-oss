// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.application

import com.openbank.communication.application.port.out.CommunicationAuditRepository
import com.openbank.communication.application.port.out.GoldenSetRepository
import com.openbank.communication.application.port.out.PersonaRepository
import com.openbank.communication.domain.GoldenSetEntry
import com.openbank.communication.domain.GoldenSetEntryNotFoundException
import com.openbank.communication.domain.GoldenSetEntryValidationException
import com.openbank.communication.domain.PersonaNotFoundException
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

data class CreateGoldenSetEntryCommand(
    val personaKey: String,
    val question: String,
    val expectedLanguage: String,
    val expectNoFigureFromMemory: Boolean,
    val expectedToneMarkers: List<String>,
    val requiredComplianceSentence: String?,
    val createdBy: String,
)

/**
 * D4's golden set: CRUD only. No lint (unlike style/playbook drafting): a golden-set `question`
 * is a TEST INPUT — what a hypothetical customer might ask — and a legitimate entry may
 * deliberately contain injection-shaped phrasing to test the composed prompt's defence against
 * it. Linting it the way `CommStyleLinter` lints editor-authored content the bot then SAYS would
 * reject the exact adversarial cases this exists to check for. `expectedLanguage`,
 * `expectedToneMarkers` and `requiredComplianceSentence` are check CRITERIA, not text a
 * consumer is ever served, so the same reasoning applies to them.
 *
 * No replay is wired here (see [GoldenSetEntry]'s KDoc) — entries are inert until that gate
 * exists, so no four-eyes control applies to authoring them either: nothing an editor writes
 * here can reach a customer without a separate, later change composing and replaying against it.
 */
@ApplicationScoped
class CommunicationGoldenSetService(
    private val personas: PersonaRepository,
    private val entries: GoldenSetRepository,
    private val audit: CommunicationAuditRepository,
    private val clock: Clock,
) {
    suspend fun create(command: CreateGoldenSetEntryCommand): GoldenSetEntry {
        val persona = personas.findByKey(command.personaKey)
            ?: throw PersonaNotFoundException("persona '${command.personaKey}' not found")
        if (command.question.isBlank()) {
            throw GoldenSetEntryValidationException("question must not be blank")
        }
        val now = Instant.now(clock)
        val created = entries.create(
            GoldenSetEntry(
                id = Ids.newId(),
                personaId = persona.id,
                question = command.question,
                expectedLanguage = command.expectedLanguage,
                expectNoFigureFromMemory = command.expectNoFigureFromMemory,
                expectedToneMarkers = command.expectedToneMarkers,
                requiredComplianceSentence = command.requiredComplianceSentence,
                createdBy = command.createdBy,
                createdAt = now,
            ),
        )
        audit.append("GOLDEN_SET_ENTRY_CREATED", created.id, command.createdBy, command.personaKey, now)
        return created
    }

    suspend fun list(personaKey: String): List<GoldenSetEntry> {
        val persona = personas.findByKey(personaKey)
            ?: throw PersonaNotFoundException("persona '$personaKey' not found")
        return entries.findByPersona(persona.id)
    }

    suspend fun delete(id: java.util.UUID, actor: String) {
        val existing = entries.find(id) ?: throw GoldenSetEntryNotFoundException("golden-set entry $id not found")
        entries.delete(id)
        audit.append(
            "GOLDEN_SET_ENTRY_DELETED",
            existing.id,
            actor,
            "persona=${existing.personaId}",
            Instant.now(clock),
        )
    }
}
