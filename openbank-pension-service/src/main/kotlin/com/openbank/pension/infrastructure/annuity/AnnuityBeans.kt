// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.openbank.pension.application.annuity.AnnuityAdapterCatalog
import com.openbank.pension.application.annuity.AnnuityMarketplaceService
import com.openbank.pension.application.annuity.AnnuityProviderAdapter
import com.openbank.pension.application.annuity.AnnuityProviderRegistryService
import com.openbank.pension.application.annuity.AnnuityProviderRepository
import com.openbank.pension.application.annuity.AnnuityPurchaseRepository
import com.openbank.pension.application.annuity.AnnuityRails
import com.openbank.pension.application.annuity.AnnuityStores
import com.openbank.pension.application.exit.PaymentInstructionRepository
import com.openbank.pension.application.exit.PayoutPaymentPort
import com.openbank.pension.application.exit.PayoutRequestRepository
import com.openbank.pension.application.exit.ScaVerificationPort
import com.openbank.pension.application.port.`in`.PensionContractUseCase
import com.openbank.pension.application.port.out.FundAdministrationPort
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import io.quarkus.arc.All
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration

/** Every [AnnuityProviderAdapter] bean of THIS build, by kind (the simulator exists only in dev/test). */
@ApplicationScoped
class CdiAnnuityAdapterCatalog : AnnuityAdapterCatalog {
    @Inject
    @field:All
    lateinit var adapters: MutableList<AnnuityProviderAdapter>

    override fun adapterFor(kind: String): AnnuityProviderAdapter? = adapters.firstOrNull { it.kind == kind }

    override fun kinds(): Set<String> = adapters.map { it.kind }.toSet()
}

/** CDI wiring of the framework-free annuity services (#12383). */
@ApplicationScoped
class AnnuityBeans {

    @Produces
    @Singleton
    @Suppress("LongParameterList")
    fun annuityMarketplace(
        contracts: PensionContractUseCase,
        purchases: AnnuityPurchaseRepository,
        providers: AnnuityProviderRepository,
        payouts: PayoutRequestRepository,
        instructions: PaymentInstructionRepository,
        adapters: AnnuityAdapterCatalog,
        sca: ScaVerificationPort,
        payments: PayoutPaymentPort,
        fund: FundAdministrationPort,
        packs: JurisdictionPackRegistry,
        clock: Clock,
        @ConfigProperty(name = "openbank.pension.annuity.quote-timeout", defaultValue = "PT5S") quoteTimeout: Duration,
    ) = AnnuityMarketplaceService(
        contracts,
        AnnuityStores(purchases, providers, payouts, instructions),
        AnnuityRails(adapters, sca, payments, fund),
        packs,
        clock,
        quoteTimeout,
    )

    @Produces
    @Singleton
    fun annuityProviderRegistry(
        providers: AnnuityProviderRepository,
        adapters: AnnuityAdapterCatalog,
        clock: Clock,
        @ConfigProperty(name = "openbank.pension.annuity.allow-insecure-endpoints", defaultValue = "false")
        allowInsecure: Boolean,
    ) = AnnuityProviderRegistryService(providers, adapters, clock, allowInsecure)
}
