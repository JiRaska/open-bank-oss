// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.application.port.out

import com.openbank.lending.application.usecase.CzCreditPolicyActivationService
import com.openbank.libs.decision.PolicyBundle
import io.smallrye.mutiny.Uni
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import java.time.LocalDate

@Alternative
@Priority(1)
@ApplicationScoped
class ScopedCzCreditPolicy(
    private val revisions: CzCreditPolicyActivationService,
    private val starter: StarterCreditPolicy,
) : CreditPolicyPort {
    override fun activeBundle(asOf: LocalDate): Uni<PolicyBundle> = revisions.active(asOf).flatMap { bundle ->
        if (bundle == null) starter.activeBundle(asOf) else Uni.createFrom().item(bundle)
    }

    override fun forApplication(asOf: LocalDate, jurisdiction: String?, productType: String?): Uni<PolicyBundle> =
        if (jurisdiction == "CZ" && productType == "CONSUMER_CREDIT") activeBundle(asOf) else starter.activeBundle(asOf)

    override fun isCodeSeeded(bundle: PolicyBundle): Boolean =
        bundle.tables.all { it.version < CzCreditPolicyActivationService.FIRST_STORED_REVISION }
}
