// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure

import com.openbank.kyb.application.port.out.AgreementProduct
import com.openbank.kyb.application.port.out.BusinessOnboardingSettings
import com.openbank.kyb.infrastructure.registry.CountryPackRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.LocalDate

/**
 * Configuration behind [BusinessOnboardingSettings]. Every property is an object type with a
 * `defaultValue` (and the same value in application.yaml), so none is required and none needs a
 * Kotlin initializer (`configproperty-kotlin-defaults`).
 */
@ApplicationScoped
class ConfiguredOnboardingSettings : BusinessOnboardingSettings {

    /**
     * EU list of high-risk third countries (Delegated Regulation (EU) 2016/1675 as amended). A
     * reference list for this implementation — the deployment must track the Official Journal.
     */
    @ConfigProperty(name = "openbank.kyb.risk.high-risk-countries", defaultValue = DEFAULT_HIGH_RISK)
    lateinit var highRiskCountryList: List<String>

    @ConfigProperty(name = "openbank.kyb.business-product.code", defaultValue = "prod-004")
    lateinit var productCode: String

    @ConfigProperty(name = "openbank.kyb.business-product.name", defaultValue = "Business Current Account")
    lateinit var productName: String

    @ConfigProperty(name = "openbank.kyb.business-product.currency", defaultValue = "EUR")
    lateinit var productCurrency: String

    @Inject lateinit var packs: CountryPackRegistry

    @Inject lateinit var clock: Clock

    override val highRiskCountries: Set<String>
        get() = highRiskCountryList.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    override val businessProduct: AgreementProduct
        get() = AgreementProduct(productCode, productName, productCurrency)

    override fun legalFormLabel(country: String?, legalFormCode: String?, lang: String): String? =
        packs.packFor(country, LocalDate.now(clock))?.label(legalFormCode, lang)

    companion object {
        const val DEFAULT_HIGH_RISK =
            "AF,AO,BF,BO,CD,CI,CM,DZ,HT,IR,KE,KP,LA,LB,MC,ML,MM,MZ,NA,NG,NP,SS,SY,TT,TZ,VE,VG,VN,VU,YE,ZA"
    }
}
