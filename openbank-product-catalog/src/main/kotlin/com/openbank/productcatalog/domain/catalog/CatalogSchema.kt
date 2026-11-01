// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain.catalog

import java.time.Instant

/** Immutable, hash-addressed schema document contributed by a trusted industry pack. */
data class CatalogSchema(
    val ref: SchemaRef,
    val document: CatalogValue.ObjectValue,
    val sha256: String,
    val registeredAt: Instant,
    /** Revision approval policy the pack declared for this exact schema version; null = maker-checker only. */
    val approvalPolicy: RevisionApprovalPolicy? = null,
) {
    init {
        require(SHA256_PATTERN.matches(sha256)) { "sha256 must be 64 lowercase hexadecimal characters" }
    }

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Role-qualified approvals a pack requires before a revision of one schema version may publish.
 *
 * The kernel never names a role: [roles] are codes the pack declares, each bound to the internal
 * [ApprovalRoleSpec.authority] a principal must hold. Independence is a kernel invariant and is not
 * configurable: every role is approved by a different principal and never by the maker.
 */
data class RevisionApprovalPolicy(
    val roles: List<ApprovalRoleSpec>,
    val requireEffectiveFrom: Boolean,
    /** Top-level attribute values the draft must carry before any role may approve it. */
    val requiredAttributes: Map<String, String> = emptyMap(),
) {
    init {
        require(roles.isNotEmpty()) { "an approval policy needs at least one role" }
        require(roles.map(ApprovalRoleSpec::code).toSet().size == roles.size) { "approval role codes must be unique" }
    }

    fun role(code: String): ApprovalRoleSpec? = roles.firstOrNull { it.code == code }

    val authorities: Set<String> get() = roles.mapTo(linkedSetOf(), ApprovalRoleSpec::authority)
}

data class ApprovalRoleSpec(val code: String, val authority: String) {
    init {
        require(CODE_PATTERN.matches(code)) { "approval role code must match ${CODE_PATTERN.pattern}" }
        require(CODE_PATTERN.matches(authority)) { "approval authority must match ${CODE_PATTERN.pattern}" }
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,31}$")
    }
}

/** One immutable, authenticated approval of the exact content digest of a draft revision. */
data class RevisionApproval(
    val revisionId: java.util.UUID,
    val role: String,
    val issuer: String,
    val subject: String,
    val digest: String,
    val reason: String,
    val approvedAt: Instant,
)

data class SchemaViolation(val instancePath: String, val schemaPath: String, val keyword: String, val message: String)

sealed interface SchemaValidationResult {
    data object Valid : SchemaValidationResult
    data class Invalid(val violations: List<SchemaViolation>) : SchemaValidationResult {
        init {
            require(violations.isNotEmpty()) { "invalid result must contain at least one violation" }
        }
    }
}

/** Port implemented by the JSON Schema 2020-12 infrastructure adapter. */
fun interface CatalogSchemaValidator {
    fun validate(schema: CatalogSchema, attributes: CatalogValue.ObjectValue): SchemaValidationResult
}
