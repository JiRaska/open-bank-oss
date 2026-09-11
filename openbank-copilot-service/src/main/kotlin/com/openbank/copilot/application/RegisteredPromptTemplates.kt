// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

/**
 * ADR-0148 prompt-registry loader, mirroring `openbank-agent-service`'s
 * `RegisteredPromptTemplates` exactly (same classpath-packaging convention,
 * `build.gradle.kts`'s `processResources`). Currently used only for the `style.v1`
 * git-registered baseline `PublishedStyleProvider` falls back to (ADR-0285 D5) — NOT yet for
 * `core.v1`/`style.v1` composition into the live system prompt itself. See
 * `openbank-libs/governance/prompts/registry.yaml`'s own `customer-copilot` entry for why that
 * cutover is a deliberately separate, later change (composing core+style reorders the one
 * style-eligible sentence relative to `system.v1`'s original mid-document position, and a live
 * customer-facing safety prompt needs the ADR-0148 evals replayed against the reordered text —
 * a real model call this environment cannot make — before it becomes the runtime source).
 */
internal object RegisteredPromptTemplates {

    internal val customerCopilotStyleV1: String = loadRegisteredPrompt("customer-copilot", "style.v1")

    /**
     * Load a prompt template from the ADR-0148 registry, packaged onto the classpath at build time
     * from `openbank-libs/governance/prompts/<charter>/<name>.md`. A missing resource is a build
     * misconfiguration and fails fast rather than shipping a silent empty prompt.
     */
    internal fun loadRegisteredPrompt(charter: String, name: String): String {
        val path = "/governance-prompts/$charter/$name.md"
        return RegisteredPromptTemplates::class.java.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: error(
                "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                    "openbank-libs/governance/prompts/$charter/$name.md (ADR-0148)",
            )
    }
}
