// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.observability

import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.domain.cnb.CnbPolicyRateUpsert
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * `openbank_fx_cnb_policy_rate_rows_total{instrument, outcome}` — what each ingestion did to the
 * stored history: `inserted`, `unchanged`, or `revised`. `revised` is the one to watch: the ČNB does
 * not revise published history, so a non-zero increase means either it did, or the parser read a
 * row differently than before — both need a human. Counters are created eagerly on first use of the
 * instrument so a `revised` series exists at 0 rather than being absent.
 */
@ApplicationScoped
class CnbPolicyRateMetrics {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private fun counter(instrument: CnbPolicyInstrument, outcome: String): Counter? =
        if (registryInstance.isResolvable) {
            Counter.builder(METRIC)
                .tag("instrument", instrument.name)
                .tag("outcome", outcome)
                .register(registryInstance.get())
        } else {
            null
        }

    fun record(instrument: CnbPolicyInstrument, upsert: CnbPolicyRateUpsert) {
        counter(instrument, OUTCOME_INSERTED)?.increment(upsert.inserted.toDouble())
        counter(instrument, OUTCOME_UNCHANGED)?.increment(upsert.unchanged.toDouble())
        counter(instrument, OUTCOME_REVISED)?.increment(upsert.revised.toDouble())
    }

    companion object {
        const val METRIC = "openbank.fx.cnb.policy.rate.rows"
        const val OUTCOME_INSERTED = "inserted"
        const val OUTCOME_UNCHANGED = "unchanged"
        const val OUTCOME_REVISED = "revised"
    }
}
