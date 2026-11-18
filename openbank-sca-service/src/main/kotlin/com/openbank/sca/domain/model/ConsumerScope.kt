// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.domain.model

/**
 * An approval namespace reserved to one scoped consumer (ADR-0335 D1). A challenge belongs to a
 * namespace when its DEVICE-SIGNED [DynamicLinkingData.approvalRequestId] starts with [prefix] —
 * read from the stored challenge, never from what a consumer states.
 */
enum class ReservedNamespace(
    val prefix: String,
    val purposes: Set<ScaPurpose>,
    private val shape: Regex,
    /**
     * True when [purposes] belong to this namespace ALONE: a challenge of such a purpose is in the
     * namespace whatever its approvalRequestId says, so no other consumer can spend it even when
     * the id was mis-signed. False for PENSION, whose APPROVAL purpose is shared fleet-wide.
     */
    private val ownsPurposes: Boolean = false,
) {
    /** pension-service operations: `pension-onboarding:…`, `pension-transfer-out:…`, `pension-exit:…`. */
    PENSION("pension-", setOf(ScaPurpose.APPROVAL), Regex("^pension-[a-z]+(-[a-z]+)*:\\S+$")),

    /**
     * ADR-0385 D3 — connecting a customer MCP client: `mcp-connect:<PAR request id>`. Only the
     * authorization server's scoped consumer may spend it; no principal holds it yet (see
     * ConsumerScopes), so until one is declared nobody can — fail closed.
     */
    MCP_CONNECT(
        "mcp-connect:",
        setOf(ScaPurpose.MCP_CLIENT_CONNECT),
        Regex("^mcp-connect:[A-Za-z0-9._~:-]{1,140}$"),
        ownsPurposes = true,
    ),
    ;

    /** Is [approvalRequestId] a well-formed id of this namespace (operation token, colon, reference)? */
    fun isWellFormed(approvalRequestId: String): Boolean = shape.matches(approvalRequestId)

    companion object {
        /** The namespace [challenge] was signed into, or null for an unreserved challenge. */
        fun of(challenge: ScaChallenge): ReservedNamespace? {
            entries.firstOrNull { it.ownsPurposes && challenge.purpose in it.purposes }?.let { return it }
            val id = challenge.dynamicLinkingData?.approvalRequestId ?: return null
            return entries.firstOrNull { id.startsWith(it.prefix) }
        }
    }
}

/**
 * Which approved challenges a consumer may spend (ADR-0335 D1). Decided in the domain, before the
 * compare-and-consume, so a refused attempt never burns the challenge. Complements — never
 * replaces — the party, dynamic-linking and single-use checks.
 */
sealed interface ConsumerScope {
    fun permits(challenge: ScaChallenge): Boolean

    /** Every consumer without a reservation: anything EXCEPT a reserved-namespace challenge. */
    data object General : ConsumerScope {
        override fun permits(challenge: ScaChallenge): Boolean = ReservedNamespace.of(challenge) == null
    }

    /** A scoped consumer: only challenges of its namespace's purposes, signed into its own namespace, well formed. */
    data class Reserved(val namespace: ReservedNamespace) : ConsumerScope {
        override fun permits(challenge: ScaChallenge): Boolean {
            val id = challenge.dynamicLinkingData?.approvalRequestId ?: return false
            // isWellFormed anchors the namespace's own prefix, so it is also the membership test.
            return challenge.purpose in namespace.purposes && namespace.isWellFormed(id)
        }
    }
}
