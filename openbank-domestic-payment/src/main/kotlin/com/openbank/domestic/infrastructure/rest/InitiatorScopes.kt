// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest

import com.openbank.domestic.domain.model.InitiatorScope
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional
import java.util.UUID

/**
 * Resolves the [InitiatorScope] of the authenticated caller (ADR-0335 D5). The declared principals
 * and their config keys mirror rules.yaml `scoped_payment_initiators` (held to it by
 * InitiatorScopesRulesParityTest); the debtor account itself is environment configuration.
 */
@ApplicationScoped
class InitiatorScopes(
    @ConfigProperty(name = PENSION_DEBTOR_ACCOUNT_KEY)
    private val pensionDebtorAccount: Optional<String>,
) {
    fun of(identity: SecurityIdentity): InitiatorScope {
        val principal = identity.principal?.name
        if (principal == PENSION_PRINCIPAL) {
            val account = pensionDebtorAccount.orElse("").trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return InitiatorScope.Scoped(PENSION_PRINCIPAL, account)
        }
        return if (STAFF_ROLES.any { identity.hasRole(it) }) InitiatorScope.General else InitiatorScope.Undeclared
    }

    companion object {
        const val PENSION_PRINCIPAL = "service-account-openbank-pension"
        const val PENSION_DEBTOR_ACCOUNT_KEY = "openbank.domestic-payment.scoped-initiators.pension.debtor-account-id"
        val DECLARED: Map<String, String> = mapOf(PENSION_PRINCIPAL to PENSION_DEBTOR_ACCOUNT_KEY)
        private val STAFF_ROLES = listOf("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")
    }
}
