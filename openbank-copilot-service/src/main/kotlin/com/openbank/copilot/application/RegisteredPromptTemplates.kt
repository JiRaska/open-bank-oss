// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
package com.openbank.copilot.application

import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

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

/**
 * Forces [RegisteredPromptTemplates] to load at boot rather than lazily on first use. Without
 * this, `customerCopilotStyleV1` is only touched from `PublishedStyleProvider`'s failure branch —
 * a `processResources` misconfiguration would leave the service green until communication-service
 * is actually down with a cold cache, which is precisely the moment the fallback is needed and the
 * worst time to discover it's broken. Same shape as `HelpKnowledgeBase`'s `@Startup`, same reason
 * this codebase's own CLAUDE.md documents for `PdfBoxPadesSealAdapter`: an `@ApplicationScoped`
 * bean is lazy by default, and a boot-time guard that only runs on first request is not a guard.
 */
// UtilityClassWithPublicConstructor: not a utility class — a CDI lifecycle bean whose only job is
// its @Startup-triggered `init` block. detekt doesn't recognize that shape; HelpKnowledgeBase's
// @Startup bean escapes the same rule only because it happens to also implement CorpusSource.
@Suppress("UtilityClassWithPublicConstructor")
@Startup
@ApplicationScoped
class RegisteredPromptTemplatesEagerLoader {
    init {
        val chars = RegisteredPromptTemplates.customerCopilotStyleV1.length
        LOG.infof("ADR-0148 registered-prompt baseline loaded at boot: customer-copilot/style.v1 (%d chars)", chars)
    }

    private companion object {
        val LOG: org.jboss.logging.Logger = org.jboss.logging.Logger.getLogger(
            RegisteredPromptTemplatesEagerLoader::class.java,
        )
    }
}
