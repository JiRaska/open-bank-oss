// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.infrastructure.rest

import com.openbank.sdd.domain.model.MandateInitiatorScope
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Resolves the caller's [MandateInitiatorScope] (ADR-0335 D6). Declared principals and config keys
 * mirror rules.yaml `scoped_payment_initiators` (MandateInitiatorScopesRulesParityTest).
 */
@ApplicationScoped
class MandateInitiatorScopes(
    @ConfigProperty(name = PENSION_CREDITOR_KEY)
    private val pensionCreditor: Optional<String>,
) {
    fun of(identity: SecurityIdentity): MandateInitiatorScope = if (identity.principal?.name == PENSION_PRINCIPAL) {
        MandateInitiatorScope.Scoped(PENSION_PRINCIPAL, pensionCreditor.orElse("").trim().ifEmpty { null })
    } else {
        MandateInitiatorScope.General
    }

    companion object {
        const val PENSION_PRINCIPAL = "service-account-openbank-pension"
        const val PENSION_CREDITOR_KEY = "openbank.sdd.scoped-initiators.pension.creditor-identifier"
        val DECLARED: Map<String, String> = mapOf(PENSION_PRINCIPAL to PENSION_CREDITOR_KEY)
    }
}
