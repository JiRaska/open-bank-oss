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
 * Mapping to JWT claims: these are REALM roles, and a token carries them verbatim under
 * `realm_access.roles` — `ROLE_ADMIN`, not `admin`. Nothing strips or re-adds the prefix: the
 * realm templates declare no client roles at all, and there is no `SecurityIdentityAugmentor`
 * anywhere in the fleet (only customer-edge sets `role-claim-path`, and it points at
 * `realm_access/roles`, which is already the default). An earlier version of this KDoc claimed
 * the roles arrived as bare suffixes under `resource_access.openbank-services.roles` and were
 * re-prefixed at the boundary; that was never true, and it is the belief that produced three
 * parallel invented vocabularies (`openbank-employee`, `platform-admin`, `SERVICE`) at 39
 * `@RolesAllowed` sites that answered 403 to every caller (issue #2404).
 *
 * Use the constants below, not raw strings. `.github/scripts/check-roles-allowed-realm.py`
 * enforces that every `@RolesAllowed` name exists in a realm.
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

    /**
     * Machine-to-machine API access — the realm's own name for what a service-account token
     * should carry (`ROLE_API`, "Machine-to-machine API access" in realm-template.json).
     *
     * NOTE: no service account is granted this role today, so an endpoint gated on it alone is
     * still unreachable until someone grants it in Keycloak (issue #2404). That is a deliberate,
     * visible gap rather than the invisible one [SERVICE] created.
     */
    const val API = "ROLE_API"

    /**
     * Service-to-service authentication.
     *
     * DEAD: no realm in this platform declares `ROLE_SERVICE` — not the staff realm, not the
     * customers realm — so no token can ever carry it and this name authorizes nobody. It appears
     * at ~150 `@RolesAllowed` sites across ~29 services, always alongside a live role, so humans
     * still get in and only the M2M caller it was reserved for is silently denied (issue #2404).
     * The JWT-role twin of the unreachable `principal.type == "SERVICE"` rego rules that
     * `check-no-service-principal-type.sh` already forbids.
     *
     * Use [API] for new M2M grants. Retiring the existing sites is a per-service decision — grant
     * the role or delete the path — not a mechanical rename, so this constant stays until they
     * are cleared rather than breaking 29 services' compilation today.
     */
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
        API,
        SERVICE,
    )
}
