// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import java.time.Instant

/**
 * The governance metadata of a flag, as declared in **flag-as-code** (ADR-0067
 * §3/§5/§7). This is the canonical model the CI validator parses out of the
 * flagd JSON and the admin-ui renders — it is *about* the flag, separate from the
 * runtime [FlagEvaluation] of its value.
 *
 * Why a typed model in libs (not just JSON in gitops): the CI gate, the admin-ui
 * BFF, and any service that wants to introspect its own flags all need the same
 * understanding of "is this expired", "is this money-path", "who owns it" —
 * `derive → enforce → show` (ADR-0029) wants one definition, not three.
 *
 * Validation rules CI enforces against the parsed set (see [validate]):
 *   - [key] non-blank, kebab-case (the flagd key and the code reference agree).
 *   - [owner] non-blank (no orphan flags — every flag has a team to retire it).
 *   - [classification] = `MONEY_PATH` requires [fourEyes] = true (a money-path
 *     flip cannot be single-actor — ADR-0023/0034).
 *   - [expiresAt] in the future at merge time (stale-flag GC, ADR-0067 §7).
 */
data class FlagDefinition(
    val key: String,
    val description: String,
    val classification: FlagClassification,
    val owner: String,
    val expiresAt: Instant?,
    /** Whether a flip requires four-eyes approval. Forced true for money-path. */
    val fourEyes: Boolean = classification == FlagClassification.MONEY_PATH,
) {
    /** True once [expiresAt] has passed — surfaced by CI and the admin-ui stale badge. */
    fun isExpired(asOf: Instant): Boolean = expiresAt != null && asOf.isAfter(expiresAt)

    /**
     * Static validation of a single definition (the CI gate runs this over every
     * parsed flag and fails the build on any non-empty result). Returns the list
     * of human-readable violations; empty = valid.
     */
    fun validate(asOf: Instant): List<String> = buildList {
        if (!KEBAB_CASE.matches(key)) add("flag key '$key' must be non-blank kebab-case")
        if (owner.isBlank()) add("flag '$key' has no owner")
        if (classification == FlagClassification.MONEY_PATH && !fourEyes) {
            add("flag '$key' is MONEY_PATH but not four-eyes gated")
        }
        if (isExpired(asOf)) add("flag '$key' expired at $expiresAt — remove it or extend the owner's review")
    }

    private companion object {
        val KEBAB_CASE = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}
