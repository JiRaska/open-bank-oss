// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

/**
 * Canonical OpenBank role names.
 *
 * `@RolesAllowed` requires compile-time string constants, so these are `const val` rather
 * than enum entries. The companion `Role.values()` lets policy/audit code enumerate them.
 *
 * Mapping to JWT claims: each role appears in `resource_access.openbank-services.roles`
 * as the bare suffix (`admin`, `operator`, …); Quarkus OIDC strips the `ROLE_` prefix and
 * we add it back at the boundary, so use the constants below — not raw strings.
 */
object Roles {
    /** Full administrative access to all resources. Reserved for break-glass scenarios. */
    const val ADMIN = "ROLE_ADMIN"

    /** Day-to-day operations: account servicing, transaction reconciliation, exception handling. */
    const val OPERATOR = "ROLE_OPERATOR"

    /** Read-only access to dashboards, reports, transaction history. No mutation. */
    const val VIEWER = "ROLE_VIEWER"

    /** AML/KYC analyst: can read all PII unmasked, raise alerts, freeze accounts. */
    const val COMPLIANCE = "ROLE_COMPLIANCE"

    /** Read-only access to audit trail. Cannot mutate. Required for SOX/DORA evidence. */
    const val AUDITOR = "ROLE_AUDITOR"

    /** Approves limit overrides, large payments, board-level escalations. */
    const val SUPERVISOR = "ROLE_SUPERVISOR"

    /** KYC officer: onboarding, periodic review, documentation. */
    const val KYC = "ROLE_KYC"

    /**
     * KYC case opener — initiates cases, submits documents, updates check results.
     * May NOT approve or reject cases (four-eyes / maker-checker, ADR-0116).
     */
    const val KYC_OPENER = "ROLE_KYC_OPENER"

    /**
     * KYC case reviewer — approves or rejects cases in UNDER_REVIEW state.
     * Must be a different identity than the case opener (four-eyes / maker-checker, ADR-0116).
     */
    const val KYC_REVIEWER = "ROLE_KYC_REVIEWER"

    /** Payment ops: initiation, recall, return, clearing reconciliation. */
    const val PAYMENTS = "ROLE_PAYMENTS"

    /** Service-to-service authentication. Issued to other openbank services via [ServiceTokenProvider]. */
    const val SERVICE = "ROLE_SERVICE"

    /** All canonical roles, in declaration order. Use for policy/audit enumeration. */
    val ALL: List<String> = listOf(
        ADMIN,
        OPERATOR,
        VIEWER,
        COMPLIANCE,
        AUDITOR,
        SUPERVISOR,
        KYC,
        KYC_OPENER,
        KYC_REVIEWER,
        PAYMENTS,
        SERVICE,
    )
}
