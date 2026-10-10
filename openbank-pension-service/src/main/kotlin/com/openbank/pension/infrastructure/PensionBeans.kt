// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure

import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.ParticipantNotifier
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.usecase.PensionContractService
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * CDI wiring for the framework-free application and domain layers.
 *
 * The registry is `@Startup`: `@ApplicationScoped` is lazy, and a malformed pack must stop the
 * pod at boot, not answer 500 on the first contract request after the deploy went green.
 */
@ApplicationScoped
class PensionBeans {

    @Produces
    @Startup
    @ApplicationScoped
    fun packRegistry(): JurisdictionPackRegistry = JurisdictionPackLoader.loadRegistry()

    @Produces
    @ApplicationScoped
    fun contractUseCase(
        repository: PensionContractRepository,
        registry: JurisdictionPackRegistry,
        clock: Clock,
        notifier: ParticipantNotifier,
        onboarding: jakarta.enterprise.inject.Instance<com.openbank.pension.application.onboarding.OnboardingService>,
        sca: com.openbank.pension.application.exit.ScaVerificationPort,
    ): PensionContractUseCase = PensionContractService(
        repository,
        registry,
        clock,
        notifier,
        // Resolved per call: OnboardingService owns the assessment in force (#12384).
        object : com.openbank.pension.application.port.out.StrategySuitabilityPort {
            override suspend fun authorize(
                request: com.openbank.pension.application.port.out.StrategySuitabilityRequest,
            ) = onboarding.get().authorizeStrategy(request)

            override suspend fun record(approval: com.openbank.pension.application.port.out.StrategyApproval) =
                onboarding.get().recordStrategyApproval(approval)
        },
        sca,
    )
}
