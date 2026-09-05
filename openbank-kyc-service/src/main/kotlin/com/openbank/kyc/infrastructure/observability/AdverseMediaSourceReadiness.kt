// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.observability

import com.openbank.kyc.application.port.out.AdverseMediaScreeningPort
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Publishes whether this platform has an adverse-media source at all, as
 * `openbank_kyc_adverse_media_source_configured` (1 = a source is wired, 0 = none).
 *
 * Today the value is **0**, and that is the deliverable: ADR-0256 D5's "no adverse-media source
 * exists" stops being a sentence in a decision record and becomes a number an operator or auditor
 * can query. The gauge is derived from [AdverseMediaScreeningPort.sourceId] rather than from a
 * config flag, so it cannot claim coverage the wired adapter does not have.
 *
 * `@Startup` is load-bearing. `@ApplicationScoped` is lazy: Quarkus creates the bean on first
 * use through a client proxy, so a boot-time warning in `init {}` or `@PostConstruct` on a bean
 * nothing calls never reaches a pod log. This repo shipped a PAdES adapter warning that its seals
 * were "worthless as evidence" and that line never once appeared in production (#1299). Without
 * `@Startup` the gauge below would likewise never be registered, and an absent series reads to
 * every dashboard exactly like a healthy one that has not scraped yet.
 */
@Startup
@ApplicationScoped
class AdverseMediaSourceReadiness {

    @Inject
    lateinit var port: AdverseMediaScreeningPort

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private val log = Logger.getLogger(AdverseMediaSourceReadiness::class.java)

    /** 1 when a real adverse-media source backs the port, 0 when none is configured. */
    fun configured(): Int = if (port.sourceId != null) 1 else 0

    @PostConstruct
    fun register() {
        if (configured() == 0) {
            log.warn(
                "No adverse-media source is configured (ADR-0256 D5, issue #4459): KYC cases carry NO " +
                    "adverse-media coverage. openbank_kyc_adverse_media_source_configured=0. Any " +
                    "adverse-media check on a case will resolve to MANUAL_REVIEW, never PASSED.",
            )
        } else {
            log.infof("Adverse-media source configured: %s", port.sourceId)
        }
        if (registryInstance.isResolvable) {
            registryInstance.get().gauge(
                "openbank_kyc_adverse_media_source_configured",
                this,
            ) { it.configured().toDouble() }
        }
    }
}
