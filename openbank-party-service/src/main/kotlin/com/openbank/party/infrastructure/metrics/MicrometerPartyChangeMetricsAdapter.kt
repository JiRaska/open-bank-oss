// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.metrics

import com.openbank.party.application.port.out.PartyChangeMetricsPort
import com.openbank.party.domain.model.PartyChangeMateriality
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * `openbank.parties.change.classified{materiality=MATERIAL|NON_MATERIAL|NO_CHANGE}`.
 *
 * The registry is resolved through [Instance] the way `DomainMetrics` does it: a test profile
 * without the Micrometer extension must not fail bean construction, and a metric that cannot be
 * recorded is not a reason to reject a party update.
 */
@ApplicationScoped
class MicrometerPartyChangeMetricsAdapter : PartyChangeMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    override fun changeClassified(materiality: PartyChangeMateriality) {
        val registry = if (registryInstance.isResolvable) registryInstance.get() else return
        Counter.builder(METRIC)
            .tags("materiality", materiality.name)
            .register(registry)
            .increment()
    }

    private companion object {
        const val METRIC = "openbank.parties.change.classified"
    }
}
