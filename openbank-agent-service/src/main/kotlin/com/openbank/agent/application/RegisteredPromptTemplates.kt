// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

/**
 * ADR-0148 prompt-registry loader for the prompts that openbank-agent-service owns directly.
 *
 * The registry files are the source of truth (`openbank-libs/governance/prompts/`); build.gradle.kts
 * packages the live versions onto the classpath under `/governance-prompts/`. Templated prompts are
 * rendered from those files at runtime so the sent prompt stays resolvable from its `prompt_hash`.
 */
internal object RegisteredPromptTemplates {

    private const val OVERSIGHT_DUPLICATE_CLAUSE =
        " Do NOT draft a proposal that duplicates one already pending human review: [{{pending_titles}}]."
    private const val PAGE_CONTEXT_CLAUSE = " Operator is viewing: {{page_context}}."

    private val oversightTemplate = loadRegisteredPrompt("compliance-officer", "oversight.v1")
    private val uiAssistantTemplate = loadRegisteredPrompt("ui-assistant", "system.v3")
    private val catalogReviewTemplate = loadRegisteredPrompt("ui-assistant", "catalog-review.v1")

    internal fun oversightPrompt(pendingTitles: List<String>): String = if (pendingTitles.isEmpty()) {
        normalizeTrailingWhitespace(oversightTemplate.replace(OVERSIGHT_DUPLICATE_CLAUSE, ""))
    } else {
        renderTemplate(oversightTemplate, mapOf("pending_titles" to pendingTitles.joinToString("; ")))
    }

    internal fun uiAssistantPrompt(pageContext: String?): String = pageContext?.takeIf { it.isNotBlank() }
        ?.let { renderTemplate(uiAssistantTemplate, mapOf("page_context" to it)) }
        ?: normalizeTrailingWhitespace(uiAssistantTemplate.replace(PAGE_CONTEXT_CLAUSE, ""))

    /** Purpose-bound UI-assistant mode used only with a pre-fetched, exact catalog snapshot. */
    internal fun catalogReviewPrompt(): String = catalogReviewTemplate

    /**
     * Load a prompt template from the ADR-0148 registry, packaged onto the classpath at build time
     * from `openbank-libs/governance/prompts/<agentId>/<name>.md`. A missing resource is a build
     * misconfiguration and fails fast rather than shipping a silent empty prompt.
     */
    internal fun loadRegisteredPrompt(agentId: String, name: String): String {
        val path = "/governance-prompts/$agentId/$name.md"
        return RegisteredPromptTemplates::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: error(
                "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                    "openbank-libs/governance/prompts/$agentId/$name.md (ADR-0148)",
            )
    }

    internal fun renderTemplate(template: String, substitutions: Map<String, String>): String =
        normalizeTrailingWhitespace(
            substitutions.entries.fold(template) { rendered, (name, value) ->
                rendered.replace("{{$name}}", value)
            },
        )

    internal fun normalizeTrailingWhitespace(text: String): String =
        text.lineSequence().joinToString("\n") { it.trimEnd() }.trimEnd()
}
